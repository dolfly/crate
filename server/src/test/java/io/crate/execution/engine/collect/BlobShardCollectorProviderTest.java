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

package io.crate.execution.engine.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.CheckedRunnable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.IntegTestCase;
import org.junit.Test;

import io.crate.analyze.WhereClause;
import io.crate.blob.v2.BlobIndicesService;
import io.crate.blob.v2.BlobShard;
import io.crate.data.Row;
import io.crate.execution.dsl.phases.RoutedCollectPhase;
import io.crate.integrationtests.SQLHttpIntegrationTest;
import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Routing;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.TransactionContext;
import io.crate.planner.distribution.DistributionInfo;

@IntegTestCase.ClusterScope(numDataNodes = 1, numClientNodes = 0, supportsDedicatedMasters = false)
public class BlobShardCollectorProviderTest extends SQLHttpIntegrationTest {

    private BlobShardCollectorProvider collectorProvider;

    private Iterable<Row> getBlobRows(RoutedCollectPhase phase, boolean repeat) throws Exception {
        Method m = BlobShardCollectorProvider.class.getDeclaredMethod("getBlobRows", TransactionContext.class, RoutedCollectPhase.class, boolean.class);
        m.setAccessible(true);
        //noinspection unchecked
        return (Iterable<Row>) m.invoke(collectorProvider, CoordinatorTxnCtx.systemTransactionContext(), phase, repeat);
    }

    @Test
    public void testReadIsolation() throws Exception {
        execute("create blob table b1 clustered into 1 shards with (number_of_replicas = 0)");
        upload("b1", "foo");
        upload("b1", "bar");
        ensureGreen();
        assertBusy(new Initializer());

        RoutedCollectPhase collectPhase = new RoutedCollectPhase(
            UUID.randomUUID(),
            1,
            "collect",
            new Routing(Map.of()),
            RowGranularity.SHARD,
            false,
            List.of(),
            List.of(),
            WhereClause.MATCH_ALL.queryOrFallback(),
            DistributionInfo.DEFAULT_BROADCAST
        );

        // No read Isolation
        Iterable<Row> iterable = getBlobRows(collectPhase, false);
        assertThat(StreamSupport.stream(iterable.spliterator(), false).count()).isEqualTo(2L);
        upload("b1", "newEntry1");

        assertThat(StreamSupport.stream(iterable.spliterator(), false).count()).isEqualTo(3L);

        // Read isolation
        iterable = getBlobRows(collectPhase, true);
        assertThat(StreamSupport.stream(iterable.spliterator(), false).count()).isEqualTo(3L);
        upload("b1", "newEntry2");
        assertThat(StreamSupport.stream(iterable.spliterator(), false).count()).isEqualTo(3L);
    }

    private final class Initializer implements CheckedRunnable<Exception> {
        @Override
        public void run() {
            try {
                ClusterService clusterService = cluster().getDataNodeInstance(ClusterService.class);
                String indexUUID = resolveIndex(".blob_b1").getUUID();
                BlobIndicesService blobIndicesService = cluster().getDataNodeInstance(BlobIndicesService.class);
                BlobShard blobShard = blobIndicesService.blobShard(new ShardId(".blob_b1", indexUUID, 0));
                assertThat(blobShard).isNotNull();
                collectorProvider = new BlobShardCollectorProvider(
                    blobShard,
                    clusterService,
                    null,
                    new NoneCircuitBreakerService(),
                    mock(NodeContext.class),
                    null,
                    Settings.EMPTY,
                    mock(Client.class),
                    Map.of()
                );
                assertThat(collectorProvider).isNotNull();
            } catch (Exception e) {
                fail("Exception shouldn't be thrown: " + e.getMessage());
            }
        }
    }
}
