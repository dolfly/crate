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

package io.crate.execution.dml;

import static io.crate.execution.dml.ArrayIndexer.ARRAY_LENGTH_FIELD_PREFIX;
import static io.crate.execution.dml.ArrayIndexer.ARRAY_VALUES_FIELD_PREFIX;
import static io.crate.execution.dml.ArrayIndexer.toArrayLengthFieldName;
import static io.crate.testing.Asserts.assertThat;
import static io.crate.testing.Asserts.isReference;
import static io.crate.types.GeoShapeType.Names.TREE_BKD;
import static io.crate.types.GeoShapeType.Names.TREE_GEOHASH;
import static io.crate.types.GeoShapeType.Names.TREE_LEGACY_QUADTREE;
import static io.crate.types.GeoShapeType.Names.TREE_QUADTREE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elasticsearch.cluster.metadata.Metadata.COLUMN_OID_UNASSIGNED;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.junit.Ignore;
import org.junit.Test;

import com.carrotsearch.hppc.IntArrayList;

import io.crate.common.collections.Lists;
import io.crate.common.collections.MapBuilder;
import io.crate.execution.ddl.tables.AddColumnRequest;
import io.crate.execution.ddl.tables.AlterTableTask;
import io.crate.execution.ddl.tables.TransportAddColumn;
import io.crate.expression.reference.doc.lucene.SourceParser;
import io.crate.expression.symbol.DynamicReference;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.PartitionName;
import io.crate.metadata.Reference;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.RelationName;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.doc.DocTableInfoFactory;
import io.crate.sql.tree.BitString;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.DataTypeTesting;
import io.crate.testing.IndexEnv;
import io.crate.testing.SQLExecutor;
import io.crate.testing.UseNewCluster;
import io.crate.types.ArrayType;
import io.crate.types.BitStringType;
import io.crate.types.BooleanType;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.FloatVectorType;
import io.crate.types.GeoShapeType;
import io.crate.types.IpType;
import io.crate.types.ObjectType;

public class IndexerTest extends CrateDummyClusterServiceUnitTest {

    public static IndexItem item(Object ... values) {
        return new IndexItem.StaticItem("dummy-id-1", List.of(), values, 0L, 0L);
    }

    public static Indexer getIndexer(SQLExecutor e,
                                     String tableName,
                                     String ... columns) {
        DocTableInfo table = e.resolveTableInfo(tableName);
        return new Indexer(
            List.of(),
            table,
            table.versionCreated(),
            new CoordinatorTxnCtx(e.getSessionSettings()),
            e.nodeCtx,
            Stream.of(columns)
                .map(x -> table.resolveColumn(x, true, false))
                .toList(),
            null
        );
    }

    private DocTableInfo addColumns(SQLExecutor e, DocTableInfo table, List<Reference> newColumns) throws Exception {
        var addColumnTask = new AlterTableTask<>(
            e.nodeCtx, table.ident(), e.fulltextAnalyzerResolver(), TransportAddColumn.ADD_COLUMN_OPERATOR);
        AddColumnRequest request = new AddColumnRequest(
                table.ident(),
                newColumns,
                Map.of(),
                new IntArrayList(0)
        );
        ClusterState newState = addColumnTask.execute(clusterService.state(), request);
        return new DocTableInfoFactory(e.nodeCtx).create(table.ident(), newState.metadata());
    }

    static Map<String, Object> sourceMap(ParsedDocument parsedDocument, DocTableInfo tableInfo) throws Exception {
        var sourceParser = new SourceParser(tableInfo.lookupNameBySourceKey(), true);
        return sourceParser.parse(parsedDocument.source());
    }

    static String source(ParsedDocument parsedDocument, DocTableInfo tableInfo) throws Exception {
        return Strings.toString(
                XContentBuilder.builder(JsonXContent.JSON_XCONTENT)
                        .map(sourceMap(parsedDocument, tableInfo))
        );
    }

    @Test
    public void test_index_object_with_dynamic_column_creation() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o object as (x int))");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference o = table.getReference(ColumnIdent.of("o"));
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(o),
            null
        );

        Map<String, Object> value = Map.of("x", 10, "y", 20);
        IndexItem item = item(value);
        List<Reference> newColumns = indexer.collectSchemaUpdates(item);
        DocTableInfo actualTable = addColumns(executor, table, newColumns);
        indexer.updateTargets(actualTable::getReference);
        ParsedDocument parsedDoc = indexer.index(item);
        assertThat(parsedDoc.doc().getFields())
            .hasSize(10);

        assertThat(newColumns)
            .hasSize(1);

        assertThat(source(parsedDoc, actualTable)).isIn(
            "{\"o\":{\"x\":10,\"y\":20}}",
            "{\"o\":{\"y\":20,\"x\":10}}"
        );

        value = Map.of("x", 10, "y", 20);
        newColumns = indexer.collectSchemaUpdates(item(value));
        assertThat(newColumns).isEmpty();

        assertTranslogParses(parsedDoc, actualTable);
    }

    @Test
    public void test_create_dynamic_object_with_nested_columns() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o object as (x int))");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference o = table.getReference(ColumnIdent.of("o"));
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(o),
            null
        );

        Map<String, Object> value = Map.of("x", 10, "obj", Map.of("y", 20, "z", 30));
        IndexItem item = item(value);
        List<Reference> newColumns = indexer.collectSchemaUpdates(item);

        // Add new columns so they get
        //  1. an OID applied
        //  2. the correct data type, in this case the `o['obj']` must contain its new members 'y' + 'z'
        DocTableInfo actualTable = addColumns(executor, table, newColumns);

        indexer.updateTargets(actualTable::getReference);
        ParsedDocument parsedDoc = indexer.index(item);
        assertThat(parsedDoc.doc().getFields())
            .hasSize(11);

        assertThat(newColumns)
            .satisfiesExactly(
                col1 -> assertThat(col1).hasName("o['obj']"),
                col2 -> assertThat(col2)
                    .hasName("o['obj']['y']"),
                col3 -> assertThat(col3)
                    .hasName("o['obj']['z']")
            );

        assertTranslogParses(parsedDoc, actualTable);
    }

    @Test
    public void test_ignored_object_values_are_ignored_and_added_to_source() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o object (ignored))");
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "o");
        ParsedDocument doc = indexer.index(item(Map.of("x", 10)));
        assertThat(source(doc, table)).isEqualToIgnoringWhitespace(
            """
            {"o": {"x": 10}}
            """
        );
        assertThat(doc.doc().getFields("o.x")).isEmpty();
        assertThat(doc.doc().getFields("o.y")).isEmpty();
        assertThat(doc.doc().getFields())
            .as("source, seqNo, id...")
            .hasSize(9);

        assertTranslogParses(doc, table);
    }

    @Test
    public void test_create_dynamic_array() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o object as (x int))");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference o = table.getReference(ColumnIdent.of("o"));
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(o),
            null
        );

        Map<String, Object> value = Map.of("x", 10, "xs", List.of(2, 3, 4));
        IndexItem item = item(value);
        List<Reference> newColumns = indexer.collectSchemaUpdates(item);
        DocTableInfo actualTable = addColumns(executor, table, newColumns);
        indexer.updateTargets(actualTable::getReference);
        ParsedDocument parsedDoc = indexer.index(item);

        assertThat(newColumns)
            .satisfiesExactly(
                col1 -> assertThat(col1)
                    .hasName("o['xs']")
                    .hasType(new ArrayType<>(DataTypes.LONG))
            );

        assertThat(source(parsedDoc, actualTable)).isIn(
            "{\"o\":{\"x\":10,\"xs\":[2,3,4]}}",
            "{\"o\":{\"xs\":[2,3,4],\"x\":10}}"
        );

        assertThat(parsedDoc.doc().getFields())
            .hasSize(14);

        assertTranslogParses(parsedDoc, actualTable);
    }

    @Test
    public void test_adds_default_values() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int, y int default 0)");
        CoordinatorTxnCtx txnCtx = new CoordinatorTxnCtx(executor.getSessionSettings());
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference x = table.getReference(ColumnIdent.of("x"));
        Reference y = table.getReference(ColumnIdent.of("y"));
        var indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            txnCtx,
            executor.nodeCtx,
            List.of(y),
            null
        );
        var parsedDoc = indexer.index(item(new Object[] { null }));
        assertThat(source(parsedDoc, table))
            .as("If explicit null value is provided, the default expression is not applied")
            .isEqualTo("{}");
        assertThat(parsedDoc.doc().getFields())
            .hasSize(8);

        indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            txnCtx,
            executor.nodeCtx,
            List.of(x),
            null
        );
        parsedDoc = indexer.index(item(10));
        assertThat(source(parsedDoc, table)).isEqualTo(
            "{\"x\":10,\"y\":0}"
        );
        assertThat(parsedDoc.doc().getFields())
            .hasSize(10);
    }

    @Test
    public void test_adds_generated_column() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int, y int as x + 2)");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference x = table.getReference(ColumnIdent.of("x"));
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(x),
            null
        );
        var parsedDoc = indexer.index(item(1));
        assertThat(source(parsedDoc, table)).isEqualTo(
            "{\"x\":1,\"y\":3}"
        );
    }

    @Test
    public void test_collect_columns_of_dynamic_object_array() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("CREATE TABLE tbl (data OBJECT(DYNAMIC))");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference x = table.getReference(ColumnIdent.of("data"));
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(x),
            null
        );

        // Create 1 object with a nested object array of 2 elements with 4 columns each
        Map<String, Object> item = Map.of(
            "a", 1,
            "b", "foo",
            "c", 2,
            "d", 123
        );
        ArrayList<Map<String, Object>> list = new ArrayList<>();
        IntStream.range(0, 2).forEach(_ -> list.add(item));
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);

        var references = indexer.collectSchemaUpdates(item(data));
        // Must result in 5 columns, duplicates of each of the 2 elements must be ignored
        assertThat(references).satisfiesExactly(
            isReference("data['list']"),
            isReference("data['list']['a']"),
            isReference("data['list']['b']"),
            isReference("data['list']['c']"),
            isReference("data['list']['d']")
        );
    }

    @Test
    public void test_generated_partitioned_column_is_not_indexed_or_included_in_source() throws Exception {
        PartitionName partition = new PartitionName(new RelationName("doc", "tbl"), List.of("3"));
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable(
                "create table doc.tbl (x int, p int as x + 2) partitioned by (p)",
                partition.asIndexName()
            );
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference x = table.getReference(ColumnIdent.of("x"));
        Indexer indexer = new Indexer(
            partition.values(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(x),
            null
        );
        var parsedDoc = indexer.index(item(1));
        assertThat(source(parsedDoc, table)).isEqualTo(
            "{\"x\":1}"
        );
    }

    @Test
    public void test_default_and_generated_column_within_object() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o object as (x int default 0, y int as o['x'] + 2, z int))");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference o = table.getReference(ColumnIdent.of("o"));
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(o),
            null
        );

        var parsedDoc = indexer.index(item(Map.of("z", 20)));
        assertThat(source(parsedDoc, table)).isEqualTo(
            "{\"o\":{\"x\":0,\"y\":2,\"z\":20}}"
        );
        assertTranslogParses(parsedDoc, table);
    }

    @Test
    @Ignore("https://github.com/crate/crate/issues/14189")
    /*
     * This isolated test would pass without the validation in {@link AnalyzedColumnDefinition} since it covers only
     * part of code path but actually running a {@code CREATE TABLE tbl (x int, o object as (x int) default {x=10})}
     * throws:
     *    MapperParsingException[Failed to parse mapping: Mapping definition for [o] has unsupported
     *    parameters:  [default_expr : {"x"=10}]]}
     */
    public void test_default_for_full_object() throws Exception {
        var executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int, o object as (x int) default {x=10})");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference x = table.getReference(ColumnIdent.of("x"));
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(x),
            null
        );
        var parsedDoc = indexer.index(item(42));
        assertThat(source(parsedDoc, table)).isEqualToIgnoringWhitespace("""
            {
                "x":42,
                "o": {
                    "x": 10
                }
            }
            """);
    }

    @Test
    public void test_validates_user_provided_value_for_generated_columns() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int, y int as x + 2, o object as (z int as x + 3))");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference x = table.getReference(ColumnIdent.of("x"));
        Reference y = table.getReference(ColumnIdent.of("y"));
        Reference o = table.getReference(ColumnIdent.of("o"));
        Indexer indexer1 = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(x, y),
            null
        );
        assertThatThrownBy(() -> indexer1.index(item(1, 2)))
            .hasMessage("Given value 2 for generated column y does not match calculation (x + 2) = 3");

        Indexer indexer2 = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(x, o),
            null
        );
        assertThatThrownBy(() -> indexer2.index(item(1, Map.of("z", 10))))
            .hasMessage("Given value 10 for generated column o['z'] does not match calculation (x + 3) = 4");
    }

    @Test
    public void test_index_fails_if_not_null_column_has_null_value() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int not null, y int default 0 NOT NULL)");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(
                table.getReference(ColumnIdent.of("x"))
            ),
            null
        );
        assertThatThrownBy(() -> indexer.index(item(new Object[] { null })))
            .hasMessage("\"x\" must not be null");

        ParsedDocument parsedDoc = indexer.index(item(10));
        assertThat(source(parsedDoc, table)).isEqualToIgnoringWhitespace("""
            {"x":10, "y":0}
            """);
    }

    @Test
    public void test_index_fails_if_check_constraint_returns_false() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("""
                create table tbl (
                    x int not null constraint c1 check (x > 10),
                    y int constraint c2 check (y < 3),
                    z int default 0 check (z > 0)
                )
                """);
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(
                table.getReference(ColumnIdent.of("x")),
                table.getReference(ColumnIdent.of("z"))
            ),
            null
        );
        assertThatThrownBy(() -> indexer.index(item(8, 10)))
            .hasMessage("Failed CONSTRAINT c1 CHECK (\"x\" > 10) for values: [8, 10]");

        ParsedDocument parsedDoc = indexer.index(item(20, null));
        assertThat(source(parsedDoc, table)).isEqualToIgnoringWhitespace("""
            {"x":20}
            """);
    }

    @Test
    public void test_does_not_allow_new_columns_in_strict_object() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("""
                create table tbl (
                    o object (strict) as (
                        x int
                    )
                )
                """);
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(
                table.getReference(ColumnIdent.of("o"))
            ),
            null
        );
        assertThatThrownBy(() -> indexer.collectSchemaUpdates(item(Map.of("x", 10, "y", 20))))
            .hasMessage("Cannot add column `y` to strict object `o`");
    }

    @Test
    public void test_dynamic_int_value_results_in_long_column() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("""
                create table tbl (
                    o object (dynamic) as (
                        x int
                    )
                )
                """);
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(
                table.getReference(ColumnIdent.of("o"))
            ),
            null
        );
        List<Reference> newColumns = indexer.collectSchemaUpdates(item(Map.of("x", 10, "y", 20)));
        assertThat(newColumns).satisfiesExactly(
            r -> assertThat(r)
                .hasName("o['y']")
                .hasType(DataTypes.LONG)
        );
    }

    @Test
    public void test_can_generate_return_values() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int, y int default 20)");

        DocTableInfo table = e.resolveTableInfo("tbl");
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(e.getSessionSettings()),
            e.nodeCtx,
            List.of(
                table.getReference(ColumnIdent.of("x"))
            ),
            new Symbol[] {
                table.getReference(ColumnIdent.of("_id")),
                table.getReference(ColumnIdent.of("x")),
                table.getReference(ColumnIdent.of("y")),
            }
        );

        Object[] returnValues = indexer.returnValues(item(10));
        assertThat(returnValues).containsExactly("dummy-id-1", 10, 20);
    }

    @Test
    public void test_fields_are_omitted_in_source_for_null_values() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int, o object as (y int))");

        DocTableInfo table = e.resolveTableInfo("tbl");
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(e.getSessionSettings()),
            e.nodeCtx,
            List.of(
                table.getReference(ColumnIdent.of("x")),
                table.getReference(ColumnIdent.of("o"))
            ),
            null
        );

        HashMap<String, Object> o = new HashMap<>();
        o.put("y", null);
        ParsedDocument doc = indexer.index(item(null, o));
        assertThat(source(doc, table)).isEqualTo(
            "{\"o\":{}}"
        );
    }

    @Test
    public void test_indexing_float_results_in_float_field() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x float)");
        DocTableInfo table = e.resolveTableInfo("tbl");
        var ref = table.getReference(ColumnIdent.of("x"));

        Indexer indexer = getIndexer(e, "tbl", "x");
        ParsedDocument doc = indexer.index(item(42.2f));
        IndexableField[] fields = doc.doc().getFields(ref.storageIdent());
        assertThat(fields).satisfiesExactly(
            x -> assertThat(x)
                .isExactlyInstanceOf(FloatField.class)
                .extracting("fieldsData")
                .isEqualTo((long) NumericUtils.floatToSortableInt(42.2f))
        );
    }

    @Test
    public void test_can_index_fulltext_column() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x text index using fulltext with (analyzer = 'english'))");
        DocTableInfo table = e.resolveTableInfo("tbl");
        var ref = table.getReference(ColumnIdent.of("x"));

        var indexer = getIndexer(e, "tbl", "x");
        ParsedDocument doc = indexer.index(item("Hello World"));
        IndexableField[] fields = doc.doc().getFields(ref.storageIdent());
        assertThat(fields).hasSize(2);
        assertThat(fields).anySatisfy(
            x -> assertThat(x)
                .isExactlyInstanceOf(Field.class)
                .extracting("fieldsData")
                .as("value is indexed as string instead of BytesRef")
                .isEqualTo("Hello World")
        );
        assertThat(fields[0].fieldType().tokenized()).isTrue();
        assertTranslogParses(doc, table);

        doc = indexer.index(item(new Object[]{null}));
        fields = doc.doc().getFields(ref.storageIdent());
        assertThat(fields).hasSize(0);
        assertTranslogParses(doc, table);
    }

    @Test
    public void test_can_add_dedicated_fulltext_to_sub_column() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("""
                create table tbl (
                    id int,
                    author object as (
                        name string
                    ),
                    index nested_ft using fulltext(author['name'])
                )
            """);
        DocTableInfo table = e.resolveTableInfo("tbl");
        var ref = table.indexColumn(ColumnIdent.of("nested_ft"));

        var indexer = getIndexer(e, "tbl", "id", "author");
        ParsedDocument doc = indexer.index(item(1, Map.of("name", "sub_col_name")));
        IndexableField[] fields = doc.doc().getFields(ref.storageIdent());
        assertThat(fields).hasSize(1);
        assertTranslogParses(doc, table);
    }

    @Test
    public void test_can_index_all_storable_types() throws Exception {
        StringBuilder stmtBuilder = new StringBuilder()
            .append("create table tbl (");

        ArrayList<ColumnIdent> columns = new ArrayList<>();
        ArrayList<Object> values = new ArrayList<>();
        List<DataType<?>> types = Lists
            .concat(
                DataTypes.PRIMITIVE_TYPES,
                List.of(
                    DataTypes.GEO_POINT,
                    DataTypes.GEO_SHAPE,
                    new BitStringType(1),
                    FloatVectorType.INSTANCE_ONE
                ))
            .stream()
            .filter(t -> t.storageSupport() != null)
            .toList();
        Iterator<DataType<?>> it = types.iterator();
        boolean first = true;
        while (it.hasNext()) {
            var type = it.next();
            if (first) {
                first = false;
            } else {
                stmtBuilder.append(",\n");
            }
            Object value = DataTypeTesting.getDataGenerator(type).get();
            values.add(value);
            columns.add(ColumnIdent.of("c_" + type.getName()));
            stmtBuilder
                .append("\"c_" + type.getName() + "\" ")
                .append(type);

        }

        String stmt = stmtBuilder.append(")").toString();
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable(stmt);

        DocTableInfo table = e.resolveTableInfo("tbl");
        try (var indexEnv = new IndexEnv(
                e.nodeCtx,
                THREAD_POOL,
                table,
                clusterService.state(), Version.CURRENT)) {

            Indexer indexer = new Indexer(
                List.of(),
                table,
                Version.CURRENT,
                new CoordinatorTxnCtx(e.getSessionSettings()),
                e.nodeCtx,
                Lists.map(columns, c -> table.getReference(c)),
                null
            );
            ParsedDocument doc = indexer.index(item(values.toArray()));
            Map<String, Object> source = sourceMap(doc, table);
            it = types.iterator();
            for (int i = 0; it.hasNext(); i++) {
                var type = it.next();
                Object expected = values.get(i);
                assertThat(source).hasEntrySatisfying(
                    "c_" + type.getName(),
                    v -> assertThat(type.sanitizeValue(v)).isEqualTo(expected)
                );
            }
            assertTranslogParses(doc, table);
        }
    }

    @Test
    public void test_can_add_dynamic_ref_as_new_top_level_column() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int) with (column_policy = 'dynamic')");

        DocTableInfo table = e.resolveTableInfo("tbl");
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(e.getSessionSettings()),
            e.nodeCtx,
            List.of(
                table.getReference(ColumnIdent.of("x")),
                table.getDynamic(ColumnIdent.of("y"), true, false),
                table.getDynamic(ColumnIdent.of("z"), true, false)
            ),
            null
        );
        IndexItem item = item(42, "Hello", 21);
        List<Reference> newColumns = indexer.collectSchemaUpdates(item);
        ParsedDocument doc = indexer.index(item);
        assertThat(newColumns).satisfiesExactly(
            x -> assertThat(x)
                .hasName("y")
                .hasType(DataTypes.STRING)
                .hasPosition(-1),
            x -> assertThat(x)
                .hasName("z")
                .hasType(DataTypes.LONG)
                .hasPosition(-2)
        );
        assertThat(source(doc, table)).isEqualToIgnoringWhitespace(
            """
            {"x": 42, "y": "Hello", "z": 21}
            """
        );

        newColumns = indexer.collectSchemaUpdates(item(42, "Hello", 22));
        assertThat(newColumns)
            .as("Doesn't repeatedly add new column")
            .hasSize(0);
    }

    @Test
    public void test_cannot_add_dynamic_column_on_strict_table() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int)");
        DocTableInfo table = e.resolveTableInfo("tbl");
        assertThatThrownBy(() -> {
            new Indexer(
                List.of(),
                table,
                Version.CURRENT,
                new CoordinatorTxnCtx(e.getSessionSettings()),
                e.nodeCtx,
                List.<Reference>of(
                    new DynamicReference(
                        new ReferenceIdent(table.ident(), "y"),
                        RowGranularity.DOC,
                        -1
                    )
                ),
                null
            );
        }).isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cannot add column `y` to table `doc.tbl` with column policy `strict`");
    }

    @Test
    public void test_source_includes_null_values_in_arrays() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (xs int[])");
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "xs");
        ParsedDocument doc = indexer.index(item(Arrays.asList(1, 42, null, 21)));
        assertThat(source(doc, table)).isEqualToIgnoringWhitespace(
            """
            {"xs": [1, 42, null, 21]}
            """
        );
        assertTranslogParses(doc, table);
    }

    @Test
    public void test_array_length_is_indexed() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (xs int[] index off storage with (columnstore='false'))");
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "xs");
        ParsedDocument doc = indexer.index(item(List.of()));
        assertThat(doc.doc().getFields(toArrayLengthFieldName((Reference) e.asSymbol("xs"), table::getReference))[0].toString())
            .isEqualTo("IntField <_array_length_1:0>");
        assertTranslogParses(doc, table);
    }

    @Test
    public void test_only_top_most_array_length_is_indexed_for_multi_dimensional_arrays() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (xs int[][], xs2 int[][][])");
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "xs");
        ParsedDocument doc = indexer.index(item(List.of(List.of(1), List.of(1, 1), List.of(1, 2, 3, 4)))); // [ [1], [1,1], [1,2,3,4] ]
        IndexableField[] arrayLengthFields = doc.doc().getFields(toArrayLengthFieldName((Reference) e.asSymbol("xs"), table::getReference));

        assertThat(arrayLengthFields.length).isEqualTo(1);
        assertThat(arrayLengthFields[0].toString()).isEqualTo("IntField <_array_length_1:3>");

        indexer = getIndexer(e, "tbl", "xs2");
        doc = indexer.index(item(List.of(List.of(List.of(1, 1, 1)), List.of(List.of(2, 2, 2, 2))))); // [ [ [1,1,1] ], [ [2,2,2,2] ] ]
        arrayLengthFields = doc.doc().getFields(toArrayLengthFieldName((Reference) e.asSymbol("xs2"), table::getReference));

        assertThat(arrayLengthFields.length).isEqualTo(1);
        assertThat(arrayLengthFields[0].toString()).isEqualTo("IntField <_array_length_2:2>");
        assertTranslogParses(doc, table);
    }

    @Test
    public void test_only_top_most_array_length_is_indexed_for_multi_dimensional_object_arrays() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (xs object as (a int)[][], xs2 object as (a int)[][][])");
        DocTableInfo table = e.resolveTableInfo("tbl");
        var o = Map.of("a", 1);

        var indexer = getIndexer(e, "tbl", "xs");
        ParsedDocument doc = indexer.index(item(List.of(List.of(o), List.of(o, o), List.of(o, o, o, o)))); // [ [o], [o,o], [o,o,o,o] ]
        IndexableField[] arrayLengthFields = doc.doc().getFields(toArrayLengthFieldName((Reference) e.asSymbol("xs"), table::getReference));

        assertThat(arrayLengthFields.length).isEqualTo(1);
        assertThat(arrayLengthFields[0].toString()).isEqualTo("IntField <_array_length_1:3>");

        indexer = getIndexer(e, "tbl", "xs2");
        doc = indexer.index(item(List.of(List.of(List.of(o, o, o)), List.of(List.of(o, o, o, o))))); // [ [ [o,o,o] ], [ [o,o,o,o] ] ]
        arrayLengthFields = doc.doc().getFields(toArrayLengthFieldName((Reference) e.asSymbol("xs2"), table::getReference));

        assertThat(arrayLengthFields.length).isEqualTo(1);
        assertThat(arrayLengthFields[0].toString()).isEqualTo("IntField <_array_length_3:2>");
        assertTranslogParses(doc, table);
    }

    @Test
    public void test_array_length_is_not_indexed_for_object_arrays_within_object_arrays() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o object as (o2 object as (a int)[])[])");
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "o");
        ParsedDocument doc = indexer.index(item(List.of(Map.of("o2", List.of(Map.of("a", 1))))));

        var arrayLengthFields = doc.doc().getFields().stream().filter(f -> f.name().startsWith(ARRAY_LENGTH_FIELD_PREFIX)).toList();
        assertThat(arrayLengthFields).hasSize(1);
        assertThat(arrayLengthFields.getFirst().toString()).isEqualTo("IntField <_array_length_1:1>");

        assertTranslogParses(doc, table);
    }

    @Test
    public void test_array_length_is_not_indexed_for_child_arrays() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o_array array(object as (xs int[], o_array_2 array(object as (xs2 int[])))))");
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "o_array");
        ParsedDocument doc = indexer.index(item(List.of(Map.of("xs", List.of(), "o_array_2", List.of(Map.of("xs2", List.of()))))));
        var arrayLengthFields = doc.doc().getFields().stream().filter(field -> field.name().startsWith(ARRAY_LENGTH_FIELD_PREFIX)).toList();
        assertThat(arrayLengthFields.getFirst().toString()).isEqualTo("IntField <_array_length_1:1>"); // for the groot level array

        assertTranslogParses(doc, table);
    }

    @Test
    public void test_array_length_is_not_indexed_before_V_5_9_0() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (xs int[] index off storage with (columnstore='false'))",
                Settings.builder().put(IndexMetadata.SETTING_INDEX_VERSION_CREATED.getKey(), Version.V_5_8_0).build());
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "xs");
        ParsedDocument doc = indexer.index(item(List.of()));
        var arrayLengthField = doc.doc().getFields(toArrayLengthFieldName((Reference) e.asSymbol("xs"), table::getReference));
        assertThat(arrayLengthField).isEmpty();
    }

    @Test
    public void test_cannot_create_value_indexer_from_an_inner_array_of_multi_dimensional_array() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService).addTable("create table tbl (xs int[][])");
        DocTableInfo table = e.resolveTableInfo("tbl");
        Function<ColumnIdent, Reference> getRef = table::getReference;
        Reference ref = table.getReference("xs");
        ArrayType<?> type = (ArrayType<?>) ref.valueType();
        ArrayType<?> innerType = (ArrayType<?>) type.innerType();
        assertThatThrownBy(
            () -> innerType.storageSupport().valueIndexer(table.ident(), ref, getRef)
        ).hasMessage("Must not retrieve value indexer of the child array of a multi dimensional array");
    }

    @Test
    public void test_can_have_ft_index_for_array() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (xs text[], index ft using fulltext (xs))");
        DocTableInfo table = e.resolveTableInfo("tbl");
        var refFt = table.indexColumn(ColumnIdent.of("ft"));
        var indexer = getIndexer(e, "tbl", "xs");

        ParsedDocument doc = indexer.index(item(Arrays.asList("foo", "bar", "baz", null)));
        // NULL inside array is not indexed, hence doc has 3 fields but we have NULL it in the source.
        assertThat(doc.doc().getFields(refFt.storageIdent())).hasSize(3);
        assertThat(source(doc, table)).isEqualToIgnoringWhitespace(
            """
            {"xs": ["foo", "bar", "baz", null]}
            """
        );
        assertTranslogParses(doc, table);
    }

    @Test
    public void test_leaves_out_generated_column_if_dependency_is_null() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int, y int generated always as x + 1)");
        Indexer indexer = getIndexer(e, "tbl", "x");
        IndexItem item = item(new Object[] { null });
        List<Reference> newColumns = indexer.collectSchemaUpdates(item);
        ParsedDocument doc = indexer.index(item);
        assertThat(newColumns).isEmpty();
        assertThat(doc.source().utf8ToString()).isEqualTo("{}");
        assertTranslogParses(doc, e.resolveTableInfo("tbl"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void test_adds_non_deterministic_defaults_and_generated_columns() throws Exception {
        long now = System.currentTimeMillis();
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("""
                create table tbl (
                    o object as (
                        x int generated always as random(),
                        y int
                    ),
                    z timestamp default now()
                )
                """);
        DocTableInfo table = e.resolveTableInfo("tbl");
        Indexer indexer = getIndexer(e, "tbl", "o");
        IndexItem item = item(MapBuilder.newMapBuilder().put("y", 2).map());
        ParsedDocument doc = indexer.index(item);
        Map<String, Object> source = sourceMap(doc, table);
        assertThat(source).containsKeys("o", "z");
        assertThat((Map<String, ?>) source.get("o")).containsKeys("x", "y");

        Object[] insertValues = indexer.addGeneratedValues(item);
        assertThat(insertValues).hasSize(2);
        assertThat((Map<String, ?>) insertValues[0]).containsKeys("x", "y");
        assertThat((long) insertValues[1]).isGreaterThanOrEqualTo(now);

        assertTranslogParses(doc, table);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void test_adds_non_deterministic_sub_columns_when_root_is_not_in_targets() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("""
                create table tbl (
                    a int,
                    o object as (
                        x int as round((random() + 1) * 100)
                    )
                )
                """);

        // Object column "o" is not in the insert targets and value is not provided.
        Indexer indexer = getIndexer(e, "tbl", "a");
        IndexItem item = item(1);

        Object[] insertValues = indexer.addGeneratedValues(item);
        assertThat(insertValues).hasSize(2);
        Map<String, Object> object = (Map<String, Object>) insertValues[1];
        assertThat((int) object.get("x")).isGreaterThan(0);
    }

    @Test
    public void test_fields_order_in_source_is_deterministic() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int, o object, y int)");
        DocTableInfo table = e.resolveTableInfo("tbl");
        Indexer indexer = getIndexer(e, "tbl", "x", "o", "y");
        BytesReference source = null;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < randomIntBetween(4, 7); i++) {
            keys.add(randomAlphaOfLength(randomIntBetween(4, 20)));
        }
        List<Reference> newColumns = null;
        for (int i = 0; i < 10; i++) {
            Map<String, Integer> o = new LinkedHashMap<>();
            for (int c = 0; c < keys.size(); c++) {
                String key = keys.get(c);
                o.put(key, c);
            }
            IndexItem item = item(10, o, 50);
            List<Reference> collectedNewColumns = indexer.collectSchemaUpdates(item);

            if (collectedNewColumns.isEmpty() == false) {
                DocTableInfo actualTable = addColumns(e, table, collectedNewColumns);
                indexer.updateTargets(actualTable::getReference);
            }
            ParsedDocument doc = indexer.index(item);
            if (source == null) {
                source = doc.source();
                newColumns = collectedNewColumns;
                logger.info("Dynamic column order: {}", newColumns);
                logger.info("New keys order: {}", keys);
            } else {
                assertThat(doc.source())
                    .as("fields in " + doc.source().utf8ToString() + " must have same order in " + source.utf8ToString())
                    .isEqualTo(source);

            }
        }

        DocTableInfo newTable = addColumns(e, table, newColumns);
        Reference oRef = newTable.getReference(ColumnIdent.of("o"));
        assertThat(((ObjectType) oRef.valueType()).innerTypes().keySet()).containsExactlyElementsOf(keys);
        indexer = new Indexer(
            List.of(),
            newTable,
            Version.CURRENT,
            new CoordinatorTxnCtx(e.getSessionSettings()),
            e.nodeCtx,
            List.of("x", "o", "y").stream()
                .map(x -> newTable.getReference(ColumnIdent.of(x)))
                .toList(),
            null
        );
        Map<String, Integer> o = new LinkedHashMap<>();
        for (int c = 0; c < keys.size(); c++) {
            String key = keys.get(c);
            o.put(key, c);
        }
        IndexItem item = item(10, o, 50);
        List<Reference> collectedNewColumns = indexer.collectSchemaUpdates(item);
        assertThat(collectedNewColumns).isEmpty();
        ParsedDocument doc = indexer.index(item);
        assertThat(doc.source())
            .as(
                "Fields in new source expected to match old source\n" +
                "old=" + doc.source().utf8ToString() + "\n" +
                "new=" + source.utf8ToString() + "\n" +
                "keys=" + keys)
            .isEqualTo(source);
    }

    /**
     * Ensures that docs containing numeric values created by our indexer uses the same Lucene fields definition
     * than the FieldMapper used when inserting documents read from the translog.
     */
    @Test
    public void test_indexing_number_results_in_same_fields_as_document_mapper_if_not_indexed() throws Exception {
        var idx = 0;
        for (var dt : DataTypes.NUMERIC_PRIMITIVE_TYPES) {
            var tableName = "tbl_" + idx++;
            SQLExecutor e = SQLExecutor.of(clusterService)
                    .addTable("create table " + tableName + " (x " + dt.getName() + " INDEX OFF)");

            Indexer indexer = getIndexer(e, tableName, "x");
            ParsedDocument doc = indexer.index(item(1));
            DocTableInfo tableInfo = e.resolveTableInfo(tableName);
            assertTranslogParses(doc, tableInfo);
        }
    }

    @Test
    public void test_indexing_ip_results_in_same_fields_as_document_mapper_if_not_indexed() throws Exception {
        var tableName = "tbl";
        var dt = IpType.INSTANCE;
        SQLExecutor e = SQLExecutor.of(clusterService)
                .addTable("create table " + tableName + " (x " + dt.getName() + " INDEX OFF)");

        Indexer indexer = getIndexer(e, tableName, "x");
        ParsedDocument doc = indexer.index(item("127.0.0.1"));
        assertTranslogParses(doc, e.resolveTableInfo(tableName));
    }

    @Test
    public void test_indexing_bitstring_results_in_same_fields_as_document_mapper_if_not_indexed() throws Exception {
        var tableName = "tbl";
        var dt = BitStringType.INSTANCE_ONE;
        SQLExecutor e = SQLExecutor.of(clusterService)
                .addTable("create table " + tableName + " (x " + dt.getName() + "(1) INDEX OFF)");

        Indexer indexer = getIndexer(e, tableName, "x");

        ParsedDocument doc = indexer.index(item(BitString.ofRawBits("1")));
        assertTranslogParses(doc, e.resolveTableInfo(tableName));
    }

    @Test
    public void test_indexing_boolean_results_in_same_fields_as_document_mapper_if_not_indexed() throws Exception {
        var tableName = "tbl";
        var dt = BooleanType.INSTANCE;
        SQLExecutor e = SQLExecutor.of(clusterService)
                .addTable("create table " + tableName + " (x " + dt.getName() + " INDEX OFF)");

        Indexer indexer = getIndexer(e, tableName, "x");

        ParsedDocument doc = indexer.index(item(true));
        assertTranslogParses(doc, e.resolveTableInfo(tableName));
    }

    @Test
    public void test_index_nested_array() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (x int) with (column_policy = 'dynamic')");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference x = table.getReference(ColumnIdent.of("x"));
        Reference y = new DynamicReference(new ReferenceIdent(table.ident(), "y"), RowGranularity.DOC, 2);
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(x, y),
            null
        );
        IndexItem item = item(10, List.of(List.of(1, 2), List.of(3, 4)));
        List<Reference> newColumns = indexer.collectSchemaUpdates(item);
        ParsedDocument doc = indexer.index(item);
        assertThat(newColumns).satisfiesExactly(
            column -> assertThat(column)
                .hasName("y")
                .hasType(new ArrayType<>(new ArrayType<>(DataTypes.LONG)))
        );
        assertThat(source(doc, table)).isEqualTo("""
            {"x":10,"y":[[1,2],[3,4]]}"""
        );
        // NB: We can't test translog assertions, as the test does not update DocTableInfo
        // with the new dynamic reference.
    }

    @Test
    public void test_generated_column_can_refer_to_a_non_string_partitioned_by_column() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("""
             CREATE TABLE t (
                 a INT,
                 parted INT CHECK (parted > 1),
                 gen_from_parted INT as parted + 1
             ) PARTITIONED BY (parted)
             """
            );
        DocTableInfo table = executor.resolveTableInfo("t");
        Indexer indexer = new Indexer(
            List.of("2"),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(
                table.getReference(ColumnIdent.of("a"))
                // 'Parted' is not in targets to imitate insert-from-subquery behavior
                //  which excludes partitioned columns from targets
            ),
            null
        );

        // Imitating problematic query
        // insert into t (a, parted) select 1, 2
        // We are inserting into partition 2, so b = 2.
        ParsedDocument parsedDoc = indexer.index(item(1));
        assertThat(source(parsedDoc, table)).isEqualToIgnoringWhitespace(
            """
            {"a":1, "gen_from_parted": 3}
            """
        );

        assertTranslogParses(parsedDoc, table);
    }

    @Test
    public void test_check_constraint_on_object_sub_column_is_verified() throws Exception {
        SQLExecutor executor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (obj object as (x int check (obj['x'] > 10)))");
        DocTableInfo table = executor.resolveTableInfo("tbl");
        Reference x = table.getReference(ColumnIdent.of("obj"));
        Indexer indexer = new Indexer(
            List.of(),
            table,
            Version.CURRENT,
            new CoordinatorTxnCtx(executor.getSessionSettings()),
            executor.nodeCtx,
            List.of(x),
            null
        );
        assertThatThrownBy(() -> indexer.index(item(Map.of("x", 5))))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll("Failed CONSTRAINT", "CHECK (\"obj\"['x'] > 10) for values: [{x=5}]");
    }

    @Test
    public void test_empty_arrays_together_with_another_field_added_as_new_cols_with_dynamic_policy() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (i int) with (column_policy='dynamic')");
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "i", "empty_arr", "a");
        indexer.updateTargets(table::getReference);
        IndexItem item = item(1, List.of(), "foo");
        List<Reference> newColumns = indexer.collectSchemaUpdates(item);
        assertThat(newColumns).satisfiesExactly(
            ref1 -> assertThat(ref1.column()).isEqualTo(ColumnIdent.of("empty_arr")),
            ref2 -> assertThat(ref2.column()).isEqualTo(ColumnIdent.of("a"))
        );
        ParsedDocument doc = indexer.index(item);
        // `_u_a` is not a valid real-world scenario, as column `a` would have been
        // added implicitly with an `AddColumnRequest` in `TransportShardUpsertAction`,
        // and therefore would be assigned an oid.
        assertThat(doc.source().utf8ToString()).isEqualToIgnoringWhitespace(
            """
            {"1":1,"_u_empty_arr":[],"_u_a":"foo"}
            """
        );
        // prefix is stripped on non _raw lookups
        assertThat(source(doc, table)).isEqualToIgnoringWhitespace(
            """
            {"a":"foo","i":1,"empty_arr":[]}
            """
        );
    }

    @Test
    public void test_empty_arrays_are_not_prefixed_as_unknown_on_tables_created_less_5_5() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
                .addTable(
                        "create table tbl (i int) with (column_policy='dynamic')",
                        Settings.builder().put(IndexMetadata.SETTING_INDEX_VERSION_CREATED.getKey(), Version.V_5_4_0).build()
                );

        var indexer = getIndexer(e, "tbl", "empty_arr");
        ParsedDocument doc = indexer.index(item(List.of()));
        assertThat(doc.source().utf8ToString()).isEqualToIgnoringWhitespace(
                """
                {"empty_arr":[]}
                """
        );

        assertTranslogParses(doc, e.resolveTableInfo("tbl"), Version.V_5_4_0);
    }

    @UseNewCluster
    @Test
    public void test_ignored_object_child_columns_are_prefixed() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
                .addTable("create table tbl (o object (ignored) as (i int))");
        DocTableInfo table = e.resolveTableInfo("tbl");

        var indexer = getIndexer(e, "tbl", "o");
        ParsedDocument doc = indexer.index(item(Map.of("i", 1, "ignored_col", "foo")));
        assertThat(doc.source().utf8ToString()).isEqualToIgnoringWhitespace(
                """
                {"1":{"2":1,"_u_ignored_col":"foo"}}
                """
        );
        // prefix is stripped and OID's replaced on non _raw lookups
        assertThat(source(doc, table)).isIn(
                "{\"o\":{\"i\":1,\"ignored_col\":\"foo\"}}",
                "{\"o\":{\"ignored_col\":\"foo\",\"i\":1}}"
        );

        assertTranslogParses(doc, table);
    }

    @Test
    public void test_ignored_object_child_columns_are_not_prefixed_on_tables_created_less_5_5() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService)
            // old tables created with CrateDB < 5.5.0 do not assign any OID, fake it here
            .setColumnOidSupplier(() -> COLUMN_OID_UNASSIGNED)
            .addTable("create table tbl (o object (ignored) as (i int))",
                Settings.builder().put(IndexMetadata.SETTING_INDEX_VERSION_CREATED.getKey(), Version.V_5_4_0).build()
            );

        var indexer = getIndexer(e, "tbl", "o");
        ParsedDocument doc = indexer.index(item(Map.of("i", 1, "ignored_col", "foo")));
        assertThat(doc.source().utf8ToString()).isEqualToIgnoringWhitespace(
                """
                {"o":{"i":1,"ignored_col":"foo"}}
                """
        );

        assertTranslogParses(doc, e.resolveTableInfo("tbl"), Version.V_5_4_0);
    }

    /**
     * {@link TranslogIndexer#index(String, BytesReference)} is used to parse translog entries,
     * ensure it can parse a document containing OIDs instead of column names.
     */
    @UseNewCluster
    @Test
    public void test_translog_indexer_can_read_source_with_oids() throws Exception {
        var tableName = "tbl";
        SQLExecutor e = SQLExecutor.of(clusterService)
            .addTable("create table tbl (i int, o object as (x int))");

        Indexer indexer = getIndexer(e, tableName, "i", "o");

        ParsedDocument doc = indexer.index(item(1, Map.of("x", 2)));
        // Ensure source contains OID's instead of column names
        assertThat(doc.source().utf8ToString()).isEqualToIgnoringWhitespace(
            """
            {"1":1,"2":{"3":2}}
            """
        );

        assertTranslogParses(doc, e.resolveTableInfo(tableName));
    }

    @Test
    public void test_indexing_geo_shape_results_in_same_fields_as_document_mapper() throws Exception {
        var sqlExecutor = SQLExecutor.of(clusterService);
        int idx = 0;
        for (var indexType : List.of(TREE_GEOHASH, TREE_QUADTREE, TREE_LEGACY_QUADTREE, TREE_BKD)) {
            String tableName = "tbl_" + idx++;
            sqlExecutor.addTable("create table " + tableName + " (x geo_shape index using " + indexType + ")");

            Supplier<Map<String, Object>> dataGenerator = DataTypeTesting.getDataGenerator(GeoShapeType.INSTANCE);
            DocTableInfo table = sqlExecutor.resolveTableInfo(tableName);

            Indexer indexer = getIndexer(sqlExecutor, tableName, "x");
            Map<String, Object> value = dataGenerator.get();
            ParsedDocument doc = indexer.index(item(value));

            assertTranslogParses(doc, table);
        }
    }

    @Test
    public void test_can_index_generated_geo_shape() throws Exception {
        var sqlExecutor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o object(ignored), geo generated always as o::geo_shape)");

        DocTableInfo table = sqlExecutor.resolveTableInfo("tbl");
        Indexer indexer = getIndexer(sqlExecutor, "tbl", "o");

        Map<String, Object> obj = new HashMap<>();
        obj.put("coordinates", List.of(50, 50));
        obj.put("type", "Point");
        ParsedDocument doc = indexer.index(item(obj));

        assertThat(doc.source().utf8ToString())
            .isEqualToIgnoringWhitespace("""
                {"1":{"_u_coordinates":[50,50],"_u_type":"Point"},"2":{"coordinates":[50,50],"type":"Point"}}
                """);
        assertTranslogParses(doc, table);
    }

    @Test
    public void test_handles_type_conflicts_in_dynamic_nested_objects() throws Exception {
        var sqlExecutor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (o object(dynamic))");
        DocTableInfo table = sqlExecutor.resolveTableInfo("tbl");
        Indexer indexer = getIndexer(sqlExecutor, "tbl", "o");

        Map<String, List<Map<String, Integer>>> value1 = Map.of("name", List.of(Map.of("a", 1)));
        Map<String, Map<String, Integer>> value2 = Map.of("name", Map.of("a", 1));

        List<Reference> newColumns = indexer.collectSchemaUpdates(item(value1));

        assertThat(newColumns).satisfiesExactly(
            x -> assertThat(x).hasName("o['name']"),
            x -> assertThat(x).hasName("o['name']['a']")
        );


        newColumns = indexer.collectSchemaUpdates(item(value2));
        assertThat(newColumns).as("collectSchemaUpdates must not apply the updates immediately").satisfiesExactly(
            x -> assertThat(x).hasName("o['name']"),
            x -> assertThat(x).hasName("o['name']['a']")
        );

        DocTableInfo newTable = addColumns(sqlExecutor, table, newColumns);

        indexer.updateTargets(newTable::getReference);
        newColumns = indexer.collectSchemaUpdates(item(value2));
        assertThat(newColumns).isEmpty();

        assertThatThrownBy(() -> indexer.index(item(value1)))
            .isExactlyInstanceOf(ClassCastException.class);
    }


    @Test
    public void test_exceeding_value_limits_raises_errors() throws Exception {
        // long max values are used as sentinel values during ORDER BY
        // -> byte, short and int max values are allowed
        // -> long is restricted to min-value + 1 to max-value - 1

        var sqlExecutor = SQLExecutor.of(clusterService)
            .addTable("create table tbl (b byte, s smallint, i int, l long)");
        Indexer indexer = getIndexer(sqlExecutor, "tbl", "b", "s", "i", "l");

        indexer.index(item(Byte.MAX_VALUE, 0, 0, 0));
        indexer.index(item(0, Short.MAX_VALUE, 0, 0));
        indexer.index(item(0, 0, Integer.MAX_VALUE, 0));

        assertThatThrownBy(() -> indexer.index(item(0, 0, 0, Long.MAX_VALUE)))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("Value 9223372036854775807 exceeds allowed range for column of type bigint");
    }

    @Test
    public void test_array_of_object_within_array_of_object_does_not_store_values() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService).addTable("create table tbl (o object as (o2 object as (a int)[])[])");
        var indexer = getIndexer(e, "tbl", "o");
        ParsedDocument doc = indexer.index(item(List.of(Map.of("o2", List.of(Map.of("a", 1))))));
        var storedFields = doc.doc().getFields().stream().filter(f -> f.name().startsWith(ARRAY_VALUES_FIELD_PREFIX)).toList();
        assertThat(storedFields).hasSize(1);
        StoredField storedField = (StoredField) storedFields.getFirst();
        assertThat(storedField.name()).isEqualTo("_array_values_1");
        assertThat(storedField.binaryValue().utf8ToString()).isEqualTo("{\"1\":[{\"2\":[{\"3\":1}]}]}");
    }

    public static void assertTranslogParses(ParsedDocument doc, DocTableInfo info) {
        assertTranslogParses(doc, info, Version.CURRENT);
    }

    public static void assertTranslogParses(ParsedDocument doc, DocTableInfo info, Version shardCreatedVersion) {
        TranslogIndexer ti = new TranslogIndexer(info, shardCreatedVersion);
        ParsedDocument d = ti.index(doc.id(), doc.source());
        assertThat(doc).parsesTo(d);
    }
}
