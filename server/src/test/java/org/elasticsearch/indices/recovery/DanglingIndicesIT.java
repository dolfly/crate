/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.indices.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsearch.cluster.metadata.IndexGraveyard.SETTING_MAX_TOMBSTONES;
import static org.elasticsearch.gateway.DanglingIndicesState.AUTO_IMPORT_DANGLING_INDICES_SETTING;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.test.IntegTestCase;
import org.elasticsearch.test.IntegTestCase.ClusterScope;
import org.elasticsearch.test.TestCluster;
import org.junit.Test;

import io.crate.metadata.RelationName;


@ClusterScope(numDataNodes = 0, scope = IntegTestCase.Scope.TEST)
public class DanglingIndicesIT extends IntegTestCase {

    private Settings buildSettings(boolean writeDanglingIndices, boolean importDanglingIndices) {
        return Settings.builder()
            // Don't keep any indices in the graveyard, so that when we delete an index,
            // it's definitely considered to be dangling.
            .put(SETTING_MAX_TOMBSTONES.getKey(), 0)
            .put(IndicesService.WRITE_DANGLING_INDICES_INFO_SETTING.getKey(), writeDanglingIndices)
            .put(AUTO_IMPORT_DANGLING_INDICES_SETTING.getKey(), importDanglingIndices)
            .build();
    }

    /**
     * Check that when dangling indices are discovered, then they are recovered into
     * the cluster, so long as the recovery setting is enabled.
     */
    @Test
    public void testDanglingIndicesAreRecoveredWhenSettingIsEnabled() throws Exception {
        final Settings settings = buildSettings(true, true);
        cluster().startNodes(3, settings);

        execute("create table doc.test(id integer) clustered into 2 shards with(number_of_replicas = 2)");
        ensureGreen();
        assertBusy(() -> cluster().getInstances(IndicesService.class).forEach(
            indicesService -> assertThat(indicesService.allPendingDanglingIndicesWritten()).isTrue()));

        RelationName relationName = new RelationName("doc", "test");
        String indexUUID = clusterService().state().metadata().getIndex(relationName, List.of(), true, IndexMetadata::getIndexUUID);

        boolean refreshIntervalChanged = randomBoolean();
        if (refreshIntervalChanged) {
            execute("alter table doc.test set (refresh_interval = '42s')");
            assertBusy(() -> cluster().getInstances(IndicesService.class).forEach(
                indicesService -> assertThat(indicesService.allPendingDanglingIndicesWritten()).isTrue()));
        }

        // Restart node, deleting the index in its absence, so that there is a dangling index to recover
        cluster().restartRandomDataNode(new TestCluster.RestartCallback() {

            @Override
            public Settings onNodeStopped(String nodeName) throws Exception {
                ensureClusterSizeConsistency();
                execute("drop table doc.test");
                return super.onNodeStopped(nodeName);
            }
        });

        assertBusy(() ->
            assertThat(execute("select 1 from information_schema.tables where table_name='test'").rowCount())
                .as("Expected dangling index test to be recovered")
                .isEqualTo((1L)));
        ensureGreen();


        final IndexMetadata indexMetadata = clusterService().state().metadata().index(indexUUID);
        assertThat(indexMetadata.getSettings().get(IndexMetadata.SETTING_HISTORY_UUID)).isNotNull();
    }

    /**
     * Check that when dangling indices are discovered, then they are not recovered into
     * the cluster when the recovery setting is disabled.
     */
    @Test
    public void testDanglingIndicesAreNotRecoveredWhenSettingIsDisabled() throws Exception {
        cluster().startNodes(3, buildSettings(false, true));

        execute("create table doc.test(id integer) clustered into 2 shards with(number_of_replicas = 2)");
        ensureGreen();

        assertBusy(() -> cluster().getInstances(IndicesService.class).forEach(
            indicesService -> assertThat(indicesService.allPendingDanglingIndicesWritten()).isTrue()));

        // Restart node, deleting the index in its absence, so that there is a dangling index to recover
        cluster().restartRandomDataNode(new TestCluster.RestartCallback() {

            @Override
            public Settings onNodeStopped(String nodeName) throws Exception {
                ensureClusterSizeConsistency();
                execute("drop table doc.test");
                return super.onNodeStopped(nodeName);
            }
        });

        // Since index recovery is async, we can't prove index recovery will never occur, just that it doesn't occur within some reasonable
        // amount of time
        assertBusy(() -> assertThat(
            execute("select 1 from information_schema.tables where table_name='test'").rowCount())
                .as("Did not expect dangling index test to be recovered")
                .isEqualTo(0L), 1, TimeUnit.SECONDS);
    }
}
