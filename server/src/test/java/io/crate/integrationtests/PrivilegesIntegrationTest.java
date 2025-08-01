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

package io.crate.integrationtests;

import static io.crate.protocols.postgres.PGErrorStatus.INTERNAL_ERROR;
import static io.crate.protocols.postgres.PGErrorStatus.UNDEFINED_OBJECT;
import static io.crate.protocols.postgres.PGErrorStatus.UNDEFINED_TABLE;
import static io.crate.testing.Asserts.assertThat;
import static io.crate.testing.TestingHelpers.printedTable;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.crate.auth.Protocol;
import io.crate.expression.udf.UserDefinedFunctionService;
import io.crate.metadata.RelationName;
import io.crate.metadata.pgcatalog.OidHash;
import io.crate.metadata.pgcatalog.OidHash.Type;
import io.crate.protocols.postgres.ConnectionProperties;
import io.crate.role.Role;
import io.crate.role.Roles;
import io.crate.session.Session;
import io.crate.session.Sessions;
import io.crate.testing.Asserts;
import io.crate.testing.SQLResponse;
import io.crate.testing.UseRandomizedSchema;

public class PrivilegesIntegrationTest extends BaseRolesIntegrationTest {

    private static final String TEST_USERNAME = "privileges_test_user";

    private final UserDefinedFunctionsIntegrationTest.DummyLang dummyLang = new UserDefinedFunctionsIntegrationTest.DummyLang();
    private Sessions sqlOperations;
    private Roles roles;

    private void assertPrivilegeIsGranted(String privilege) {
        SQLResponse response = executeAsSuperuser("select count(*) from sys.privileges where grantee = ? and type = ?",
            new Object[]{TEST_USERNAME, privilege});
        assertThat(response).hasRows("1");
    }

    private void assertPrivilegeIsRevoked(String privilege) {
        SQLResponse response = executeAsSuperuser("select count(*) from sys.privileges where grantee = ? and type = ?",
            new Object[]{TEST_USERNAME, privilege});
        assertThat(response).hasRows("0");
    }

    private Session testUserSession() {
        return testUserSession(null);
    }

    private Session testUserSession(String defaultSchema) {
        Role user = roles.findUser(TEST_USERNAME);
        assertThat(user).isNotNull();
        return sqlOperations.newSession(
            new ConnectionProperties(null, null, Protocol.HTTP, null), defaultSchema, user);
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        Iterable<UserDefinedFunctionService> udfServices = cluster().getInstances(UserDefinedFunctionService.class);
        for (UserDefinedFunctionService udfService : udfServices) {
            udfService.registerLanguage(dummyLang);
        }
        roles = cluster().getInstance(Roles.class);
        sqlOperations = cluster().getInstance(Sessions.class, null);
        executeAsSuperuser("create user " + TEST_USERNAME);
    }

    @After
    public void dropDbObjects() {
        executeAsSuperuser("drop view if exists v1, my_schema.v2, other_schema.v3");
    }

    @Test
    public void testNormalUserGrantsPrivilegeThrowsException() {
        Asserts.assertSQLError(() -> executeAs("grant DQL to " + TEST_USERNAME, NORMAL_USER))
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(UNAUTHORIZED, 4011)
            .hasMessageContaining("Missing 'AL' privilege for user 'normal'");
    }

    @Test
    public void testNewUserHasNoPrivilegesByDefault() {
        executeAsSuperuser("select * from sys.privileges where grantee = ?", new Object[]{TEST_USERNAME});
        assertThat(response).hasRowCount(0);
    }

    @Test
    public void testSuperUserGrantsPrivilege() {
        executeAsSuperuser("grant DQL to " + TEST_USERNAME);
        assertPrivilegeIsGranted("DQL");
    }

    @Test
    public void testGrantRevokeALLPrivileges() {
        executeAsSuperuser("grant ALL to " + TEST_USERNAME);
        assertPrivilegeIsGranted("DQL");
        assertPrivilegeIsGranted("DML");
        assertPrivilegeIsGranted("DDL");

        executeAsSuperuser("revoke ALL from " + TEST_USERNAME);
        assertPrivilegeIsRevoked("DQL");
        assertPrivilegeIsRevoked("DML");
        assertPrivilegeIsRevoked("DDL");
    }

    @Test
    public void testSuperUserRevokesPrivilege() {
        executeAsSuperuser("grant DQL to " + TEST_USERNAME);
        assertThat(response).hasRowCount(1L);

        executeAsSuperuser("revoke DQL from " + TEST_USERNAME);
        assertThat(response).hasRowCount(1L);

        assertPrivilegeIsRevoked("DQL");
    }

    @Test
    public void testGrantPrivilegeToSuperuserThrowsException() {
        String superuserName = Role.CRATE_USER.name();
        Asserts.assertSQLError(() -> executeAsSuperuser("grant DQL to " + superuserName))
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(BAD_REQUEST, 4004)
            .hasMessageContaining("Cannot alter privileges for superuser '" + superuserName + "'");
    }

    @Test
    public void testApplyPrivilegesToUnknownUserThrowsException() {
        Asserts.assertSQLError(() -> executeAsSuperuser("grant DQL to unknown_user"))
            .hasPGError(UNDEFINED_OBJECT)
            .hasHTTPError(NOT_FOUND, 40410)
            .hasMessageContaining("Role 'unknown_user' does not exist");
    }

    @Test
    public void testApplyPrivilegesToMultipleUnknownUsersThrowsException() {
        Asserts.assertSQLError(() -> executeAsSuperuser("grant DQL to unknown_user, also_unknown"))
            .hasPGError(UNDEFINED_OBJECT)
            .hasHTTPError(NOT_FOUND, 40410)
            .hasMessageContaining("Roles 'unknown_user, also_unknown' do not exist");
    }

    @Test
    @UseRandomizedSchema(random = false)
    public void testQuerySysShardsReturnsOnlyRowsRegardingTablesUserHasAccessOn() {
        executeAsSuperuser("create table t1 (x int) partitioned by (x) clustered into 1 shards with (number_of_replicas = 0)");
        executeAsSuperuser("insert into t1 values (1)");
        executeAsSuperuser("insert into t1 values (2)");
        executeAsSuperuser("insert into t1 values (3)");
        executeAsSuperuser("create table doc.t2 (x int) clustered into 1 shards with (number_of_replicas = 0)");
        executeAsSuperuser("create table t3 (x int) clustered into 1 shards with (number_of_replicas = 0)");

        executeAsSuperuser("grant dql on table t1 to " + TEST_USERNAME);
        executeAsSuperuser("grant dml on table t2 to " + TEST_USERNAME);
        executeAsSuperuser("grant dql on table sys.shards to " + TEST_USERNAME);

        try (Session testUserSession = testUserSession()) {
            execute("select table_name from sys.shards order by table_name", null, testUserSession);
        }
        assertThat(response).hasRowCount(4L);
        assertThat(response.rows()[0][0]).isEqualTo("t1");
        assertThat(response.rows()[1][0]).isEqualTo("t1");
        assertThat(response.rows()[2][0]).isEqualTo("t1");
        assertThat(response.rows()[3][0]).isEqualTo("t2");
    }

    @Test
    public void testQuerySysJobsLogReturnsLogEntriesRelevantToUser() {
        executeAsSuperuser("grant dql on table sys.jobs_log to " + TEST_USERNAME);
        executeAsSuperuser("select 2");
        executeAsSuperuser("select 3");
        try (Session testUserSession = testUserSession()) {
            execute("select 1", null, testUserSession);
        }

        String stmt = "select username, stmt " +
                      "from sys.jobs_log " +
                      "where stmt in ('select 1', 'select 2', 'select 3') " +
                      "order by stmt";

        executeAsSuperuser(stmt);
        assertThat(response).hasRows(
                   "privileges_test_user| select 1",
                   "crate| select 2",
                   "crate| select 3");

        try (Session testUserSession = testUserSession()) {
            execute(stmt, null, testUserSession);
        }
        assertThat(response).hasRows("privileges_test_user| select 1");

        executeAsSuperuser("grant AL to " + TEST_USERNAME);
        try (Session testUserSession = testUserSession()) {
            execute(stmt, null, testUserSession);
        }
        assertThat(response).hasRows(
            "privileges_test_user| select 1",
            "crate| select 2",
            "crate| select 3"
        );
    }

    @Test
    public void testQuerySysAllocationsReturnsOnlyRowsRegardingTablesUserHasAccessOn() {
        executeAsSuperuser("CREATE TABLE su.t1 (i INTEGER) CLUSTERED INTO 1 SHARDS WITH (number_of_replicas = 0)");
        executeAsSuperuser("CREATE TABLE u.t1 (i INTEGER) CLUSTERED INTO 1 SHARDS WITH (number_of_replicas = 0)");

        executeAsSuperuser("GRANT DQL ON SCHEMA u TO " + TEST_USERNAME);
        executeAsSuperuser("GRANT DQL ON TABLE sys.allocations TO " + TEST_USERNAME);

        try (Session testUserSession = testUserSession()) {
            execute("SELECT table_schema, table_name, shard_id FROM sys.allocations", null, testUserSession);
        }
        assertThat(response).hasRowCount(1L);
        assertThat(response.rows()[0][0]).isEqualTo("u");
        assertThat(response.rows()[0][1]).isEqualTo("t1");
        assertThat(response.rows()[0][2]).isEqualTo(0);
    }

    @Test
    public void testQuerySysHealthReturnsOnlyRowsRegardingTablesUserHasAccessOn() {
        executeAsSuperuser("CREATE TABLE su.t1 (i INTEGER) CLUSTERED INTO 1 SHARDS WITH (number_of_replicas = 0)");
        executeAsSuperuser("CREATE TABLE u.t1 (i INTEGER) CLUSTERED INTO 1 SHARDS WITH (number_of_replicas = 0)");

        executeAsSuperuser("GRANT DQL ON SCHEMA u TO " + TEST_USERNAME);
        executeAsSuperuser("GRANT DQL ON TABLE sys.health TO " + TEST_USERNAME);

        try (Session testUserSession = testUserSession()) {
            execute("SELECT table_schema, table_name, health FROM sys.health", null, testUserSession);
        }
        assertThat(response).hasRows("u| t1| GREEN");
    }

    @Test
    public void testQueryInformationSchemaShowsOnlyRowsRegardingTablesUserHasAccessOn() {
        executeAsSuperuser("create table t1 (x int) partitioned by (x) clustered into 1 shards with (number_of_replicas = 0)");
        executeAsSuperuser("insert into t1 values (1)");
        executeAsSuperuser("insert into t1 values (2)");
        executeAsSuperuser("create table my_schema.t2 (x int primary key) clustered into 1 shards with (number_of_replicas = 0)");
        executeAsSuperuser("create table other_schema.t3 (x int) clustered into 1 shards with (number_of_replicas = 0)");

        executeAsSuperuser("create view v1 as Select * from my_schema.t2");
        executeAsSuperuser("create view my_schema.v2 as Select * from other_schema.t3");
        executeAsSuperuser("create view other_schema.v3 as Select * from t1");

        executeAsSuperuser("create function my_schema.foo(long)" +
                " returns string language dummy_lang as 'function foo(x) { return \"1\"; }'");
        executeAsSuperuser("create function other_func(long)" +
                " returns string language dummy_lang as 'function foo(x) { return \"1\"; }'");

        executeAsSuperuser("grant dql on table t1 to " + TEST_USERNAME);
        executeAsSuperuser("grant dml on table my_schema.t2 to " + TEST_USERNAME);
        executeAsSuperuser("grant dql on schema my_schema to " + TEST_USERNAME);
        executeAsSuperuser("grant dql on view v1 to " + TEST_USERNAME);

        try (Session testUserSession = testUserSession()) {
            execute("select schema_name from information_schema.schemata order by schema_name", null, testUserSession);
            assertThat(response).hasRows("my_schema");
            execute("select table_name from information_schema.tables order by table_name", null, testUserSession);
            assertThat(response).hasRows("t1",
                                         "t2",
                                         "v1",
                                         "v2");
            execute("select table_name from information_schema.table_partitions order by table_name",
                    null,
                    testUserSession);
            assertThat(response).hasRows("t1",
                                         "t1");
            execute("select table_name from information_schema.columns order by table_name", null, testUserSession);
            assertThat(response).hasRows("t1",
                                         "t2",
                                         "v1",
                                         "v2");
            execute(
                "select table_name, constraint_name from information_schema.table_constraints order by table_name, constraint_name",
                null,
                testUserSession);
            assertThat(response).hasRows(
                "t2| my_schema_t2_x_not_null",
                "t2| t2_pkey");
            execute("select routine_schema from information_schema.routines order by routine_schema",
                    null,
                    testUserSession);
            assertThat(response).hasRows("my_schema");

            execute("select table_name from information_schema.views order by table_name", null, testUserSession);
            assertThat(response).hasRows("v1",
                                         "v2");

            executeAsSuperuser("drop function other_func(long)");
            executeAsSuperuser("drop function my_schema.foo(long)");
        }
    }

    @Test
    @UseRandomizedSchema(random = false)
    public void testRenameTableTransfersPrivilegesToNewTable() {
        executeAsSuperuser("create table doc.t1 (x int) clustered into 1 shards with (number_of_replicas = 0)");
        executeAsSuperuser("grant dql on table t1 to " + TEST_USERNAME);

        executeAsSuperuser("alter table doc.t1 rename to t1_renamed");

        try (Session testUserSession = testUserSession()) {
            execute("select * from t1_renamed", null, testUserSession);
        }
        assertThat(response).hasRowCount(0L);
    }

    @Test
    public void testPrivilegeIsSwappedWithSwapTable() {
        executeAsSuperuser("create table doc.t1 (x int)");
        executeAsSuperuser("create table doc.t2 (x int)");
        executeAsSuperuser("grant dql on table doc.t1 to " + TEST_USERNAME);

        executeAsSuperuser("alter cluster swap table doc.t1 to doc.t2 with (drop_source = true)");
        try (Session testUserSession = testUserSession()) {
            execute("select * from doc.t2", null, testUserSession);
        }
        assertThat(response).hasRowCount(0L);
    }

    @Test
    @UseRandomizedSchema(random = false)
    public void testRenamePartitionedTableTransfersPrivilegesToNewTable() {
        executeAsSuperuser("create table t1 (x int) partitioned by (x) clustered into 1 shards with (number_of_replicas = 0)");
        executeAsSuperuser("insert into t1 values (1)");
        executeAsSuperuser("grant dql on table t1 to " + TEST_USERNAME);

        executeAsSuperuser("alter table doc.t1 rename to t1_renamed");

        try (Session testUserSession = testUserSession()) {
            execute("select * from t1_renamed", null, testUserSession);
        }
        assertThat(response).hasRowCount(1L);
    }

    @Test
    @UseRandomizedSchema(random = false)
    public void testDropTableRemovesPrivileges() {
        executeAsSuperuser("create table doc.t1 (x int) clustered into 1 shards with (number_of_replicas = 0)");
        executeAsSuperuser("grant dql on table t1 to " + TEST_USERNAME);

        executeAsSuperuser("drop table t1");

        executeAsSuperuser("create table doc.t1 (x int) clustered into 1 shards with (number_of_replicas = 0)");

        Asserts.assertSQLError(() -> {
            try (Session testUserSession = testUserSession()) {
                execute("select * from t1", null, testUserSession);
            }
        })
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(NOT_FOUND, 4045)
            .hasMessageContaining("Schema 'doc' unknown");
    }

    @Test
    @UseRandomizedSchema(random = false)
    public void testDropViewRemovesPrivileges() {
        executeAsSuperuser("create view doc.v1 as select 1");
        executeAsSuperuser("grant dql on view v1 to " + TEST_USERNAME);

        executeAsSuperuser("drop view v1");

        executeAsSuperuser("create view doc.v1 as select 1");
        Asserts.assertSQLError(() -> {
            try (Session testUserSession = testUserSession()) {
                execute("select * from v1", null, testUserSession);
            }
        })
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(NOT_FOUND, 4045)
            .hasMessageContaining("Schema 'doc' unknown");
    }

    @Test
    @UseRandomizedSchema(random = false)
    public void testDropEmptyPartitionedTableRemovesPrivileges() {
        executeAsSuperuser(
            "create table doc.t1 (x int) partitioned by (x) clustered into 1 shards with (number_of_replicas = 0)");
        executeAsSuperuser("grant dql on table t1 to " + TEST_USERNAME);

        executeAsSuperuser("drop table t1");

        executeAsSuperuser("select * from sys.privileges where grantee = ? and ident = ?",
                           new Object[] {TEST_USERNAME, "doc.t1"});
        assertThat(response).hasRowCount(0L);

        executeAsSuperuser("create table doc.t1 (x int) clustered into 1 shards with (number_of_replicas = 0)");
        Asserts.assertSQLError(() -> {
            try (Session testUserSession = testUserSession()) {
                execute("select * from t1", null, testUserSession);
            }
        })
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(NOT_FOUND, 4045)
            .hasMessageContaining("Schema 'doc' unknown");
    }

    @Test
    public void testGrantWithCustomDefaultSchema() {
        executeAsSuperuser("create table doc.t1 (x int)");
        executeAsSuperuser("set search_path to 'custom_schema'");
        executeAsSuperuser("create table t2 (x int)");

        executeAsSuperuser("grant dql on table t2 to " + TEST_USERNAME);
        assertThat(response).hasRowCount(1L);

        Asserts.assertSQLError(() -> executeAsSuperuser("grant dql on table t1 to " + TEST_USERNAME))
            .hasPGError(UNDEFINED_TABLE)
            .hasHTTPError(NOT_FOUND, 4041)
            .hasMessageContaining("Relation 't1' unknown");
    }

    @Test
    public void testAlterClusterRerouteRetryFailedPrivileges() {
        executeAsSuperuser("alter cluster reroute retry failed");
        assertThat(response).hasRowCount(0L);

        Asserts.assertSQLError(() -> executeAs("alter cluster reroute retry failed", NORMAL_USER))
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(UNAUTHORIZED, 4011)
            .hasMessageContaining("Missing 'AL' privilege for user 'normal'");
    }

    @Test
    public void testOperationOnClosedTableAsAuthorizedUser() {
        executeAsSuperuser("create table s.t1 (x int)");
        executeAsSuperuser("alter table s.t1 close");

        executeAsSuperuser("grant dql on schema s to " + TEST_USERNAME);
        assertThat(response).hasRowCount(1L);

        Asserts.assertSQLError(() -> {
            try (Session testUserSession = testUserSession()) {
                execute("refresh table s.t1", null, testUserSession);
            }
        })
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(BAD_REQUEST, 4007)
            .hasMessageContaining("The relation \"s.t1\" doesn't support or allow REFRESH " +
                    "operations, as it is currently closed.");
    }

    @Test
    public void testPermissionsValidOnTableAlias() {
        executeAsSuperuser("create table test.test (x int)");

        executeAsSuperuser("grant dql on schema test to " + TEST_USERNAME);
        executeAsSuperuser("deny dql on schema doc to " + TEST_USERNAME);
        assertThat(response).hasRowCount(1L);

        try (Session testUserSession = testUserSession("s")) {
            execute("select t.x from test.test as t", null, testUserSession);
        }
        assertThat(response).hasRowCount(0L);
    }

    @Test
    public void testPermissionsValidOnSubselectAlias() {
        executeAsSuperuser("create table s.t1 (x int)");

        executeAsSuperuser("grant dql on schema s to " + TEST_USERNAME);
        executeAsSuperuser("deny dql on schema doc to " + TEST_USERNAME);
        assertThat(response).hasRowCount(1L);

        try (Session testUserSession = testUserSession("s")) {
            execute("select t.x from (select x from t1) as t", null, testUserSession);
        }
        assertThat(response).hasRowCount(0L);
    }

    @Test
    public void testAccessesToPgClassEntriesWithRespectToPrivileges() throws Exception {
        //make sure a new user has default accesses to pg tables with information and pg catalog schema related entries
        try (Session testUserSession = testUserSession()) {
            execute("select relname from pg_catalog.pg_class order by relname", null, testUserSession);
        }
        assertThat(response).hasRows(
            "administrable_role_authorizations",
            "applicable_roles",
            "character_sets",
            "columns",
            "columns_pkey",
            "enabled_roles",
            "foreign_server_options",
            "foreign_servers",
            "foreign_table_options",
            "foreign_tables",
            "key_column_usage",
            "key_column_usage_pkey",
            "pg_am",
            "pg_attrdef",
            "pg_attribute",
            "pg_auth_members",
            "pg_class",
            "pg_constraint",
            "pg_cursors",
            "pg_database",
            "pg_depend",
            "pg_description",
            "pg_enum",
            "pg_event_trigger",
            "pg_index",
            "pg_indexes",
            "pg_locks",
            "pg_matviews",
            "pg_namespace",
            "pg_proc",
            "pg_publication",
            "pg_publication_tables",
            "pg_range",
            "pg_roles",
            "pg_settings",
            "pg_shdescription",
            "pg_stats",
            "pg_subscription",
            "pg_subscription_rel",
            "pg_tables",
            "pg_tablespace",
            "pg_type",
            "pg_views",
            "referential_constraints",
            "referential_constraints_pkey",
            "role_table_grants",
            "routines",
            "schemata",
            "schemata_pkey",
            "sql_features",
            "sql_features_pkey",
            "table_constraints",
            "table_constraints_pkey",
            "table_partitions",
            "table_partitions_pkey",
            "tables",
            "tables_pkey",
            "user_mapping_options",
            "user_mappings",
            "views",
            "views_pkey"
        );

        //create table that a new user is not privileged to access
        executeAsSuperuser("create table test_schema.my_table (my_col int)");
        executeAsSuperuser("insert into test_schema.my_table values (1),(2)");

        //make sure a new user cannot access my_table without privilege
        try (Session testUserSession = testUserSession()) {
            execute("select * from pg_catalog.pg_class where relname = 'my_table' order by relname", null, testUserSession);
            assertThat(response).hasRowCount(0L);

            //if privilege is granted, the new user can access
            executeAsSuperuser("grant DQL on table test_schema.my_table to " + TEST_USERNAME);
            execute("select * from pg_catalog.pg_class where relname = 'my_table' order by relname", null, testUserSession);
            assertThat(response).hasRowCount(1L);
        }

        //values are identical
        String newUserWithPrivilegesResult = printedTable(response.rows());
        executeAsSuperuser("select * from pg_catalog.pg_class where relname = 'my_table' order by relname");
        String superUserResult = printedTable(response.rows());
        assertThat(newUserWithPrivilegesResult).isEqualTo(superUserResult);
    }

    @Test
    public void testAccessesToPgProcEntriesWithRespectToPrivileges() throws Exception {
        //make sure a new user has default accesses to pg tables with information and pg catalog schema related entries
        try (Session testUserSession = testUserSession()) {
            execute("select proname from pg_catalog.pg_proc order by proname", null, testUserSession);
            assertThat(response.rowCount()).isGreaterThan(0);

            //create a table and a function that a new user is not privileged to access
            executeAsSuperuser("create table test_schema.my_table (my_col int)");
            executeAsSuperuser("create function test_schema.bar(long)" +
                            " returns string language dummy_lang as 'function bar(x) { return \"1\"; }'");

            //make sure a new user cannot access function bar without privilege
            execute("select * from pg_catalog.pg_proc where proname = 'bar' order by proname", null, testUserSession);
            assertThat(response).hasRowCount(0L);

            //if privilege is granted, the new user can access
            executeAsSuperuser("grant DQL on schema test_schema to " + TEST_USERNAME);
            execute("select * from pg_catalog.pg_proc where proname = 'bar' order by proname", null, testUserSession);
            assertThat(response).hasRowCount(1L);
        }

        //values are identical
        String newUserWithPrivilegesResult = printedTable(response.rows());
        executeAsSuperuser("select * from pg_catalog.pg_proc where proname = 'bar' order by proname");
        String superUserResult = printedTable(response.rows());
        assertThat(newUserWithPrivilegesResult).isEqualTo(superUserResult);

        executeAsSuperuser("drop function test_schema.bar(bigint)");
    }

    @Test
    public void testAccessesToPgNamespaceEntriesWithRespectToPrivileges() throws Exception {
        //make sure a new user has default accesses to pg tables with information and pg catalog schema related entries
        try (Session testUserSession = testUserSession()) {
            execute("select nspname from pg_catalog.pg_namespace " +
                    "where nspname='information_schema' or nspname='pg_catalog' or nspname='sys' order by nspname",
                    null, testUserSession);
            assertThat(response).hasRows(
                "information_schema",
                "pg_catalog");
            execute("select * from pg_catalog.pg_namespace " +
                    "where nspname='blob' or nspname='doc'", null, testUserSession);
            assertThat(response).hasRowCount(0L);

            //create a schema that a new user is not privileged to access
            executeAsSuperuser("create table test_schema.my_table (my_col int)");
            executeAsSuperuser("insert into test_schema.my_table values (1),(2)");

            //make sure a new user cannot access test_schema without privilege
            execute("select * from pg_catalog.pg_namespace where nspname = 'test_schema' order by nspname", null, testUserSession);
            assertThat(response).hasRowCount(0L);

            //if privilege is granted, the new user can access
            executeAsSuperuser("grant DQL on schema test_schema to " + TEST_USERNAME);
            execute("select * from pg_catalog.pg_namespace where nspname = 'test_schema' order by nspname", null, testUserSession);
            assertThat(response).hasRowCount(1L);
        }

        //values are identical
        String newUserWithPrivilegesResult = printedTable(response.rows());
        executeAsSuperuser("select * from pg_catalog.pg_namespace where nspname = 'test_schema' order by nspname");
        String superUserResult = printedTable(response.rows());
        assertThat(newUserWithPrivilegesResult).isEqualTo(superUserResult);
    }

    @Test
    public void testAccessesToPgAttributeEntriesWithRespectToPrivileges() throws Exception {
        //make sure a new user has default accesses to pg tables with information and pg catalog schema related entries
        try (Session testUserSession = testUserSession()) {
            execute("select * from pg_catalog.pg_attribute order by attname", null, testUserSession);
            assertThat(response).hasRowCount(594L);

            //create a table with an attribute that a new user is not privileged to access
            executeAsSuperuser("create table test_schema.my_table (my_col int)");
            executeAsSuperuser("insert into test_schema.my_table values (1),(2)");

            //make sure a new user cannot access my_col without privilege
            execute("select * from pg_catalog.pg_attribute where attname = 'my_col' order by attname", null, testUserSession);
            assertThat(response).hasRowCount(0L);

            //if privilege is granted, the new user can access
            executeAsSuperuser("grant DQL on table test_schema.my_table to " + TEST_USERNAME);
            execute("select * from pg_catalog.pg_attribute where attname = 'my_col' order by attname", null, testUserSession);
            assertThat(response).hasRowCount(1L);
        }

        //values are identical
        String newUserWithPrivilegesResult = printedTable(response.rows());
        executeAsSuperuser("select * from pg_catalog.pg_attribute where attname = 'my_col' order by attname");
        String superUserResult = printedTable(response.rows());
        assertThat(newUserWithPrivilegesResult).isEqualTo(superUserResult);
    }

    @Test
    public void testAccessesToPgConstraintEntriesWithRespectToPrivileges() throws Exception {
        int relationOid = OidHash.relationOid(Type.TABLE, new RelationName("test_schema", "my_table"));
        String selectConstraints =
            "select conname from pg_catalog.pg_constraint where conrelid = ? order by conname";
        Object[] args = new Object[] { relationOid };

        //make sure a new user has default accesses to pg tables with information and pg catalog schema related entries
        try (Session testUserSession = testUserSession()) {
            execute("select conname from pg_catalog.pg_constraint order by conname", null, testUserSession);
            assertThat(response).hasRows(
                    "columns_pkey",
                    "key_column_usage_pkey",
                    "referential_constraints_pkey",
                    "schemata_pkey",
                    "sql_features_pkey",
                    "table_constraints_pkey",
                    "table_partitions_pkey",
                    "tables_pkey",
                    "views_pkey");

            //create a table with constraints that a new user is not privileged to access
            executeAsSuperuser("""
                create table test_schema.my_table (
                    my_pk int primary key,
                    my_col int constraint positive_num check (my_col > 0)
                )
                """
            );

            execute(selectConstraints, args, testUserSession);
            assertThat(response)
                .as("user doesn't see constraints for tables without privileges")
                .isEmpty();

            executeAsSuperuser("grant DQL on table test_schema.my_table to " + TEST_USERNAME);
            execute(selectConstraints, args, testUserSession);
            assertThat(response)
                .as("user sees constraints after having granted privileges")
                .hasRows(
                    "my_table_pkey",
                    "positive_num"
                );
        }

        executeAsSuperuser(selectConstraints, args);
        assertThat(response)
            .as("super user sees same constraints")
            .hasRows(
                "my_table_pkey",
                "positive_num"
            );
    }

    @Test
    public void test_create_user_as_regular_user_with_al_privileges_from_parent() throws Exception {
        executeAsSuperuser("CREATE USER trillian");
        assertUserIsCreated("trillian");
        executeAsSuperuser("CREATE ROLE al_role");
        assertRoleIsCreated("al_role");
        executeAsSuperuser("GRANT al_role TO trillian");
        Asserts.assertSQLError(() -> executeAs("CREATE ROLE test", "trillian"))
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(UNAUTHORIZED, 4011)
            .hasMessageContaining("Missing 'AL' privilege for user 'trillian'");

        executeAsSuperuser("GRANT AL TO al_role");
        executeAs("CREATE ROLE test", "trillian");
        assertRoleIsCreated("test");
    }
}
