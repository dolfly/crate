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

package org.elasticsearch.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.cluster.coordination.Coordinator;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.Index;
import org.elasticsearch.test.IntegTestCase;
import org.elasticsearch.test.IntegTestCase.ClusterScope;
import org.elasticsearch.test.IntegTestCase.Scope;
import org.junit.Test;


@ClusterScope(scope = Scope.TEST, numDataNodes = 0)
public class MetadataWriteDataNodesIT extends IntegTestCase {

    public void testMetaWrittenAlsoOnDataNode() throws Exception {
        // this test checks that index state is written on data only nodes if they have a shard allocated
        String masterNode = cluster().startMasterOnlyNode(Settings.EMPTY);
        String dataNode = cluster().startDataOnlyNode(Settings.EMPTY);
        execute("create table doc.test(x int) with (number_of_replicas = 0)");
        execute("insert into doc.test values(1)");
        ensureGreen();

        Index index = resolveIndex("doc.test");
        String indexUUID = index.getUUID();

        assertIndexInMetaState(dataNode, indexUUID);
        assertIndexInMetaState(masterNode, indexUUID);
    }

    @Test
    public void testIndexFilesAreRemovedIfAllShardsFromIndexRemoved() throws Exception {
        // this test checks that the index data is removed from a data only node once all shards have been allocated away from it
        String masterNode = cluster().startMasterOnlyNode(Settings.EMPTY);
        List<String> nodeNames= cluster().startDataOnlyNodes(2);
        String node1 = nodeNames.get(0);
        String node2 = nodeNames.get(1);

        execute("create table doc.test(x int) with (number_of_replicas = 0, \"routing.allocation.include._name\" = ?)", new Object[]{node1});
        execute("insert into doc.test values(1)");
        ensureGreen();


        Index resolveIndex = resolveIndex("doc.test");
        String indexUUID = resolveIndex.getUUID();
        assertIndexInMetaState(node1, indexUUID);
        assertIndexDirectoryExists(node1, resolveIndex);
        assertIndexDirectoryDeleted(node2, resolveIndex);
        assertIndexInMetaState(masterNode, indexUUID);
        assertIndexDirectoryDeleted(masterNode, resolveIndex);

        logger.debug("relocating index...");
        execute("alter table doc.test set(\"routing.allocation.include._name\" = ?)", new Object[] {node2});
        client().health(new ClusterHealthRequest().waitForNoRelocatingShards(true)).get();
        ensureGreen();
        assertIndexDirectoryDeleted(node1, resolveIndex);
        assertIndexInMetaState(node2, indexUUID);
        assertIndexDirectoryExists(node2, resolveIndex);
        assertIndexInMetaState(masterNode, indexUUID);
        assertIndexDirectoryDeleted(masterNode, resolveIndex);

        execute("drop table doc.test");
        assertIndexDirectoryDeleted(node1, resolveIndex);
        assertIndexDirectoryDeleted(node2, resolveIndex);
    }

    protected void assertIndexDirectoryDeleted(final String nodeName, final Index index) throws Exception {
        assertBusy(() -> {
                       logger.info("checking if index directory exists...");
                       assertThat(indexDirectoryExists(nodeName, index)).as("Expecting index directory of " + index + " to be deleted from node " + nodeName).isFalse();
                   }
        );
    }

    protected void assertIndexDirectoryExists(final String nodeName, final Index index) throws Exception {
        assertBusy(() -> assertThat(indexDirectoryExists(nodeName, index))
            .as("Expecting index directory of " + index + " to exist on node " + nodeName)
            .isTrue()
        );
    }

    protected void assertIndexInMetaState(final String nodeName, final String indexUUID) throws Exception {
        assertBusy(() -> {
                       logger.info("checking if meta state exists...");
                       try {
                           assertThat(getIndicesMetadataOnNode(nodeName).containsKey(indexUUID)).as("Expecting meta state of index " + indexUUID + " to be on node " + nodeName).isTrue();
                       } catch (Exception e) {
                           logger.info("failed to load meta state", e);
                           fail("could not load meta state");
                       }
                   }
        );
    }


    private boolean indexDirectoryExists(String nodeName, Index index) {
        NodeEnvironment nodeEnv = cluster().getInstance(NodeEnvironment.class, nodeName);
        Path[] paths = nodeEnv.indexPaths(index);
        for (Path path : paths) {
            if (Files.exists(path)) {
                return true;
            }
        }
        return false;
    }

    private ImmutableOpenMap<String, IndexMetadata> getIndicesMetadataOnNode(String nodeName) {
        final Coordinator coordinator = cluster().getInstance(Coordinator.class, nodeName);
        return coordinator.getApplierState().metadata().indices();
    }
}
