/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.execution.dml.upsert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.crate.analyze.Id;
import io.crate.common.collections.Maps;
import io.crate.data.Input;
import io.crate.execution.dml.IndexItem;
import io.crate.execution.dml.Indexer;
import io.crate.execution.engine.collect.CollectExpression;
import io.crate.expression.BaseImplementationSymbolVisitor;
import io.crate.expression.reference.Doc;
import io.crate.expression.reference.DocRefResolver;
import io.crate.expression.reference.ReferenceResolver;
import io.crate.expression.symbol.InputColumn;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.GeneratedReference;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Reference;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.doc.DocTableInfo;

/**
 * Uses a stored document to convert an UPDATE into an absolute INSERT
 * (or rather: the structure required for indexing)
 *
 * <p>
 * It doesn't re-generate values for generated columns and doesn't handle check constraints.
 * This is left to the {@link Indexer} which should be used on the result.
 *
 * Despite this, it does include undeterministic synthetic columns within {@link #columns()}
 * This is necessary to ensure that in upsert statements, both the INSERT and the UPDATE case
 * have the same column ordering, which is:
 *
 * <ol>
 *   <li>Insert columns</li>
 *   <li>Undeterministic generated columns</li>
 *   <li>All remaining columns (update only)</li>
 * </ol>
 *
 * <p>
 * Otherwise in bulk operations like follows the payload/column order could get mixed up
 * between insert/update:
 * </p>
 *
 * <p>
 * Table with id, value, x, y, rnd_val
 * </p>
 *
 * <pre>
 *   INSERT INTO tbl (id, value) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET x = x + 1
 * </pre>
 *
 * <pre>
 * Bulk args:
 *  [1, 1] -> [x] conflict  -> payload: [id, value, rnd_val, x, y]  -> [1, 1, 42, 10, 20]
 *  [2, 2] -> [ ] conflict  -> payload: [id, value, rnd_val]        -> [2, 2, 23]
 * </pre>
 *
 * <p>
 * Examples:
 * </p>
 *
 * <pre>
 *  Table with columns (x, y, z) and existing record: (1, 2, 3)
 *
 *  UPDATE tbl SET
 *      x = x + 2,
 *      y = 42
 *
 *  Result:
 *      targetColumns: [x,  y, z]
 *      values:        [3, 42, 3]
 * </pre>
 *
 * <pre>
 *  Table with columns (x, o (y, z)) and existing record (1, {y=10, z=20})
 *
 *  UPDATE tbl SET
 *      o['y'] = 40
 *
 *  Result:
 *      targetColumns [x,            o]
 *      values:       [1, {y=40, z=20}]
 *  </pre>
 *
 *
 *  <pre>
 *   Table with columns (x, y, z) and existing record: (1, 2, 3)
 *
 *   INSERT INTO tbl (x, y, z) values (1, 10, 20)
 *   ON CONFLICT (x) DO UPDATE SET
 *      y = y + excluded.y
 *
 *  Result:
 *      targetColumns: [x,  y, z]
 *      values:        [1, 12, 3]
 *  </pre>
 *
 *  <pre>
 *      Table with columns (x, y, z) and existing record: (1, 2, 3)
 *
 *  INSERT INTO tbl (z) VALUES (3)
 *  ON CONFLICT (z) DO UPDATE SET
 *      y = 20
 *
 *  Result:
 *      targetColumns: [z, x,  y]
 *      values:        [3, 1, 20]
 **/
public final class UpdateToInsert {

    private final DocTableInfo table;
    private final Evaluator eval;
    private final List<Reference> updateColumns;
    private final ArrayList<Reference> columns;


    public record Update(List<String> pkValues, Object[] insertValues, long version) {}

    record Values(Doc doc, Object[] excludedValues) {
    }

    private static class Evaluator extends BaseImplementationSymbolVisitor<Values> {

        private final ReferenceResolver<CollectExpression<Doc, ?>> refResolver;

        private Evaluator(NodeContext nodeCtx,
                          TransactionContext txnCtx,
                          ReferenceResolver<CollectExpression<Doc, ?>> refResolver) {
            super(txnCtx, nodeCtx);
            this.refResolver = refResolver;
        }

        @Override
        public Input<?> visitInputColumn(InputColumn inputColumn, Values context) {
            return Literal.ofUnchecked(inputColumn.valueType(), context.excludedValues[inputColumn.index()]);
        }

        @Override
        public Input<?> visitReference(Reference symbol, Values values) {
            CollectExpression<Doc, ?> expr = refResolver.getImplementation(symbol);
            expr.setNextRow(values.doc);
            return expr;
        }
    }

    public UpdateToInsert(NodeContext nodeCtx,
                          TransactionContext txnCtx,
                          DocTableInfo table,
                          String[] updateColumns,
                          @Nullable List<Reference> insertColumns) {
        var refResolver = new DocRefResolver(table.partitionedBy());
        this.table = table;
        this.eval = new Evaluator(nodeCtx, txnCtx, refResolver);
        this.updateColumns = new ArrayList<>(updateColumns.length);
        this.columns = new ArrayList<>();
        List<String> updateColumnList = Arrays.asList(updateColumns);
        boolean errorOnUnknownObjectKey = txnCtx.sessionSettings().errorOnUnknownObjectKey();

        if (insertColumns != null) {
            this.columns.addAll(insertColumns);
        }
        for (var ref : table.defaultExpressionColumns()) {
            if (!ref.defaultExpression().isDeterministic() && !this.columns.contains(ref)) {
                this.columns.add(ref);
            }
        }
        for (var ref : table.generatedColumns()) {
            if (!ref.isDeterministic() && !this.columns.contains(ref)) {
                this.columns.add(ref);
            }
        }
        for (var ref : table.rootColumns()) {
            if (ref instanceof GeneratedReference && !updateColumnList.contains(ref.column().fqn())) {
                continue;
            }
            if (!this.columns.contains(ref)) {
                this.columns.add(ref);
            }
        }
        for (String columnName : updateColumns) {
            ColumnIdent column = ColumnIdent.fromPath(columnName);
            Reference existingRef = table.getReference(column);
            if (existingRef == null) {
                Reference reference = table.getDynamic(column, true, errorOnUnknownObjectKey);
                if (column.isRoot()) {
                    columns.add(reference);
                    this.updateColumns.add(reference);
                } else {
                    ColumnIdent root = column.getRoot();
                    Reference rootReference = table.getReference(root);
                    if (rootReference == null) {
                        throw new UnsupportedOperationException(String.format(
                            Locale.ENGLISH,
                            "Cannot add new child `%s` if parent column is missing",
                            column
                        ));
                    } else {
                        this.updateColumns.add(reference);
                    }
                }
            } else {
                this.updateColumns.add(existingRef);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public IndexItem convert(Doc doc, Symbol[] updateAssignments, Object[] excludedValues) {
        Values values = new Values(doc, excludedValues);
        Object[] insertValues = new Object[columns.size()];
        Iterator<Reference> it = columns.iterator();
        for (int i = 0; it.hasNext(); i++) {
            Reference ref = it.next();
            int updateIdx = updateColumns.indexOf(ref);
            if (updateIdx >= 0) {
                Symbol symbol = updateAssignments[updateIdx];
                Object value = symbol.accept(eval, values).value();
                assert ref.column().isRoot()
                    : "If updateColumns.indexOf(reference-from-table.columns()) is >= 0 it must be a top level reference";
                insertValues[i] = value;
            } else if (ref instanceof GeneratedReference genRef && !genRef.isDeterministic()) {
                insertValues[i] = null;
            } else if (ref.defaultExpression() != null && !ref.defaultExpression().isDeterministic()) {
                insertValues[i] = null;
            } else {
                insertValues[i] = ref.accept(eval, values).value();
            }
        }
        for (int i = 0; i < updateColumns.size(); i++) {
            Reference updateColumn = updateColumns.get(i);
            ColumnIdent column = updateColumn.column();
            if (column.isRoot()) {
                // Handled in previous loop over the columns
                continue;
            }
            ColumnIdent root = column.getRoot();
            int idx = Reference.indexOf(columns, root);
            assert idx > -1 : "Root of updateColumns must exist in table columns";
            Symbol assignment = updateAssignments[i];
            Object value = assignment.accept(eval, values).value();
            ColumnIdent targetPath = column.shiftRight();
            Map<String, Object> source = (Map<String, Object>) insertValues[idx];
            if (source == null) {
                source = new HashMap<>();
                insertValues[idx] = source;
            }
            Maps.mergeInto(source, targetPath.name(), targetPath.path(), value);
        }
        return new IndexItem.StaticItem(
            doc.getId(),
            Id.decode(table.primaryKey(), doc.getId()),
            insertValues,
            doc.getSeqNo(),
            doc.getPrimaryTerm()
        );
    }

    public List<Reference> columns() {
        return columns;
    }
}
