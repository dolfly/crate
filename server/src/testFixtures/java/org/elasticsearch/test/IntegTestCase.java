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

package org.elasticsearch.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_WAIT_FOR_ACTIVE_SHARDS;
import static org.elasticsearch.discovery.DiscoveryModule.DISCOVERY_SEED_PROVIDERS_SETTING;
import static org.elasticsearch.discovery.SettingsBasedSeedHostsProvider.DISCOVERY_SEED_HOSTS_SETTING;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoTimeout;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.cluster.tasks.PendingClusterTasksAction;
import org.elasticsearch.action.admin.cluster.tasks.PendingClusterTasksRequest;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.metadata.AutoExpandReplicas;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.DiskThresholdSettings;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.collect.ImmutableOpenIntMap;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.http.HttpTransportSettings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.MergePolicyConfig;
import org.elasticsearch.index.MergeSchedulerConfig;
import org.elasticsearch.index.MockEngineFactoryPlugin;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.IndicesQueryCache;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.store.IndicesStore;
import org.elasticsearch.node.NodeMocksPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.test.disruption.NetworkDisruption;
import org.elasticsearch.test.disruption.ServiceDisruptionScheme;
import org.elasticsearch.test.store.MockFSIndexStore;
import org.elasticsearch.threadpool.ThreadPool;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;

import com.carrotsearch.randomizedtesting.RandomizedContext;
import com.carrotsearch.randomizedtesting.annotations.Listeners;
import com.carrotsearch.randomizedtesting.annotations.TestGroup;
import com.carrotsearch.randomizedtesting.generators.RandomNumbers;
import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import com.carrotsearch.randomizedtesting.generators.RandomStrings;

import io.crate.analyze.Analyzer;
import io.crate.analyze.ParamTypeHints;
import io.crate.auth.Protocol;
import io.crate.common.collections.Lists;
import io.crate.common.io.IOUtils;
import io.crate.common.unit.TimeValue;
import io.crate.data.Paging;
import io.crate.data.Row;
import io.crate.data.testing.TestingRowConsumer;
import io.crate.exceptions.UnsupportedFunctionException;
import io.crate.execution.dml.BulkResponse;
import io.crate.execution.dml.TransportShardAction;
import io.crate.execution.dml.delete.TransportShardDeleteAction;
import io.crate.execution.dml.upsert.TransportShardUpsertAction;
import io.crate.execution.engine.collect.stats.JobsLogService;
import io.crate.execution.engine.collect.stats.JobsLogs;
import io.crate.execution.jobs.NodeLimits;
import io.crate.execution.jobs.RootTask;
import io.crate.execution.jobs.TasksService;
import io.crate.execution.jobs.kill.KillableCallable;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.Symbols;
import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.FunctionImplementation;
import io.crate.metadata.Functions;
import io.crate.metadata.IndexName;
import io.crate.metadata.IndexParts;
import io.crate.metadata.NodeContext;
import io.crate.metadata.RelationName;
import io.crate.metadata.RoutingProvider;
import io.crate.metadata.Schemas;
import io.crate.metadata.SearchPath;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.settings.CoordinatorSessionSettings;
import io.crate.metadata.settings.SessionSettings;
import io.crate.planner.DependencyCarrier;
import io.crate.planner.Plan;
import io.crate.planner.Planner;
import io.crate.planner.PlannerContext;
import io.crate.planner.operators.SubQueryResults;
import io.crate.planner.optimizer.LoadedRules;
import io.crate.planner.optimizer.rule.MergeFilterAndCollect;
import io.crate.protocols.postgres.ConnectionProperties;
import io.crate.protocols.postgres.PostgresNetty;
import io.crate.protocols.postgres.TransactionState;
import io.crate.role.Role;
import io.crate.role.Roles;
import io.crate.session.Cursors;
import io.crate.session.Session;
import io.crate.session.Sessions;
import io.crate.sql.Identifiers;
import io.crate.sql.parser.SqlParser;
import io.crate.statistics.TableStats;
import io.crate.statistics.TableStatsService;
import io.crate.test.integration.SystemPropsTestLoggingListener;
import io.crate.testing.SQLResponse;
import io.crate.testing.SQLTransportExecutor;
import io.crate.testing.TestExecutionConfig;
import io.crate.testing.UseHashJoins;
import io.crate.testing.UseJdbc;
import io.crate.testing.UseNewCluster;
import io.crate.testing.UseRandomizedOptimizerRules;
import io.crate.testing.UseRandomizedSchema;
import io.crate.types.DataType;

/**
 * {@link IntegTestCase} is an abstract base class to run integration
 * tests against a JVM private Elasticsearch Cluster. The test class supports 2 different
 * cluster scopes.
 * <ul>
 * <li>{@link Scope#TEST} - uses a new cluster for each individual test method.</li>
 * <li>{@link Scope#SUITE} - uses a cluster shared across all test methods in the same suite</li>
 * </ul>
 * <p>
 * The most common test scope is {@link Scope#SUITE} which shares a cluster per test suite.
 * <p>
 * If the test methods need specific node settings or change persistent and/or transient cluster settings {@link Scope#TEST}
 * should be used. To configure a scope for the test cluster the {@link ClusterScope} annotation
 * should be used, here is an example:
 * <pre>
 *
 * {@literal @}NodeScope(scope=Scope.TEST) public class SomeIT extends ESIntegTestCase {
 * public void testMethod() {}
 * }
 * </pre>
 * <p>
 * If no {@link ClusterScope} annotation is present on an integration test the default scope is {@link Scope#SUITE}
 * <p>
 * A test cluster creates a set of nodes in the background before the test starts. The number of nodes in the cluster is
 * determined at random and can change across tests. The {@link ClusterScope} allows configuring the initial number of nodes
 * that are created before the tests start.
 *  <pre>
 * {@literal @}NodeScope(scope=Scope.SUITE, numDataNodes=3)
 * public class SomeIT extends ESIntegTestCase {
 * public void testMethod() {}
 * }
 * </pre>
 * <p>
 * Note, the {@link IntegTestCase} uses randomized settings on a cluster and index level. For instance
 * each test might use different directory implementation for each test or will return a random client to one of the
 * nodes in the cluster for each call to {@link #client()}. Test failures might only be reproducible if the correct
 * system properties are passed to the test execution environment.
 * <p>
 * This class supports the following system properties (passed with -Dkey=value to the application)
 * <ul>
 * <li>-D{@value #TESTS_ENABLE_MOCK_MODULES} - a boolean value to enable or disable mock modules. This is
 * useful to test the system without asserting modules that to make sure they don't hide any bugs in production.</li>
 * <li> - a random seed used to initialize the index random context.
 * </ul>
 */
@LuceneTestCase.SuppressFileSystems("ExtrasFS") // doesn't work with potential multi data path from test cluster yet
@Listeners({SystemPropsTestLoggingListener.class})
@UseJdbc
@UseHashJoins
@UseRandomizedSchema
@UseRandomizedOptimizerRules(alwaysKeep = MergeFilterAndCollect.class)
public abstract class IntegTestCase extends ESTestCase {

    private static final Logger LOGGER = LogManager.getLogger(IntegTestCase.class);

    private static final int ORIGINAL_PAGE_SIZE = Paging.PAGE_SIZE;

    protected static SessionSettings DUMMY_SESSION_INFO = new SessionSettings(
        "dummyUser",
        SearchPath.createSearchPathFrom("dummySchema"));

    /** node names of the corresponding clusters will start with these prefixes */
    public static final String SUITE_CLUSTER_NODE_PREFIX = "node_s";
    public static final String TEST_CLUSTER_NODE_PREFIX = "node_t";

    /**
     * Key used to retrieve the index random seed from the index settings on a running node.
     * The value of this seed can be used to initialize a random context for a specific index.
     * It's set once per test via a generic index template.
     */
    public static final Setting<Long> INDEX_TEST_SEED_SETTING =
        Setting.longSetting("index.tests.seed", 0, Long.MIN_VALUE, Property.IndexScope);

    /**
     * A boolean value to enable or disable mock modules. This is useful to test the
     * system without asserting modules that to make sure they don't hide any bugs in
     * production.
     *
     * @see IntegTestCase
     */
    public static final String TESTS_ENABLE_MOCK_MODULES = "tests.enable_mock_modules";

    private static final boolean MOCK_MODULES_ENABLED = "true".equals(System.getProperty(TESTS_ENABLE_MOCK_MODULES, "true"));

    /**
     * Default minimum number of shards for an index
     */
    protected static final int DEFAULT_MIN_NUM_SHARDS = 1;

    /**
     * Default maximum number of shards for an index
     */
    protected static final int DEFAULT_MAX_NUM_SHARDS = 10;

    /**
     * The current cluster depending on the configured {@link Scope}.
     * By default if no {@link ClusterScope} is configured this will hold a reference to the suite cluster.
     */
    private static TestCluster currentCluster;

    private static final Map<Class<?>, TestCluster> clusters = new IdentityHashMap<>();

    public static final String RUN_SLOW_TESTS_PROP = "tests.crate.slow";
    private static IntegTestCase INSTANCE = null; // see @SuiteScope
    private static Long SUITE_SEED = null;

    @Rule
    public Timeout globalTimeout = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .anyMatch(s -> s.contains("-agentlib:jdwp"))
            ? new Timeout(30, TimeUnit.MINUTES)
            : new Timeout(3, TimeUnit.MINUTES);

    @Rule
    public TestName testName = new TestName();

    protected final SQLTransportExecutor sqlExecutor;

    @BeforeClass
    public static void beforeClass() throws Exception {
        // JDBC uses a thread to detect leaks,
        // this thread would trigger thread-leak detection if it would run too long
        System.setProperty("pgjdbc.config.cleanup.thread.ttl", "2");
        SUITE_SEED = randomLong();
        initializeSuiteScope();
    }

    @Override
    protected final boolean enableWarningsCheck() {
        //In an integ test it doesn't make sense to keep track of warnings: if the cluster is external the warnings are in another jvm,
        //if the cluster is internal the deprecation logger is shared across all nodes
        return false;
    }

    protected final void beforeInternal() throws Exception {
        Scope currentClusterScope = getCurrentClusterScope();
        Callable<Void> setup = () -> {
            cluster().beforeTest(random());
            cluster().wipe();
            return null;
        };
        switch (currentClusterScope) {
            case SUITE:
                assert SUITE_SEED != null : "Suite seed was not initialized";
                currentCluster = buildAndPutCluster(currentClusterScope, SUITE_SEED);
                RandomizedContext.current().runWithPrivateRandomness(SUITE_SEED, setup);
                break;
            case TEST:
                currentCluster = buildAndPutCluster(currentClusterScope, randomLong());
                setup.call();
                break;
            default:
                fail("Unknown Scope: [" + currentClusterScope + "]");
        }
    }

    private void printTestMessage(String message) {
        if (isSuiteScopedTest(getClass()) && (getTestName().equals("<unknown>"))) {
            logger.info("[{}]: {} suite", getTestClass().getSimpleName(), message);
        } else {
            logger.info("[{}#{}]: {} test", getTestClass().getSimpleName(), getTestName(), message);
        }
    }

    protected Settings.Builder setRandomIndexSettings(Random random, Settings.Builder builder) {
        setRandomIndexMergeSettings(random, builder);
        setRandomIndexTranslogSettings(random, builder);

        if (random.nextBoolean()) {
            builder.put(MergeSchedulerConfig.AUTO_THROTTLE_SETTING.getKey(), false);
        }

        if (randomBoolean()) {
            // keep this low so we don't stall tests
            builder.put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(),
                    RandomNumbers.randomIntBetween(random, 1, 15) + "ms");
        }

        return builder;
    }

    private static Settings.Builder setRandomIndexMergeSettings(Random random, Settings.Builder builder) {
        if (random.nextBoolean()) {
            builder.put(MergePolicyConfig.INDEX_COMPOUND_FORMAT_SETTING.getKey(),
                (random.nextBoolean() ? random.nextDouble() : random.nextBoolean()).toString());
        }
        if (random.nextInt(4) == 3) {
            int maxThreadCount = RandomNumbers.randomIntBetween(random, 1, 4);
            int maxMergeCount = RandomNumbers.randomIntBetween(random, maxThreadCount, maxThreadCount + 4);
            builder.put(MergeSchedulerConfig.MAX_MERGE_COUNT_SETTING.getKey(), maxMergeCount);
            builder.put(MergeSchedulerConfig.MAX_THREAD_COUNT_SETTING.getKey(), maxThreadCount);
        }

        return builder;
    }

    private static Settings.Builder setRandomIndexTranslogSettings(Random random, Settings.Builder builder) {
        if (random.nextBoolean()) {
            builder.put(IndexSettings.INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE_SETTING.getKey(),
                    new ByteSizeValue(RandomNumbers.randomIntBetween(random, 1, 300), ByteSizeUnit.MB));
        }
        if (random.nextBoolean()) {
            builder.put(IndexSettings.INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE_SETTING.getKey(),
                    new ByteSizeValue(1, ByteSizeUnit.PB)); // just don't flush
        }
        if (random.nextBoolean()) {
            builder.put(IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING.getKey(),
                    RandomPicks.randomFrom(random, Translog.Durability.values()));
        }

        if (random.nextBoolean()) {
            builder.put(IndexSettings.INDEX_TRANSLOG_SYNC_INTERVAL_SETTING.getKey(),
                    RandomNumbers.randomIntBetween(random, 100, 5000), TimeUnit.MILLISECONDS);
        }

        return builder;
    }

    private TestCluster buildWithPrivateContext(Scope scope, long seed) throws Exception {
        return RandomizedContext.current().runWithPrivateRandomness(seed, () -> buildTestCluster(scope, seed));
    }

    private TestCluster buildAndPutCluster(Scope currentClusterScope, long seed) throws Exception {
        Class<?> clazz = this.getClass();
        TestCluster testCluster = clusters.remove(clazz); // remove this cluster first
        clearClusters(); // all leftovers are gone by now... this is really just a double safety if we miss something somewhere
        switch (currentClusterScope) {
            case SUITE:
                if (testCluster == null) { // only build if it's not there yet
                    testCluster = buildWithPrivateContext(currentClusterScope, seed);
                }
                break;
            case TEST:
                // close the previous one and create a new one
                IOUtils.closeWhileHandlingException(testCluster);
                testCluster = buildTestCluster(currentClusterScope, seed);
                break;
        }
        clusters.put(clazz, testCluster);
        return testCluster;
    }

    private static void clearClusters() throws IOException {
        if (!clusters.isEmpty()) {
            IOUtils.close(clusters.values());
            clusters.clear();
        }
    }

    private void afterInternal(boolean afterClass) throws Exception {
        Scope currentClusterScope = getCurrentClusterScope();
        cluster().clearDisruptionScheme();
        try {
            TestCluster cluster = cluster();
            if (cluster != null) {
                if (currentClusterScope != Scope.TEST) {
                    Metadata metadata = FutureUtils.get(client().state(new ClusterStateRequest())).getState().metadata();
                    Set<String> persistent = metadata.persistentSettings().keySet();
                    assertThat(persistent)
                        .as("test does not leave persistent settings behind")
                        .isEmpty();
                    assertThat(metadata.transientSettings().keySet())
                        .as("test does not leave transient settings behind")
                        .isEmpty();
                }
                ensureClusterSizeConsistency();
                ensureClusterStateConsistency();
                beforeIndexDeletion();
                cluster().wipe(); // wipe after to make sure we fail in the test that didn't ack the delete
                if (afterClass || currentClusterScope == Scope.TEST) {
                    cluster().close();
                }
                cluster().assertAfterTest();
            }
        } finally {
            if (currentClusterScope == Scope.TEST) {
                clearClusters(); // it is ok to leave persistent / transient cluster state behind if scope is TEST
            }
        }
    }

    protected void beforeIndexDeletion() throws Exception {
        cluster().beforeIndexDeletion();
    }

    public static TestCluster cluster() {
        return currentCluster;
    }

    public ClusterService clusterService() {
        return cluster().clusterService();
    }

    public static Client client() {
        return client(null);
    }

    public static Client client(@Nullable String node) {
        if (node != null) {
            return cluster().client(node);
        }
        return cluster().client();
    }

    public static Client dataNodeClient() {
        return cluster().dataNodeClient();
    }

    public static Iterable<Client> clients() {
        return cluster().getClients();
    }

    protected int minimumNumberOfShards() {
        return DEFAULT_MIN_NUM_SHARDS;
    }

    protected int maximumNumberOfShards() {
        return DEFAULT_MAX_NUM_SHARDS;
    }

    protected int numberOfShards() {
        return between(minimumNumberOfShards(), maximumNumberOfShards());
    }

    protected int minimumNumberOfReplicas() {
        return 0;
    }

    protected int maximumNumberOfReplicas() {
        //use either 0 or 1 replica, yet a higher amount when possible, but only rarely
        int maxNumReplicas = Math.max(0, cluster().numDataNodes() - 1);
        return frequently() ? Math.min(1, maxNumReplicas) : maxNumReplicas;
    }

    protected int numberOfReplicas() {
        return between(minimumNumberOfReplicas(), maximumNumberOfReplicas());
    }


    public void setDisruptionScheme(ServiceDisruptionScheme scheme) {
        cluster().setDisruptionScheme(scheme);
    }

    /**
     * Creates a disruption that isolates the current master node from all other nodes in the cluster.
     *
     * @param disruptionType type of disruption to create
     * @return disruption
     */
    protected static NetworkDisruption isolateMasterDisruption(NetworkDisruption.NetworkLinkDisruptionType disruptionType) {
        final String masterNode = cluster().getMasterName();
        return new NetworkDisruption(
            new NetworkDisruption.TwoPartitions(
                Collections.singleton(masterNode),
                Arrays.stream(cluster().getNodeNames()).filter(name -> name.equals(masterNode) == false)
                    .collect(Collectors.toSet())), disruptionType);
    }

    /**
     * Returns a settings object used in {@link #createIndex(String...)} and friends.
     * This method can be overwritten by subclasses to set defaults for the indices that are created by the test.
     * By default it returns a settings object that sets a random number of shards. Number of shards and replicas
     * can be controlled through specific methods.
     */
    public Settings indexSettings() {
        Settings.Builder builder = Settings.builder();
        int numberOfShards = numberOfShards();
        if (numberOfShards > 0) {
            builder.put(SETTING_NUMBER_OF_SHARDS, numberOfShards).build();
        }
        int numberOfReplicas = numberOfReplicas();
        if (numberOfReplicas >= 0) {
            builder.put(SETTING_NUMBER_OF_REPLICAS, numberOfReplicas).build();
        }
        // 30% of the time
        if (randomInt(9) < 3) {
            String dataPath = randomAlphaOfLength(10);
            logger.info("using custom data_path for index: [{}]", dataPath);
            builder.put(IndexMetadata.SETTING_DATA_PATH, dataPath);
        }
        // always default delayed allocation to 0 to make sure we have tests are not delayed
        builder.put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), 0);
        builder.put(AutoExpandReplicas.SETTING_KEY, "false");
        builder.put(SETTING_WAIT_FOR_ACTIVE_SHARDS.getKey(), ActiveShardCount.ONE.toString());
        if (randomBoolean()) {
            builder.put(IndexSettings.INDEX_SOFT_DELETES_RETENTION_OPERATIONS_SETTING.getKey(), between(0, 1000));
        }
        return builder.build();
    }

    /**
     * Waits until all nodes have no pending tasks.
     */
    public void waitNoPendingTasksOnAll() throws Exception {
        assertNoTimeout(client().health(new ClusterHealthRequest().waitForEvents(Priority.LANGUID)).get());
        assertBusy(() -> {
            for (Client client : clients()) {
                ClusterHealthResponse clusterHealth = client.health(new ClusterHealthRequest().local(true)).get();
                assertThat(clusterHealth.getNumberOfInFlightFetch()).as("client " + client + " still has in flight fetch").isEqualTo(0);
                var pendingTasks = FutureUtils.get(
                    client.execute(PendingClusterTasksAction.INSTANCE, new PendingClusterTasksRequest().local(true)));
                assertThat(pendingTasks).as("client " + client + " still has pending tasks " + pendingTasks).isEmpty();
                clusterHealth = client.health(new ClusterHealthRequest().local(true)).get();
                assertThat(clusterHealth.getNumberOfInFlightFetch()).as("client " + client + " still has in flight fetch").isEqualTo(0);
            }
        });
        assertNoTimeout(client().health(new ClusterHealthRequest().waitForEvents(Priority.LANGUID)).get());
    }

    /**
     * Restricts the given index to be allocated on <code>n</code> nodes using the allocation deciders.
     * Yet if the shards can't be allocated on any other node shards for this index will remain allocated on
     * more than <code>n</code> nodes.
     */
    public void allowNodes(String index, int num) {
        assert index != null;
        cluster().ensureAtLeastNumDataNodes(num);
        if (num > 0) {
            RelationName relation = RelationName.fromIndexName(index);
            String exclude = String.join(",", cluster().allDataNodesButN(num));
            logger.debug("allowNodes: updating [{}]'s routng.allocation.exclude._name to [{}]", index, exclude);
            String stmt = String.format(
                Locale.ENGLISH,
                "alter table \"%s\".\"%s\" set (\"routing.allocation.exclude._name\" = ?)",
                relation.schema(),
                relation.name()
            );
            execute(stmt, new Object[] { exclude });
        }
    }

    /**
     * Ensures the cluster has a green state via the cluster health API. This method will also wait for relocations.
     * It is useful to ensure that all action on the cluster have finished and all shards that were currently relocating
     * are now allocated and started.
     */
    public void ensureGreen() throws Exception {
        sqlExecutor.ensureGreen(cluster().size());
    }

    /**
     * Ensures the cluster has a yellow state via the cluster health API.
     */
    public void ensureYellow() throws Exception {
        sqlExecutor.ensureYellowOrGreen(cluster().size());
    }

    /**
     * Waits for all relocating shards to become active using the cluster health API.
     */
    public ClusterHealthStatus waitForRelocation() {
        return waitForRelocation(null);
    }

    /**
     * Waits for all relocating shards to become active and the cluster has reached the given health status
     * using the cluster health API.
     */
    public ClusterHealthStatus waitForRelocation(ClusterHealthStatus status) {
        ClusterHealthRequest request = new ClusterHealthRequest().waitForNoRelocatingShards(true).waitForEvents(Priority.LANGUID);
        if (status != null) {
            request.waitForStatus(status);
        }
        ClusterHealthResponse actionGet = FutureUtils.get(client().health(request));
        if (actionGet.isTimedOut()) {
            var clusterStateResponse = FutureUtils.get(client().state(new ClusterStateRequest()));
            var pendingClusterTasks = FutureUtils.get(
                client().execute(PendingClusterTasksAction.INSTANCE, new PendingClusterTasksRequest()));
            logger.info(
                "waitForRelocation timed out (status={}), cluster state:\n{}\n{}",
                status,
                clusterStateResponse.getState(),
                pendingClusterTasks);
            assertThat(actionGet.isTimedOut()).as("timed out waiting for relocation").isFalse();
        }
        if (status != null) {
            assertThat(actionGet.getStatus()).isEqualTo(status);
        }
        return actionGet.getStatus();
    }

    /**
     * Waits until at least a give number of document is visible for searchers
     *
     * @param numDocs     number of documents to wait for
     * @param indexer     a {@link org.elasticsearch.test.BackgroundIndexer}. It will be first checked for documents indexed.
     *                    This saves on unneeded searches.
     * @param sqlExecutor a {@link io.crate.testing.SQLTransportExecutor}. It will be used to query for the amount of
     *                    documents indexed.
     */
    public void waitForDocs(long numDocs,
                            BackgroundIndexer indexer,
                            SQLTransportExecutor sqlExecutor) throws Exception {
        // indexing threads can wait for up to ~1m before retrying when they first try to index into a shard which is not STARTED.
        long maxWaitTimeMs = Math.max(90 * 1000, 200 * numDocs);

        assertBusy(
            () -> {
                long lastKnownCount = indexer.totalIndexedDocs();

                if (lastKnownCount >= numDocs) {
                    try {
                        var response = sqlExecutor.exec("select count(*) from " + indexer.table);
                        long count = (long) response.rows()[0][0];
                        if (count == lastKnownCount) {
                            sqlExecutor.exec("refresh table " + indexer.table);
                        }
                        lastKnownCount = count;
                    } catch (Exception e) { // count now acts like search and barfs if all shards failed...
                        logger.debug("failed to executed count", e);
                        throw e;
                    }
                }

                if (logger.isDebugEnabled()) {
                    if (lastKnownCount < numDocs) {
                        logger.debug("[{}] docs indexed. waiting for [{}]", lastKnownCount, numDocs);
                    } else {
                        logger.debug("[{}] docs visible for search (needed [{}])", lastKnownCount, numDocs);
                    }
                }

                assertThat(lastKnownCount).isGreaterThanOrEqualTo(numDocs);
            },
            maxWaitTimeMs,
            TimeUnit.MILLISECONDS
        );
    }

    protected void ensureClusterSizeConsistency() {
        if (cluster() != null && cluster().size() > 0) { // if static init fails the cluster can be null
            logger.trace("Check consistency for [{}] nodes", cluster().size());
            assertNoTimeout(FutureUtils.get(
                client().health(new ClusterHealthRequest().waitForNodes(Integer.toString(cluster().size())))
            ));
        }
    }

    /**
     * Verifies that all nodes that have the same version of the cluster state as master have same cluster state
     */
    protected void ensureClusterStateConsistency() throws IOException {
        TestCluster cluster = cluster();
        if (cluster == null || cluster.size() == 0) {
            return;
        }
        Client masterClient = client();
        ClusterState masterState = FutureUtils
            .get(masterClient.state(new ClusterStateRequest().all()))
            .getState();
        String masterId = masterState.nodes().getMasterNodeId();
        for (Client client : cluster.getClients()) {
            ClusterState localState = FutureUtils
                .get(client.state(new ClusterStateRequest().all().local(true)))
                .getState();
            // State must only match if they have the same version
            if (masterState.version() == localState.version()
                && masterId.equals(localState.nodes().getMasterNodeId())) {

                assertThat(localState)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        // routingNodes is built on demand
                        .withIgnoredFields("routingNodes")
                        .withComparatorForType((x, y) -> x.equals(y) ? 0 : 1, ImmutableOpenMap.class)
                        .withComparatorForType((x, y) -> x.equals(y) ? 0 : 1, ImmutableOpenIntMap.class)
                        .withIgnoreAllOverriddenEquals(false)
                        .build())
                    .isEqualTo(masterState);
            }
        }
    }

    protected void ensureStableCluster(int nodeCount) {
        ensureStableCluster(nodeCount, TimeValue.timeValueSeconds(30));
    }

    protected void ensureStableCluster(int nodeCount, TimeValue timeValue) {
        ensureStableCluster(nodeCount, timeValue, false, null);
    }

    protected void ensureStableCluster(int nodeCount, @Nullable String viaNode) {
        ensureStableCluster(cluster(), nodeCount, viaNode, logger);
    }

    public static void ensureStableCluster(TestCluster cluster,
                                           int nodeCount,
                                           @Nullable String viaNode,
                                           @Nullable Logger logger) {
        ensureStableCluster(cluster, nodeCount, TimeValue.timeValueSeconds(30), false, viaNode, logger);
    }

    protected void ensureStableCluster(int nodeCount, TimeValue timeValue, boolean local, @Nullable String viaNode) {
        ensureStableCluster(cluster(), nodeCount, timeValue, local, viaNode, logger);
    }

    public static void ensureStableCluster(TestCluster cluster,
                                           int nodeCount,
                                           TimeValue timeValue,
                                           boolean local,
                                           @Nullable String viaNode,
                                           @Nullable Logger logger) {
        if (viaNode == null) {
            viaNode = randomFrom(cluster.getNodeNames());
        }
        if (logger != null) {
            logger.debug("ensuring cluster is stable with [{}] nodes. access node: [{}]. timeout: [{}]", nodeCount, viaNode, timeValue);
        }
        ClusterHealthResponse clusterHealthResponse = FutureUtils.get(cluster.client(viaNode).health(
            new ClusterHealthRequest()
                .waitForEvents(Priority.LANGUID)
                .waitForNodes(Integer.toString(nodeCount))
                .timeout(timeValue)
                .local(local)
                .waitForNoRelocatingShards(true)
            ));
        if (clusterHealthResponse.isTimedOut()) {
            ClusterStateResponse stateResponse = FutureUtils
                .get(cluster.client(viaNode).state(new ClusterStateRequest()));
            fail("failed to reach a stable cluster of [" + nodeCount + "] nodes. Tried via [" + viaNode + "]. last cluster state:\n"
                 + stateResponse.getState());
        }
        assertThat(clusterHealthResponse.isTimedOut()).isFalse();
        ensureFullyConnectedCluster(cluster);
    }

    /**
     * See {@link NetworkDisruption#ensureFullyConnectedCluster(TestCluster)}
     */
    protected void ensureFullyConnectedCluster() {
        ensureFullyConnectedCluster(cluster());
    }

    protected static void ensureFullyConnectedCluster(TestCluster cluster) {
        NetworkDisruption.ensureFullyConnectedCluster(cluster);
    }


    /**
     * The scope of a test cluster used together with
     * {@link IntegTestCase.ClusterScope} annotations on {@link IntegTestCase} subclasses.
     */
    public enum Scope {
        /**
         * A cluster shared across all method in a single test suite
         */
        SUITE,
        /**
         * A test exclusive test cluster
         */
        TEST
    }

    /**
     * Defines a cluster scope for a {@link IntegTestCase} subclass.
     * By default if no {@link ClusterScope} annotation is present {@link IntegTestCase.Scope#SUITE} is used
     * together with randomly chosen settings like number of nodes etc.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE})
    public @interface ClusterScope {
        /**
         * Returns the scope. {@link IntegTestCase.Scope#SUITE} is default.
         */
        Scope scope() default Scope.SUITE;

        /**
         * Returns the number of nodes in the cluster. Default is {@code -1} which means
         * a random number of nodes is used, where the minimum and maximum number of nodes
         * are either the specified ones or the default ones if not specified.
         */
        int numDataNodes() default -1;

        /**
         * Returns the minimum number of data nodes in the cluster. Default is {@code -1}.
         * Ignored when {@link ClusterScope#numDataNodes()} is set.
         */
        int minNumDataNodes() default -1;

        /**
         * Returns the maximum number of data nodes in the cluster.  Default is {@code -1}.
         * Ignored when {@link ClusterScope#numDataNodes()} is set.
         */
        int maxNumDataNodes() default -1;

        /**
         * Indicates whether the cluster can have dedicated master nodes. If {@code false} means data nodes will serve as master nodes
         * and there will be no dedicated master (and data) nodes. Default is {@code false} which means
         * dedicated master nodes will be randomly used.
         */
        boolean supportsDedicatedMasters() default true;

        /**
         * Indicates whether the cluster automatically manages cluster bootstrapping and the removal of any master-eligible nodes. If
         * set to {@code false} then the tests must manage these processes explicitly.
         */
        boolean autoManageMasterNodes() default true;

        /**
         * Returns the number of client nodes in the cluster. Default is {@link TestCluster#DEFAULT_NUM_CLIENT_NODES}, a
         * negative value means that the number of client nodes will be randomized.
         */
        int numClientNodes() default TestCluster.DEFAULT_NUM_CLIENT_NODES;
    }

    private static <A extends Annotation> A getAnnotation(Class<?> clazz, Class<A> annotationClass) {
        if (clazz == Object.class || clazz == IntegTestCase.class) {
            return null;
        }
        A annotation = clazz.getAnnotation(annotationClass);
        if (annotation != null) {
            return annotation;
        }
        return getAnnotation(clazz.getSuperclass(), annotationClass);
    }

    private Scope getCurrentClusterScope() {
        UseNewCluster createNewCluster = getTestAnnotation(UseNewCluster.class);
        if (createNewCluster != null) {
            return Scope.TEST;
        }
        return getCurrentClusterScope(this.getClass());
    }

    private static Scope getCurrentClusterScope(Class<?> clazz) {
        ClusterScope annotation = getAnnotation(clazz, ClusterScope.class);
        // if we are not annotated assume suite!
        return annotation == null ? Scope.SUITE : annotation.scope();
    }

    private boolean getSupportsDedicatedMasters() {
        ClusterScope annotation = getAnnotation(this.getClass(), ClusterScope.class);
        return annotation == null || annotation.supportsDedicatedMasters();
    }

    private boolean getAutoManageMasterNodes() {
        ClusterScope annotation = getAnnotation(this.getClass(), ClusterScope.class);
        return annotation == null || annotation.autoManageMasterNodes();
    }

    private int getNumDataNodes() {
        ClusterScope annotation = getAnnotation(this.getClass(), ClusterScope.class);
        return annotation == null ? -1 : annotation.numDataNodes();
    }

    private int getMinNumDataNodes() {
        ClusterScope annotation = getAnnotation(this.getClass(), ClusterScope.class);
        return annotation == null || annotation.minNumDataNodes() == -1
                ? TestCluster.DEFAULT_MIN_NUM_DATA_NODES : annotation.minNumDataNodes();
    }

    private int getMaxNumDataNodes() {
        ClusterScope annotation = getAnnotation(this.getClass(), ClusterScope.class);
        return annotation == null || annotation.maxNumDataNodes() == -1
                ? TestCluster.DEFAULT_MAX_NUM_DATA_NODES : annotation.maxNumDataNodes();
    }

    private int getNumClientNodes() {
        ClusterScope annotation = getAnnotation(this.getClass(), ClusterScope.class);
        return annotation == null ? TestCluster.DEFAULT_NUM_CLIENT_NODES : annotation.numClientNodes();
    }

    /**
     * This method is used to obtain settings for the {@code N}th node in the cluster.
     * Nodes in this cluster are associated with an ordinal number such that nodes can
     * be started with specific configurations. This method might be called multiple
     * times with the same ordinal and is expected to return the same value for each invocation.
     * In other words subclasses must ensure this method is idempotent.
     */
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings.Builder builder = Settings.builder()
            // Default the watermarks to absurdly low to prevent the tests
            // from failing on nodes without enough disk space
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "1b")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "1b")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "1b")
            // by default we never cache below 10k docs in a segment,
            // bypass this limit so that caching gets some testing in
            // integration tests that usually create few documents
            .put(IndicesQueryCache.INDICES_QUERIES_CACHE_ALL_SEGMENTS_SETTING.getKey(), nodeOrdinal % 2 == 0)
            // wait short time for other active shards before actually deleting, default 30s not needed in tests
            .put(IndicesStore.INDICES_STORE_DELETE_SHARD_TIMEOUT.getKey(), new TimeValue(1, TimeUnit.SECONDS))
            .putList(DISCOVERY_SEED_HOSTS_SETTING.getKey()) // empty list disables a port scan for other nodes
            .putList(DISCOVERY_SEED_PROVIDERS_SETTING.getKey(), "file")
            .put(HttpTransportSettings.SETTING_HTTP_COMPRESSION.getKey(), false)
            .put(PostgresNetty.PSQL_PORT_SETTING.getKey(), 0);
        if (randomBoolean()) {
            builder.put("memory.allocation.type", "off-heap");
        }

        return builder.build();
    }

    protected Path nodeConfigPath(int nodeOrdinal) {
        return null;
    }

    /**
     * Returns a collection of plugins that should be loaded on each node.
     */
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of();
    }

    protected TestCluster buildTestCluster(Scope scope, long seed) {
        String nodePrefix = switch (scope) {
            case TEST -> TEST_CLUSTER_NODE_PREFIX;
            case SUITE -> SUITE_CLUSTER_NODE_PREFIX;
        };

        boolean supportsDedicatedMasters = getSupportsDedicatedMasters();
        int numDataNodes = getNumDataNodes();
        int minNumDataNodes;
        int maxNumDataNodes;
        if (numDataNodes >= 0) {
            minNumDataNodes = maxNumDataNodes = numDataNodes;
        } else {
            minNumDataNodes = getMinNumDataNodes();
            maxNumDataNodes = getMaxNumDataNodes();
        }
        Collection<Class<? extends Plugin>> mockPlugins = getMockPlugins();
        NodeConfigurationSource nodeConfigurationSource = getNodeConfigSource();
        return new TestCluster(
            seed,
            createTempDir(),
            supportsDedicatedMasters,
            getAutoManageMasterNodes(),
            minNumDataNodes,
            maxNumDataNodes,
            TestCluster.clusterName(scope.name(), seed) + "-cluster",
            nodeConfigurationSource,
            getNumClientNodes(),
            nodePrefix,
            mockPlugins
        );
    }

    private NodeConfigurationSource getNodeConfigSource() {
        return new NodeConfigurationSource() {
            @Override
            public Settings nodeSettings(int nodeOrdinal) {
                return IntegTestCase.this.nodeSettings(nodeOrdinal);
            }

            @Override
            public Path nodeConfigPath(int nodeOrdinal) {
                return IntegTestCase.this.nodeConfigPath(nodeOrdinal);
            }

            @Override
            public Collection<Class<? extends Plugin>> nodePlugins() {
                return IntegTestCase.this.nodePlugins();
            }
        };
    }

    /** Returns {@code true} iff this test cluster should use a dummy http transport */
    protected boolean addMockHttpTransport() {
        return true;
    }

    /**
     * Returns {@code true} if this test cluster can use a mock internal engine. Defaults to true.
     */
    protected boolean addMockInternalEngine() {
        return true;
    }

    /** Return the mock plugins the cluster should use */
    protected Collection<Class<? extends Plugin>> getMockPlugins() {
        ArrayList<Class<? extends Plugin>> mocks = new ArrayList<>();
        if (MOCK_MODULES_ENABLED && randomBoolean()) { // sometimes run without those completely
            if (randomBoolean()) {
                mocks.add(MockFSIndexStore.TestPlugin.class);
            }
            if (randomBoolean()) {
                mocks.add(NodeMocksPlugin.class);
            }
            if (addMockInternalEngine() && randomBoolean()) {
                mocks.add(MockEngineFactoryPlugin.class);
            }
        }

        if (addMockHttpTransport()) {
            mocks.add(MockHttpTransport.TestPlugin.class);
        }

        mocks.add(TestSeedPlugin.class);
        return Collections.unmodifiableList(mocks);
    }

    public static final class TestSeedPlugin extends Plugin {
        @Override
        public List<Setting<?>> getSettings() {
            return Collections.singletonList(INDEX_TEST_SEED_SETTING);
        }
    }

    /**
     * Returns path to a random directory that can be used to create a temporary file system repo
     */
    public Path randomRepoPath() {
        return randomRepoPath(currentCluster.getDefaultSettings());
    }

    /**
     * Returns path to a random directory that can be used to create a temporary file system repo
     */
    public static Path randomRepoPath(Settings settings) {
        Environment environment = TestEnvironment.newEnvironment(settings);
        Path[] repoFiles = environment.repoFiles();
        assert repoFiles.length > 0;
        Path path;
        do {
            path = repoFiles[0].resolve(randomAlphaOfLength(10));
        } while (Files.exists(path));
        return path;
    }


    /**
     * Asserts that all shards are allocated on nodes matching the given node pattern.
     */
    public Set<String> assertAllShardsOnNodes(String index, String... pattern) {
        Set<String> nodes = new HashSet<>();
        ClusterState clusterState = FutureUtils
            .get(client().state(new ClusterStateRequest()))
            .getState();
        for (IndexRoutingTable indexRoutingTable : clusterState.routingTable()) {
            for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                for (ShardRouting shardRouting : indexShardRoutingTable) {
                    if (shardRouting.currentNodeId() != null && index.equals(shardRouting.getIndexUUID())) {
                        String name = clusterState.nodes().get(shardRouting.currentNodeId()).getName();
                        nodes.add(name);
                        assertThat(Regex.simpleMatch(pattern, name))
                            .as("Allocated on new node: " + name)
                            .isTrue();
                    }
                }
            }
        }
        return nodes;
    }

    private static boolean runTestScopeLifecycle() {
        return INSTANCE == null;
    }

    @Before
    public final void setupTestCluster() throws Exception {
        if (runTestScopeLifecycle()) {
            printTestMessage("setting up");
            beforeInternal();
            printTestMessage("all set up");
        }
    }

    @After
    public final void cleanUpCluster() throws Exception {
        // Deleting indices is going to clear search contexts implicitly so we
        // need to check that there are no more in-flight search contexts before
        // we remove indices
        if (runTestScopeLifecycle()) {
            printTestMessage("cleaning up after");
            afterInternal(false);
            printTestMessage("cleaned up after");
        }
    }

    @AfterClass
    public static void afterClass() throws Exception {
        try {
            if (runTestScopeLifecycle()) {
                clearClusters();
            } else {
                INSTANCE.printTestMessage("cleaning up after");
                INSTANCE.afterInternal(true);
                checkStaticState(true);
            }
        } finally {
            SUITE_SEED = null;
            currentCluster = null;
            INSTANCE = null;
        }
    }

    private static void initializeSuiteScope() throws Exception {
        Class<?> targetClass = getTestClass();
        /*
         * Note we create these test class instance via reflection
         * since JUnit creates a new instance per test and that is also
         * the reason why INSTANCE is static since this entire method
         * must be executed in a static context.
         */
        assert INSTANCE == null;
        if (isSuiteScopedTest(targetClass)) {
            // note we need to do this this way to make sure this is reproducible
            INSTANCE = (IntegTestCase) targetClass.getConstructor().newInstance();
            boolean success = false;
            try {
                INSTANCE.printTestMessage("setup");
                INSTANCE.beforeInternal();
                INSTANCE.setupSuiteScopeCluster();
                success = true;
            } finally {
                if (!success) {
                    afterClass();
                }
            }
        } else {
            INSTANCE = null;
        }
    }

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        if (cluster().size() > 0) {
            // If it's internal cluster - using existing registry in case plugin registered custom data
            return cluster().getInstance(NamedXContentRegistry.class);
        } else {
            // If it's external cluster - fall back to the standard set
            return new NamedXContentRegistry(ClusterModule.getNamedXWriteables());
        }
    }

    /**
     * This method is executed iff the test is annotated with {@link SuiteScopeTestCase}
     * before the first test of this class is executed.
     *
     * @see SuiteScopeTestCase
     */
    protected void setupSuiteScopeCluster() throws Exception {
    }

    private static boolean isSuiteScopedTest(Class<?> clazz) {
        return clazz.getAnnotation(SuiteScopeTestCase.class) != null;
    }

    /**
     * If a test is annotated with {@link SuiteScopeTestCase}
     * the checks and modifications that are applied to the used test cluster are only done after all tests
     * of this class are executed. This also has the side-effect of a suite level setup method {@link #setupSuiteScopeCluster()}
     * that is executed in a separate test instance. Variables that need to be accessible across test instances must be static.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @Target(ElementType.TYPE)
    public @interface SuiteScopeTestCase {
    }


    /**
     * Annotation for tests that are slow. Slow tests do not run by default but can be
     * enabled.
     */
    @Documented
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @TestGroup(enabled = false, sysProperty = RUN_SLOW_TESTS_PROP)
    public @interface Slow {}

    protected SQLResponse response;

    public IntegTestCase() {
        this(false);
    }

    public IntegTestCase(boolean useSSL) {
        this(new SQLTransportExecutor(
            new SQLTransportExecutor.ClientProvider() {
                @Override
                public Client client() {
                    return IntegTestCase.client();
                }

                @Override
                public String pgUrl() {
                    PostgresNetty postgresNetty = cluster().getInstance(PostgresNetty.class);
                    BoundTransportAddress boundTransportAddress = postgresNetty.boundAddress();
                    if (boundTransportAddress != null) {
                        InetSocketAddress address = boundTransportAddress.publishAddress().address();
                        return String.format(Locale.ENGLISH, "jdbc:postgresql://%s:%d/?ssl=%s&sslmode=%s",
                            address.getHostName(), address.getPort(), useSSL, useSSL ? "require" : "disable");
                    }
                    return null;
                }

                @Override
                public Sessions sessions() {
                    return cluster().getInstance(Sessions.class);
                }
            }));
    }

    public static SQLTransportExecutor executor(String nodeName) {
        return executor(nodeName, false);
    }

    public static SQLTransportExecutor executor(String nodeName, boolean useSSL) {
        return new SQLTransportExecutor(
            new SQLTransportExecutor.ClientProvider() {
                @Override
                public Client client() {
                    return IntegTestCase.client(nodeName);
                }

                @Override
                public String pgUrl() {
                    PostgresNetty postgresNetty = cluster().getInstance(PostgresNetty.class, nodeName);
                    BoundTransportAddress boundTransportAddress = postgresNetty.boundAddress();
                    if (boundTransportAddress != null) {
                        InetSocketAddress address = boundTransportAddress.publishAddress().address();
                        return String.format(Locale.ENGLISH, "jdbc:postgresql://%s:%d/?ssl=%s&sslmode=%s",
                                             address.getHostName(), address.getPort(), useSSL, useSSL ? "require" : "disable");
                    }
                    return null;
                }

                @Override
                public Sessions sessions() {
                    return cluster().getInstance(Sessions.class);
                }
            });
    }

    public String getFqn(String schema, String tableName) {
        if (schema.equals(Schemas.DOC_SCHEMA_NAME)) {
            return tableName;
        }
        return String.format(Locale.ENGLISH, "%s.%s", schema, tableName);
    }

    public String getFqn(String tableName) {
        return getFqn(sqlExecutor.getCurrentSchema(), tableName);
    }

    @Before
    public void setSearchPath() throws Exception {
        sqlExecutor.setSearchPath(randomizedSchema());
    }

    @After
    public void resetPageSize() {
        Paging.PAGE_SIZE = ORIGINAL_PAGE_SIZE;
    }

    @After
    private void resetTableStats() {
        for (TableStatsService tableStats : cluster().getInstances(TableStatsService.class)) {
            tableStats.clear();
        }
    }

    public IntegTestCase(SQLTransportExecutor sqlExecutor) {
        this.sqlExecutor = sqlExecutor;
    }

    @After
    public void assertNoTasksAreLeftOpen() throws Exception {
        final Field activeTasks = TasksService.class.getDeclaredField("activeTasks");
        final Field activeOperationsSb = TransportShardAction.class.getDeclaredField("activeOperations");

        activeTasks.setAccessible(true);
        activeOperationsSb.setAccessible(true);
        try {
            assertBusy(() -> {
                for (TasksService tasksService : cluster().getInstances(TasksService.class)) {
                    try {
                        //noinspection unchecked
                        Map<UUID, RootTask> contexts = (Map<UUID, RootTask>) activeTasks.get(tasksService);
                        assertThat(contexts).isEmpty();
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
                for (TransportShardUpsertAction action : cluster().getInstances(TransportShardUpsertAction.class)) {
                    try {
                        @SuppressWarnings("unchecked")
                        ConcurrentHashMap<TaskId, KillableCallable<?>> operations = (ConcurrentHashMap<TaskId, KillableCallable<?>>) activeOperationsSb.get(action);
                        assertThat(operations).isEmpty();
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
                for (TransportShardDeleteAction action : cluster().getInstances(TransportShardDeleteAction.class)) {
                    try {
                        @SuppressWarnings("unchecked")
                        ConcurrentHashMap<TaskId, KillableCallable<?>> operations = (ConcurrentHashMap<TaskId, KillableCallable<?>>) activeOperationsSb.get(action);
                        assertThat(operations).isEmpty();
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, 10L, TimeUnit.SECONDS);
        } catch (AssertionError e) {
            StringBuilder errorMessageBuilder = new StringBuilder();
            errorMessageBuilder.append("Open jobs:\n");
            for (var jobsLogService : cluster().getInstances(JobsLogService.class)) {
                JobsLogs jobsLogs = jobsLogService.get();
                for (var jobContent : jobsLogs.activeJobs()) {
                    errorMessageBuilder.append(jobContent.toString()).append("\n");
                }
            }
            errorMessageBuilder.append("Active tasks:\n");
            String[] nodeNames = cluster().getNodeNames();
            for (String nodeName : nodeNames) {
                TasksService tasksService = cluster().getInstance(TasksService.class, nodeName);
                try {
                    //noinspection unchecked
                    Map<UUID, RootTask> contexts = (Map<UUID, RootTask>) activeTasks.get(tasksService);
                    String contextsString = contexts.toString();
                    if (!"{}".equals(contextsString)) {
                        errorMessageBuilder.append("## node: ");
                        errorMessageBuilder.append(nodeName);
                        errorMessageBuilder.append("\n");
                        errorMessageBuilder.append(contextsString);
                        errorMessageBuilder.append("\n");
                    }
                    contexts.clear();
                } catch (IllegalAccessException ex) {
                    throw new RuntimeException(ex);
                }
            }
            String errMsg = errorMessageBuilder.toString();
            LOGGER.error("Open jobs={}", errMsg);
            throw new AssertionError(errMsg, e);
        }
    }

    @After
    public void ensureNoInflightRequestsLeft() throws Exception {
        assertBusy(() -> {
            for (var nodeLimits : cluster().getInstances(NodeLimits.class)) {
                assertThat(nodeLimits.totalNumInflight()).isEqualTo(0L);
            }
        });
    }

    @After
    public void ensureNoSessionsLeft() throws Exception {
        assertBusy(() -> {
            for (var sessions : cluster().getInstances(Sessions.class)) {
                String msg = "Sessions must be closed after test teardown. " +
                    "An exception is one session created by the TableStatsService";
                assertThat(sessions.getActive())
                    .as(msg)
                    .hasSizeLessThanOrEqualTo(1);
            }
        });
    }

    @After
    public void ensure_one_node_limit_instance_per_node() {
        Iterable<NodeLimits> nodeLimitsInstances = cluster().getInstances(NodeLimits.class);
        int numInstances = 0;
        for (var nodeLimits : nodeLimitsInstances) {
            numInstances++;
        }
        assertThat(numInstances)
                .as("There must only be as many NodeLimits instances as there are nodes in the cluster")
                .isEqualTo(cluster().numNodes());
    }

    public void waitUntilShardOperationsFinished() throws Exception {
        assertBusy(() -> {
            Iterable<IndicesService> indexServices = cluster().getInstances(IndicesService.class);
            for (IndicesService indicesService : indexServices) {
                for (IndexService indexService : indicesService) {
                    for (IndexShard indexShard : indexService) {
                        assertThat(indexShard.getActiveOperationsCount()).isEqualTo(0);
                    }
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    public void waitUntilThreadPoolTasksFinished(final String name) throws Exception {
        assertBusy(() -> {
            Iterable<ThreadPool> threadPools = cluster().getInstances(ThreadPool.class);
            for (ThreadPool threadPool : threadPools) {
                ThreadPoolExecutor executor = (ThreadPoolExecutor) threadPool.executor(name);
                assertThat(executor.getActiveCount()).isEqualTo(0);
            }
        }, 5, TimeUnit.SECONDS);
    }

    /**
     * Execute a SQL statement as system query on a specific node in the cluster
     *
     * @param stmt      the SQL statement
     * @param schema    the schema that should be used for this statement
     *                  schema is nullable, which means the default schema ("doc") is used
     * @param node      the name of the node on which the stmt is executed
     * @return          the SQL Response
     */
    public SQLResponse systemExecute(String stmt, @Nullable String schema, String node) {
        Sessions sqlOperations = cluster().getInstance(Sessions.class, node);
        Roles roles = cluster().getInstance(Roles.class, node);
        SQLResponse response;
        try (Session session = sqlOperations.newSession(
            new ConnectionProperties(null, null, Protocol.HTTP, null), schema, roles.getUser("crate"))) {
            response = sqlExecutor.exec(stmt, session);
            this.response = response;
        }
        return response;
    }

    /**
     * Execute an SQL Statement on a random node of the cluster
     *
     * @param stmt the SQL Statement
     * @param args the arguments to replace placeholders ("?") in the statement
     * @return the SQLResponse
     */
    public SQLResponse execute(String stmt, Object[] args) {
        try {
            SQLResponse response = sqlExecutor.exec(testExecutionConfig(), stmt, args);
            this.response = response;
            return response;
        } catch (ElasticsearchTimeoutException e) {
            LOGGER.error("Timeout on SQL statement: {} {}", stmt, e);
            cluster().dumpActiveTasks();
            throw e;
        }
    }

    /**
     * Execute an SQL Statement on a random node of the cluster
     *
     * @param stmt    the SQL Statement
     * @param args    the arguments of the statement
     * @param timeout internal timeout of the statement
     * @return the SQLResponse
     */
    public SQLResponse execute(String stmt, Object[] args, TimeValue timeout) throws SQLException {
        try {
            SQLResponse response = sqlExecutor.exec(testExecutionConfig(), stmt, args, timeout);
            this.response = response;
            return response;
        } catch (ElasticsearchTimeoutException e) {
            LOGGER.error("Timeout on SQL statement: {} {}", stmt, e);
            cluster().dumpActiveTasks();
            throw e;
        }
    }

    /**
     * Execute an SQL Statement on a random node of the cluster
     *
     * @param stmt    the SQL statement
     * @param schema  the schema that should be used for this statement
     *                schema is nullable, which means default schema ("doc") is used
     * @return the SQLResponse
     */
    public SQLResponse execute(String stmt, @Nullable String schema) {
        try (Session createSession = createSession(schema)) {
            return execute(stmt, null, createSession);
        }
    }

    public static class PlanForNode {
        public final Plan plan;
        final String nodeName;
        public final PlannerContext plannerContext;

        private PlanForNode(Plan plan, String nodeName, PlannerContext plannerContext) {
            this.plan = plan;
            this.nodeName = nodeName;
            this.plannerContext = plannerContext;
        }
    }

    public PlanForNode plan(String stmt) {
        String[] nodeNames = cluster().getNodeNames();
        String nodeName = nodeNames[randomIntBetween(1, nodeNames.length) - 1];

        Analyzer analyzer = cluster().getInstance(Analyzer.class, nodeName);
        Planner planner = cluster().getInstance(Planner.class, nodeName);

        CoordinatorSessionSettings sessionSettings = new CoordinatorSessionSettings(
            Role.CRATE_USER,
            Role.CRATE_USER,
            LoadedRules.INSTANCE.disabledRules(),
            sqlExecutor.getCurrentSchema()
        );
        CoordinatorTxnCtx coordinatorTxnCtx = new CoordinatorTxnCtx(sessionSettings);
        RoutingProvider routingProvider = new RoutingProvider(Randomness.get().nextInt(), planner.getAwarenessAttributes());
        PlannerContext plannerContext = planner.createContext(
            routingProvider,
            UUID.randomUUID(),
            coordinatorTxnCtx,
            0,
            null,
            Cursors.EMPTY,
            TransactionState.IDLE,
            Session.TimeoutToken.noopToken()
        );
        Plan plan = planner.plan(
            analyzer.analyze(
                SqlParser.createStatement(stmt),
                coordinatorTxnCtx.sessionSettings(),
                ParamTypeHints.EMPTY,
                plannerContext.cursors()
            ),
            plannerContext
        );
        return new PlanForNode(plan, nodeName, plannerContext);
    }

    public TestingRowConsumer execute(PlanForNode planForNode) {
        DependencyCarrier dependencyCarrier = cluster().getInstance(DependencyCarrier.class, planForNode.nodeName);
        TestingRowConsumer downstream = new TestingRowConsumer();
        planForNode.plan.execute(
            dependencyCarrier,
            planForNode.plannerContext,
            downstream,
            Row.EMPTY,
            SubQueryResults.EMPTY
        );
        return downstream;
    }

    /**
     * Execute an SQL Statement on a random node of the cluster
     *
     * @param stmt     the SQL Statement
     * @param bulkArgs the bulk arguments of the statement
     * @return the SQLResponse
     */
    public BulkResponse execute(String stmt, Object[][] bulkArgs) {
        return sqlExecutor.execBulk(stmt, bulkArgs);
    }

    /**
     * Execute an SQL Statement on a random node of the cluster
     *
     * @param stmt     the SQL Statement
     * @param bulkArgs the bulk arguments of the statement
     * @return the SQLResponse
     */
    public BulkResponse execute(String stmt, Object[][] bulkArgs, TimeValue timeout) {
        return sqlExecutor.execBulk(stmt, bulkArgs, timeout);
    }

    /**
     * Execute an SQL Statement on a random node of the cluster
     *
     * @param stmt     the SQL Statement
     * @param bulkArgs the bulk arguments of the statement
     * @param session the Session to use
     */
    public BulkResponse executeBulk(String stmt, Object[][] bulkArgs, Session session) {
        return sqlExecutor.execBulk(stmt, bulkArgs, session);
    }


    /**
     * Execute an SQL Statement on a random node of the cluster
     *
     * @param stmt the SQL Statement
     * @return the SQLResponse
     */
    public SQLResponse execute(String stmt) {
        return execute(stmt, (Object[]) null);
    }

    /**
     * Execute an SQL Statement using a specific {@link Session}
     * This is useful to execute a query on a specific node or to test using
     * session options like default schema.
     *
     * @param stmt    the SQL Statement
     * @param session the Session to use
     * @return the SQLResponse
     */
    public SQLResponse execute(String stmt, Session session) {
        return execute(stmt, null, session);
    }

    /**
     * Execute an SQL Statement using a specific {@link Session}
     * This is useful to execute a query on a specific node or to test using
     * session options like default schema.
     *
     * @param stmt    the SQL Statement
     * @param session the Session to use
     * @return the SQLResponse
     */
    public SQLResponse execute(String stmt, Object[] args, Session session) {
        var response = sqlExecutor.exec(stmt, args, session);
        this.response = response;
        return response;
    }

    public SQLResponse execute(String stmt, Object[] args, String node) {
        return execute(stmt, args, node, SQLTransportExecutor.REQUEST_TIMEOUT);
    }

    public SQLResponse execute(String stmt, Object[] args, String node, TimeValue timeout) {
        Sessions sqlOperations = cluster().getInstance(Sessions.class, node);
        try (Session session = sqlOperations.newSession(
            new ConnectionProperties(null, null, Protocol.HTTP, null), sqlExecutor.getCurrentSchema(), Role.CRATE_USER)) {
            SQLResponse response = sqlExecutor.exec(stmt, args, session, timeout);
            this.response = response;
            return response;
        }
    }

    public DocTableInfo getTable(RelationName name) {
        return cluster().getCurrentMasterNodeInstance(NodeContext.class)
            .schemas()
            .getTableInfo(name);
    }

    public DocTableInfo getTable(String table) throws IOException {
        return getTable(new RelationName(sqlExecutor.getCurrentSchema(), table));
    }

    public DocTableInfo getTable(String schema, String table) throws IOException {
        return getTable(new RelationName(schema, table));
    }

    public void assertFunctionIsCreatedOnAll(String schema, String name, List<DataType<?>> argTypes) throws Exception {
        SearchPath searchPath = SearchPath.pathWithPGCatalogAndDoc();
        assertBusy(() -> {
            Iterable<Functions> functions = cluster().getInstances(Functions.class);
            for (Functions function : functions) {
                FunctionImplementation func = function.get(
                    schema,
                    name,
                    Lists.map(argTypes, t -> Literal.of(t, null)),
                    searchPath);
                assertThat(func).isNotNull();
                assertThat(func.boundSignature().argTypes()).isEqualTo(argTypes);
            }
        }, 20L, TimeUnit.SECONDS);
    }

    public void assertFunctionIsDeletedOnAll(String schema, String name, List<Symbol> arguments) throws Exception {
        assertBusy(() -> {
            Iterable<Functions> functions = cluster().getInstances(Functions.class);
            for (Functions function : functions) {
                try {
                    var func = function.get(schema, name, arguments, SearchPath.createSearchPathFrom(schema));
                    if (func != null) {
                        // if no exact function match is found for given arguments,
                        // the function with arguments that can be casted to provided
                        // arguments will be returned. Therefore, we have to assert that
                        // the provided arguments do not match the arguments of the resolved
                        // function if the function was deleted.
                        assertThat(func.boundSignature().argTypes()).isNotEqualTo(Symbols.typeView(arguments));
                    }
                } catch (UnsupportedFunctionException e) {
                    assertThat(e.getMessage()).startsWith("Unknown function");
                }

            }
        }, 20L, TimeUnit.SECONDS);
    }

    /**
     * Creates an {@link Session} on a specific node.
     * This can be used to ensure that a request is performed on a specific node.
     *
     * @param nodeName The name of the node to create the session
     * @return The created session
     */
    protected Session createSessionOnNode(String nodeName) {
        Sessions sqlOperations = cluster().getInstance(Sessions.class, nodeName);
        return sqlOperations.newSession(new ConnectionProperties(null, null, Protocol.HTTP, null),
            sqlExecutor.getCurrentSchema(), Role.CRATE_USER);
    }

    /**
     * Creates a {@link Session} with the given default schema
     * and an options list. This is useful if you require a session which differs
     * from the default one.
     *
     * @param defaultSchema The default schema to use. Can be null.
     * @return The created session
     */
    protected Session createSession(@Nullable String defaultSchema) {
        Sessions sqlOperations = cluster().getInstance(Sessions.class);
        return sqlOperations.newSession(
            new ConnectionProperties(null, null, Protocol.HTTP, null), defaultSchema, Role.CRATE_USER);
    }

    private TestExecutionConfig testExecutionConfig() {
        var randomizedRulesConfig = randomizedRulesConfig();
        return new TestExecutionConfig(isJdbcEnabled(),
            isHashJoinEnabled(),
            randomizedRulesConfig.enabled,
            randomizedRulesConfig.percentageOfRulesToDisable,
            randomizedRulesConfig.rulesToKeep
        );
    }

    /**
     * If the Test class or method contains a @UseJdbc annotation then,
     * based on the ratio provided, a random value of true or false is returned.
     * For more details on the ratio see {@link UseJdbc}
     * <p>
     * Method annotations have higher priority than class annotations.
     */
    private boolean isJdbcEnabled() {
        UseJdbc useJdbc = getTestAnnotation(UseJdbc.class);
        if (useJdbc == null) {
            return false;
        }
        return isFeatureEnabled(useJdbc.value());
    }

    /**
     * If the Test class or method is annotated with {@link UseHashJoins} then,
     * based on the provided ratio, a random value of true or false is returned.
     * For more details on the ratio see {@link UseHashJoins}
     * <p>
     * Method annotations have higher priority than class annotations.
     */
    private boolean isHashJoinEnabled() {
        UseHashJoins useHashJoins = getTestAnnotation(UseHashJoins.class);
        if (useHashJoins == null) {
            return false;
        }
        return isFeatureEnabled(useHashJoins.value());
    }

    private RandomizedRulesConfig randomizedRulesConfig() {
        var useRandomizedOptimizerRules = getTestAnnotation(UseRandomizedOptimizerRules.class);
        var enabled = false;
        double percentageOfRulesToDisable = 0.0d;
        List<Class<? extends io.crate.planner.optimizer.Rule<?>>> rulesToKeep = List.of();
        if (useRandomizedOptimizerRules != null) {
            enabled = isFeatureEnabled(useRandomizedOptimizerRules.value());
            if (enabled) {
                rulesToKeep = Arrays.asList(useRandomizedOptimizerRules.alwaysKeep());
                if (useRandomizedOptimizerRules.disablePercentage() == -1) {
                    percentageOfRulesToDisable = RandomizedContext.current().getRandom().nextDouble(0.1, 1.0);
                } else {
                    double disablePercentage = useRandomizedOptimizerRules.disablePercentage();
                    assert disablePercentage > 0 && disablePercentage <= 1 :
                        "Percentage of rules to disable for Rule Randomization must greater than 0 and equal or less than 1";
                    percentageOfRulesToDisable = disablePercentage;
                }
            }
        }
        return new RandomizedRulesConfig(enabled, percentageOfRulesToDisable, rulesToKeep);
    }

    private record RandomizedRulesConfig(boolean enabled, double percentageOfRulesToDisable, List<Class<? extends io.crate.planner.optimizer.Rule<?>>> rulesToKeep) {}


    /**
     * Checks if the current test method or test class is annotated with the provided {@param annotationClass}
     *
     * @return the annotation if one is present or null otherwise
     */
    @Nullable
    private <T extends Annotation> T getTestAnnotation(Class<T> annotationClass) {
        try {
            Class<?> clazz = this.getClass();
            String testMethodName = testName.getMethodName();
            String[] split = testName.getMethodName().split(" ");
            if (split.length > 1) {
                // When we annotate tests with @Repeat the test method name gets augmented with a seed and we won't
                // be able to find it in the class methods, so just grab the method name.
                testMethodName = split[0];
            }

            Method method = clazz.getMethod(testMethodName);
            T annotation = method.getAnnotation(annotationClass);
            if (annotation == null) {
                annotation = clazz.getAnnotation(annotationClass);
            }
            return annotation;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * We sometimes randomize the use of features in tests.
     * This method verifies the provided ratio and based on it and a random number
     * indicates if a feature should be enabled or not.
     *
     * @param ratio a number between [0, 1] that indicates a "randomness factor"
     *              (1 = always enabled, 0 = always disabled)
     *
     * @return true if a feature should be active/used and false otherwise
     */
    private boolean isFeatureEnabled(double ratio) {
        if (ratio == 0) {
            return false;
        }

        assert ratio >= 0.0 && ratio <= 1.0;
        return ratio == 1 || RandomizedContext.current().getRandom().nextDouble() < ratio;
    }


    /**
     * If the Test class or method contains a @UseRandomizedSchema annotation then,
     * based on the schema argument, a random (unquoted) schema name is returned. The schema name consists
     * of a 1-20 character long ASCII string.
     * For more details on the schema parameter see {@link UseRandomizedSchema}
     * <p>
     * Method annotations have higher priority than class annotations.
     */
    private String randomizedSchema() {
        UseRandomizedSchema annotation = getTestAnnotation(UseRandomizedSchema.class);
        if (annotation == null || annotation.random() == false) {
            return Schemas.DOC_SCHEMA_NAME;
        }

        Random random = RandomizedContext.current().getRandom();
        while (true) {
            String schemaName = RandomStrings.randomAsciiLettersOfLengthBetween(random, 1, 20).toLowerCase();
            if (!Schemas.READ_ONLY_SYSTEM_SCHEMAS.contains(schemaName) &&
                !Identifiers.isKeyWord(schemaName) &&
                !containsExtendedAsciiChars(schemaName)) {
                return schemaName;
            }
        }
    }

    private boolean containsExtendedAsciiChars(String value) {
        for (char c : value.toCharArray()) {
            if ((short) c > 127) {
                return true;
            }
        }
        return false;
    }

    public Index resolveIndex(String indexName) {
        ClusterService clusterService = cluster().getInstance(ClusterService.class);
        return resolveIndex(indexName, sqlExecutor.getCurrentSchema(), clusterService.state().metadata());
    }

    public Index resolveIndex(String indexName, List<String> partitionValues) {
        ClusterService clusterService = cluster().getInstance(ClusterService.class);
        return resolveIndex(indexName, partitionValues, sqlExecutor.getCurrentSchema(), clusterService.state().metadata());
    }

    public static RelationName resolveRelationName(String indexName, String currentSchema) {
        IndexParts indexParts = IndexName.decode(indexName);
        String schema = indexParts.schema();
        if (indexName.contains(schema) == false) {
            schema = currentSchema;
        }
        return new RelationName(schema, indexParts.table());
    }

    public static Index resolveIndex(String indexName, String currentSchema, Metadata metadata) {
        return resolveIndex(indexName, List.of(), currentSchema, metadata);
    }

    public static Index resolveIndex(String indexName, List<String> partitionValues, String currentSchema, Metadata metadata) {
        RelationName relationName = resolveRelationName(indexName, currentSchema);
        return metadata
            .getIndex(relationName, partitionValues, true, IndexMetadata::getIndex);
    }
}
