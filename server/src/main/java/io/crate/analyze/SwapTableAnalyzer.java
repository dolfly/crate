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

package io.crate.analyze;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.crate.analyze.expressions.ExpressionAnalysisContext;
import io.crate.analyze.expressions.ExpressionAnalyzer;
import io.crate.analyze.relations.FieldProvider;
import io.crate.exceptions.OperationOnInaccessibleRelationException;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Schemas;
import io.crate.metadata.SearchPath;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.table.Operation;
import io.crate.role.Role;
import io.crate.sql.tree.Expression;
import io.crate.sql.tree.SwapTable;

public final class SwapTableAnalyzer {

    public static final String DROP_SOURCE = "drop_source";
    private final NodeContext nodeCtx;
    private final Schemas schemas;

    SwapTableAnalyzer(NodeContext nodeCtx, Schemas schemas) {
        this.nodeCtx = nodeCtx;
        this.schemas = schemas;
    }

    public AnalyzedSwapTable analyze(SwapTable<Expression> swapTable,
                                     CoordinatorTxnCtx txnCtx,
                                     ParamTypeHints typeHints) {
        Map<String, Expression> properties = swapTable.properties().toMap(HashMap::new);
        Expression dropSourceExpr = properties.remove(DROP_SOURCE);
        if (!properties.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid options for ALTER CLUSTER SWAP TABLE: " + String.join(", ", properties.keySet()));
        }
        Symbol dropSource;
        if (dropSourceExpr == null) {
            dropSource = Literal.BOOLEAN_FALSE;
        } else {
            ExpressionAnalyzer exprAnalyzer = new ExpressionAnalyzer(
                txnCtx, nodeCtx, typeHints, FieldProvider.UNSUPPORTED, null);
            dropSource = exprAnalyzer.convert(dropSourceExpr, new ExpressionAnalysisContext(txnCtx.sessionSettings()));
        }
        SearchPath searchPath = txnCtx.sessionSettings().searchPath();
        Role user = txnCtx.sessionSettings().sessionUser();

        var source = schemas.findRelation(swapTable.source(), Operation.ALTER_TABLE_RENAME, user, searchPath);
        if (!(source instanceof DocTableInfo sourceRelation)) {
            throw new OperationOnInaccessibleRelationException(
                source.ident(),
                String.format(
                    Locale.ENGLISH, "The relation \"%s\" doesn't support or allow %s operations",
                    source.ident().name(), Operation.ALTER_TABLE_RENAME
                ));
        }
        var target = schemas.findRelation(swapTable.target(), Operation.ALTER_TABLE_RENAME, user, searchPath);
        if (!(target instanceof DocTableInfo targetRelation)) {
            throw new OperationOnInaccessibleRelationException(
                target.ident(),
                String.format(
                    Locale.ENGLISH, "The relation \"%s\" doesn't support or allow %s operations",
                    target.ident().name(), Operation.ALTER_TABLE_RENAME
                ));
        }

        return new AnalyzedSwapTable(sourceRelation, targetRelation, dropSource);
    }
}
