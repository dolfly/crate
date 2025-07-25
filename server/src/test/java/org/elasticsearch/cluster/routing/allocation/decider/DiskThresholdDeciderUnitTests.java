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

package org.elasticsearch.cluster.routing.allocation.decider;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.Collections;
import java.util.HashSet;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.DiskUsage;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RecoverySource.EmptyStoreRecoverySource;
import org.elasticsearch.cluster.routing.RecoverySource.LocalShardsRecoverySource;
import org.elasticsearch.cluster.routing.RecoverySource.PeerRecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingHelper;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDeciderTests.DevNullClusterInfo;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.junit.Test;

public class DiskThresholdDeciderUnitTests extends ESAllocationTestCase {

    @Test
    public void testCanAllocateUsesMaxAvailableSpace() {
        ClusterSettings nss = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        DiskThresholdDecider decider = new DiskThresholdDecider(Settings.EMPTY, nss);

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();

        final Index index = metadata.index("test").getIndex();

        ShardRouting test_0 = ShardRouting.newUnassigned(new ShardId(index, 0), true, EmptyStoreRecoverySource.INSTANCE,
                                                         new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        DiscoveryNode node_0 = new DiscoveryNode("node_0", buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES), Version.CURRENT);
        DiscoveryNode node_1 = new DiscoveryNode("node_1", buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES), Version.CURRENT);

        RoutingTable routingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata).routingTable(routingTable).build();

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                                                                    .add(node_0)
                                                                    .add(node_1)
        ).build();

        // actual test -- after all that bloat :)
        ImmutableOpenMap.Builder<String, DiskUsage> leastAvailableUsages = ImmutableOpenMap.builder();
        leastAvailableUsages.put("node_0", new DiskUsage("node_0", "node_0", "_na_", 100, 0)); // all full
        leastAvailableUsages.put("node_1", new DiskUsage("node_1", "node_1", "_na_", 100, 0)); // all full

        ImmutableOpenMap.Builder<String, DiskUsage> mostAvailableUsage = ImmutableOpenMap.builder();
        // 20 - 99 percent since after allocation there must be at least 10% left and shard is 10byte
        mostAvailableUsage.put("node_0", new DiskUsage("node_0", "node_0", "_na_", 100, randomIntBetween(20, 100)));
        // this is weird and smells like a bug! it should be up to 20%?
        mostAvailableUsage.put("node_1", new DiskUsage("node_1", "node_1", "_na_", 100, randomIntBetween(0, 10)));

        ImmutableOpenMap.Builder<String, Long> shardSizes = ImmutableOpenMap.builder();
        shardSizes.put("[_na_/test][0][p]", 10L); // 10 bytes
        final ClusterInfo clusterInfo = new ClusterInfo(leastAvailableUsages.build(),
            mostAvailableUsage.build(), shardSizes.build(), ImmutableOpenMap.of(),  ImmutableOpenMap.of());
        RoutingAllocation allocation = new RoutingAllocation(new AllocationDeciders(Collections.singleton(decider)),
            clusterState.getRoutingNodes(), clusterState, clusterInfo, null, System.nanoTime());
        allocation.debugDecision(true);
        Decision decision = decider.canAllocate(test_0, new RoutingNode("node_0", node_0), allocation);
        assertThat(decision.type()).as(mostAvailableUsage.toString()).isEqualTo(Decision.Type.YES);
        assertThat(decision.getExplanation()).contains("enough disk for shard on node");
        decision = decider.canAllocate(test_0, new RoutingNode("node_1", node_1), allocation);
        assertThat(decision.type()).as(mostAvailableUsage.toString()).isEqualTo(Decision.Type.NO);
        assertThat(decision.getExplanation()).contains(
                "the node is above the high watermark cluster setting " +
                "[cluster.routing.allocation.disk.watermark.high=90%], " +
                "using more disk space than the maximum allowed [90.0%]");
    }

    @Test
    public void testCannotAllocateDueToLackOfDiskResources() {
        ClusterSettings nss = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        DiskThresholdDecider decider = new DiskThresholdDecider(Settings.EMPTY, nss);

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();

        final Index index = metadata.index("test").getIndex();

        ShardRouting test_0 = ShardRouting.newUnassigned(new ShardId(index, 0), true, EmptyStoreRecoverySource.INSTANCE,
                                                         new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        DiscoveryNode node_0 = new DiscoveryNode("node_0", buildNewFakeTransportAddress(), Collections.emptyMap(),
            new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES), Version.CURRENT);
        DiscoveryNode node_1 = new DiscoveryNode("node_1", buildNewFakeTransportAddress(), Collections.emptyMap(),
            new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES), Version.CURRENT);

        RoutingTable routingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata).routingTable(routingTable).build();

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                                                                    .add(node_0)
                                                                    .add(node_1)
        ).build();

        // actual test -- after all that bloat :)

        ImmutableOpenMap.Builder<String, DiskUsage> leastAvailableUsages = ImmutableOpenMap.builder();
        leastAvailableUsages.put("node_0", new DiskUsage("node_0", "node_0", "_na_", 100, 0)); // all full
        ImmutableOpenMap.Builder<String, DiskUsage> mostAvailableUsage = ImmutableOpenMap.builder();
        final int freeBytes = randomIntBetween(20, 100);
        mostAvailableUsage.put("node_0", new DiskUsage("node_0", "node_0", "_na_", 100, freeBytes));

        ImmutableOpenMap.Builder<String, Long> shardSizes = ImmutableOpenMap.builder();
        // way bigger than available space
        final long shardSize = randomIntBetween(110, 1000);
        shardSizes.put("[test/test][0][p]", shardSize);
        ClusterInfo clusterInfo = new ClusterInfo(leastAvailableUsages.build(), mostAvailableUsage.build(),
            shardSizes.build(), ImmutableOpenMap.of(),  ImmutableOpenMap.of());
        RoutingAllocation allocation = new RoutingAllocation(new AllocationDeciders(Collections.singleton(decider)),
            clusterState.getRoutingNodes(), clusterState, clusterInfo, null, System.nanoTime());
        allocation.debugDecision(true);
        Decision decision = decider.canAllocate(test_0, new RoutingNode("node_0", node_0), allocation);
        assertThat(decision.type()).isEqualTo(Decision.Type.NO);

        assertThat(decision.getExplanation()).contains(
            "allocating the shard to this node will bring the node above the high watermark cluster setting "
            +"[cluster.routing.allocation.disk.watermark.high=90%] "
            + "and cause it to have less than the minimum required [0b] of free space "
            + "(free: [" + freeBytes + "b], estimated shard size: [" + shardSize + "b])");
    }

    @Test
    public void testCanRemainUsesLeastAvailableSpace() {
        ClusterSettings nss = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        DiskThresholdDecider decider = new DiskThresholdDecider(Settings.EMPTY, nss);
        ImmutableOpenMap.Builder<ShardRouting, String> shardRoutingMap = ImmutableOpenMap.builder();

        DiscoveryNode node_0 = new DiscoveryNode("node_0", buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES), Version.CURRENT);
        DiscoveryNode node_1 = new DiscoveryNode("node_1", buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES), Version.CURRENT);

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();
        final IndexMetadata indexMetadata = metadata.index("test");

        ShardRouting test_0 = ShardRouting.newUnassigned(new ShardId(indexMetadata.getIndex(), 0), true,
                                                         EmptyStoreRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_0 = ShardRoutingHelper.initialize(test_0, node_0.getId());
        test_0 = ShardRoutingHelper.moveToStarted(test_0);
        shardRoutingMap.put(test_0, "/node0/least");

        ShardRouting test_1 = ShardRouting.newUnassigned(new ShardId(indexMetadata.getIndex(), 1), true,
                                                         EmptyStoreRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_1 = ShardRoutingHelper.initialize(test_1, node_1.getId());
        test_1 = ShardRoutingHelper.moveToStarted(test_1);
        shardRoutingMap.put(test_1, "/node1/least");

        ShardRouting test_2 = ShardRouting.newUnassigned(new ShardId(indexMetadata.getIndex(), 2), true,
                                                         EmptyStoreRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_2 = ShardRoutingHelper.initialize(test_2, node_1.getId());
        test_2 = ShardRoutingHelper.moveToStarted(test_2);
        shardRoutingMap.put(test_2, "/node1/most");

        ShardRouting test_3 = ShardRouting.newUnassigned(new ShardId(indexMetadata.getIndex(), 3), true,
                                                         EmptyStoreRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_3 = ShardRoutingHelper.initialize(test_3, node_1.getId());
        test_3 = ShardRoutingHelper.moveToStarted(test_3);
        // Intentionally not in the shardRoutingMap. We want to test what happens when we don't know where it is.

        RoutingTable routingTable = RoutingTable.builder()
            .addAsNew(indexMetadata)
            .build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING
                                                             .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(routingTable).build();

        logger.info("--> adding two nodes");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                                                                    .add(node_0)
                                                                    .add(node_1)
        ).build();

        // actual test -- after all that bloat :)
        ImmutableOpenMap.Builder<String, DiskUsage> leastAvailableUsages = ImmutableOpenMap.builder();
        leastAvailableUsages.put("node_0", new DiskUsage("node_0", "node_0", "/node0/least", 100, 10)); // 90% used
        leastAvailableUsages.put("node_1", new DiskUsage("node_1", "node_1", "/node1/least", 100, 9)); // 91% used

        ImmutableOpenMap.Builder<String, DiskUsage> mostAvailableUsage = ImmutableOpenMap.builder();
        mostAvailableUsage.put("node_0", new DiskUsage("node_0", "node_0", "/node0/most", 100, 90)); // 10% used
        mostAvailableUsage.put("node_1", new DiskUsage("node_1", "node_1", "/node1/most", 100, 90)); // 10% used

        ImmutableOpenMap.Builder<String, Long> shardSizes = ImmutableOpenMap.builder();
        shardSizes.put("[_na_/test][0][p]", 10L); // 10 bytes
        shardSizes.put("[_na_/test][1][p]", 10L);
        shardSizes.put("[_na_/test][2][p]", 10L);

        final ClusterInfo clusterInfo = new ClusterInfo(leastAvailableUsages.build(), mostAvailableUsage.build(),
            shardSizes.build(), shardRoutingMap.build(), ImmutableOpenMap.of());
        RoutingAllocation allocation = new RoutingAllocation(new AllocationDeciders(Collections.singleton(decider)),
            clusterState.getRoutingNodes(), clusterState, clusterInfo, null, System.nanoTime());
        allocation.debugDecision(true);
        Decision decision = decider.canRemain(test_0, new RoutingNode("node_0", node_0), allocation);
        assertThat(decision.type()).isEqualTo(Decision.Type.YES);
        assertThat(decision.getExplanation()).contains(
            "there is enough disk on this node for the shard to remain, free: [10b]");
        decision = decider.canRemain(test_1, new RoutingNode("node_1", node_1), allocation);
        assertThat(decision.type()).isEqualTo(Decision.Type.NO);
        assertThat(decision.getExplanation()).contains(
            "the shard cannot remain on this node because it is " +
            "above the high watermark cluster setting [cluster.routing.allocation.disk.watermark.high=90%] and there is less than " +
            "the required [10.0%] free disk on node, actual free: [9.0%]");
        try {
            decider.canRemain(test_0, new RoutingNode("node_1", node_1), allocation);
            fail("not allocated on this node");
        } catch (IllegalArgumentException ex) {
            // not allocated on that node
        }
        try {
            decider.canRemain(test_1, new RoutingNode("node_0", node_0), allocation);
            fail("not allocated on this node");
        } catch (IllegalArgumentException ex) {
            // not allocated on that node
        }

        decision = decider.canRemain(test_2, new RoutingNode("node_1", node_1), allocation);
        assertThat(decision.type()).as("can stay since allocated on a different path with enough space").isEqualTo(Decision.Type.YES);
        assertThat(decision.getExplanation()).contains(
            "this shard is not allocated on the most utilized disk and can remain");

        decision = decider.canRemain(test_2, new RoutingNode("node_1", node_1), allocation);
        assertThat(decision.type()).as("can stay since we don't have information about this shard").isEqualTo(Decision.Type.YES);
        assertThat(decision.getExplanation()).contains(
            "this shard is not allocated on the most utilized disk and can remain");
    }

    @Test
    public void testShardSizeAndRelocatingSize() {
        ImmutableOpenMap.Builder<String, Long> shardSizes = ImmutableOpenMap.builder();
        shardSizes.put("[test/1234][0][r]", 10L);
        shardSizes.put("[test/1234][1][r]", 100L);
        shardSizes.put("[test/1234][2][r]", 1000L);
        shardSizes.put("[other/5678][0][p]", 10000L);
        ClusterInfo info = new DevNullClusterInfo(ImmutableOpenMap.of(), ImmutableOpenMap.of(), shardSizes.build());
        Metadata.Builder metaBuilder = Metadata.builder();
        metaBuilder.put(IndexMetadata.builder("1234")
            .settings(settings(Version.CURRENT)
                .put("index.uuid", "1234"))
            .indexName("test")
            .numberOfShards(3)
            .numberOfReplicas(1));
        metaBuilder.put(IndexMetadata.builder("5678")
            .settings(settings(Version.CURRENT)
                .put("index.uuid", "5678"))
            .indexName("other")
            .numberOfShards(1)
            .numberOfReplicas(1));
        Metadata metadata = metaBuilder.build();
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        routingTableBuilder.addAsNew(metadata.index("1234"));
        routingTableBuilder.addAsNew(metadata.index("5678"));
        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(routingTableBuilder.build()).build();
        RoutingAllocation allocation = new RoutingAllocation(null, null, clusterState, info, null, 0);

        final Index index = new Index("test", "1234");
        ShardRouting test_0 = ShardRouting.newUnassigned(new ShardId(index, 0), false, PeerRecoverySource.INSTANCE,
                                                         new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_0 = ShardRoutingHelper.initialize(test_0, "node1");
        test_0 = ShardRoutingHelper.moveToStarted(test_0);
        test_0 = ShardRoutingHelper.relocate(test_0, "node2");

        ShardRouting test_1 = ShardRouting.newUnassigned(new ShardId(index, 1), false, PeerRecoverySource.INSTANCE,
                                                         new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_1 = ShardRoutingHelper.initialize(test_1, "node2");
        test_1 = ShardRoutingHelper.moveToStarted(test_1);
        test_1 = ShardRoutingHelper.relocate(test_1, "node1");

        ShardRouting test_2 = ShardRouting.newUnassigned(new ShardId(index, 2), false, PeerRecoverySource.INSTANCE,
                                                         new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_2 = ShardRoutingHelper.initialize(test_2, "node1");
        test_2 = ShardRoutingHelper.moveToStarted(test_2);

        assertThat(getExpectedShardSize(test_2, 0L, allocation)).isEqualTo(1000L);
        assertThat(getExpectedShardSize(test_1, 0L, allocation)).isEqualTo(100L);
        assertThat(getExpectedShardSize(test_0, 0L, allocation)).isEqualTo(10L);

        RoutingNode node = new RoutingNode("node1", new DiscoveryNode("node1", buildNewFakeTransportAddress(),
                emptyMap(), emptySet(), Version.CURRENT), test_0, test_1.getTargetRelocatingShard(), test_2);
        assertThat(sizeOfRelocatingShards(allocation, node, false, "/dev/null")).isEqualTo(100L);
        assertThat(sizeOfRelocatingShards(allocation, node, true, "/dev/null")).isEqualTo(90L);
        assertThat(sizeOfRelocatingShards(allocation, node, true, "/dev/some/other/dev")).isEqualTo(0L);
        assertThat(sizeOfRelocatingShards(allocation, node, true, "/dev/some/other/dev")).isEqualTo(0L);

        ShardRouting test_3 = ShardRouting.newUnassigned(new ShardId(index, 3), false, PeerRecoverySource.INSTANCE,
                                                         new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_3 = ShardRoutingHelper.initialize(test_3, "node1");
        test_3 = ShardRoutingHelper.moveToStarted(test_3);
        assertThat(getExpectedShardSize(test_3, 0L, allocation)).isEqualTo(0L);


        ShardRouting other_0 = ShardRouting.newUnassigned(new ShardId("other", "5678", 0), randomBoolean(),
                                                          PeerRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        other_0 = ShardRoutingHelper.initialize(other_0, "node2");
        other_0 = ShardRoutingHelper.moveToStarted(other_0);
        other_0 = ShardRoutingHelper.relocate(other_0, "node1");

        node = new RoutingNode("node1", new DiscoveryNode("node1", buildNewFakeTransportAddress(), emptyMap(), emptySet(),
                                                          Version.CURRENT), test_0, test_1.getTargetRelocatingShard(), test_2, other_0.getTargetRelocatingShard());
        if (other_0.primary()) {
            assertThat(sizeOfRelocatingShards(allocation, node, false, "/dev/null")).isEqualTo(10100L);
            assertThat(sizeOfRelocatingShards(allocation, node, true, "/dev/null")).isEqualTo(10090L);
        } else {
            assertThat(sizeOfRelocatingShards(allocation, node, false, "/dev/null")).isEqualTo(100L);
            assertThat(sizeOfRelocatingShards(allocation, node, true, "/dev/null")).isEqualTo(90L);
        }
    }

    private long getExpectedShardSize(ShardRouting shardRouting, long defaultSize, RoutingAllocation allocation) {
        return DiskThresholdDecider.getExpectedShardSize(
            shardRouting,
            defaultSize,
            allocation.clusterInfo(),
            allocation.snapshotShardSizeInfo(),
            allocation.metadata(),
            allocation.routingTable()
        );
    }

    private long sizeOfRelocatingShards(RoutingAllocation allocation,
                                        RoutingNode node,
                                        boolean subtractShardsMovingAway,
                                        String dataPath) {
        return DiskThresholdDecider.sizeOfRelocatingShards(
            node,
            subtractShardsMovingAway,
            dataPath,
            allocation.clusterInfo(),
            allocation.metadata(),
            allocation.routingTable()
        );
    }

    @Test
    public void testSizeShrinkIndex() {
        ImmutableOpenMap.Builder<String, Long> shardSizes = ImmutableOpenMap.builder();
        shardSizes.put("[test/1234][0][p]", 10L);
        shardSizes.put("[test/1234][1][p]", 100L);
        shardSizes.put("[test/1234][2][p]", 500L);
        shardSizes.put("[test/1234][3][p]", 500L);

        final Index index = new Index("test", "1234");

        ClusterInfo info = new DevNullClusterInfo(ImmutableOpenMap.of(), ImmutableOpenMap.of(), shardSizes.build());

        // Setting the creation date is important here as shards of newer indices are allocated first, see PriorityComparator
        // Shards of the source index must be allocated first, so set the highest creation date
        Metadata.Builder metaBuilder = Metadata.builder();
        metaBuilder.put(IndexMetadata.builder(index.getUUID())
            .settings(settings(Version.CURRENT)
                .put("index.uuid", index.getUUID())
                .put(IndexMetadata.SETTING_CREATION_DATE, 3))
            .indexName(index.getName())
            .numberOfShards(4)
            .numberOfReplicas(0));
        metaBuilder.put(IndexMetadata.builder("5678")
            .settings(settings(Version.CURRENT)
                .put("index.uuid", "5678")
                .put(IndexMetadata.INDEX_RESIZE_SOURCE_NAME_KEY, "test")
                .put(IndexMetadata.INDEX_RESIZE_SOURCE_UUID_KEY, "1234")
                .put(IndexMetadata.SETTING_CREATION_DATE, 2))
            .indexName("target")
            .numberOfShards(1)
            .numberOfReplicas(0));
        metaBuilder.put(IndexMetadata.builder("9101112")
            .settings(settings(Version.CURRENT).put("index.uuid", "9101112")
                .put(IndexMetadata.INDEX_RESIZE_SOURCE_NAME_KEY, "test")
                .put(IndexMetadata.INDEX_RESIZE_SOURCE_UUID_KEY, index.getUUID())
                .put(IndexMetadata.SETTING_CREATION_DATE, 1))
            .indexName("target2")
            .numberOfShards(2)
            .numberOfReplicas(0));
        Metadata metadata = metaBuilder.build();
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        routingTableBuilder.addAsNew(metadata.index(index.getUUID()));
        routingTableBuilder.addAsNew(metadata.index("5678"));
        routingTableBuilder.addAsNew(metadata.index("9101112"));
        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING
                                                             .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(routingTableBuilder.build()).build();

        AllocationService allocationService = createAllocationService();
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder().add(newNode("node1")))
            .build();
        clusterState = allocationService.reroute(clusterState, "foo");

        clusterState = startShardsAndReroute(allocationService, clusterState,
            clusterState.routingTable().index(index.getUUID()).shardsWithState(ShardRoutingState.UNASSIGNED));

        RoutingAllocation allocation = new RoutingAllocation(null, clusterState.getRoutingNodes(), clusterState, info, null,0);

        ShardRouting test_0 = ShardRouting.newUnassigned(new ShardId(index, 0), true,
                                                         LocalShardsRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_0 = ShardRoutingHelper.initialize(test_0, "node1");
        test_0 = ShardRoutingHelper.moveToStarted(test_0);

        ShardRouting test_1 = ShardRouting.newUnassigned(new ShardId(index, 1), true,
                                                         LocalShardsRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_1 = ShardRoutingHelper.initialize(test_1, "node2");
        test_1 = ShardRoutingHelper.moveToStarted(test_1);

        ShardRouting test_2 = ShardRouting.newUnassigned(new ShardId(index, 2), true,
                                                         LocalShardsRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_2 = ShardRoutingHelper.initialize(test_2, "node1");

        ShardRouting test_3 = ShardRouting.newUnassigned(new ShardId(index, 3), true,
                                                         LocalShardsRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        test_3 = ShardRoutingHelper.initialize(test_3, "node1");
        assertThat(getExpectedShardSize(test_3, 0L, allocation)).isEqualTo(500L);
        assertThat(getExpectedShardSize(test_2, 0L, allocation)).isEqualTo(500L);
        assertThat(getExpectedShardSize(test_1, 0L, allocation)).isEqualTo(100L);
        assertThat(getExpectedShardSize(test_0, 0L, allocation)).isEqualTo(10L);

        ShardRouting target = ShardRouting.newUnassigned(new ShardId(new Index("target", "5678"), 0),
            true, LocalShardsRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        assertThat(getExpectedShardSize(target, 0L, allocation)).isEqualTo(1110L);

        ShardRouting target2 = ShardRouting.newUnassigned(new ShardId(new Index("target2", "9101112"), 0),
            true, LocalShardsRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        assertThat(getExpectedShardSize(target2, 0L, allocation)).isEqualTo(110L);

        target2 = ShardRouting.newUnassigned(new ShardId(new Index("target2", "9101112"), 1),
            true, LocalShardsRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "foo"));
        assertThat(getExpectedShardSize(target2, 0L, allocation)).isEqualTo(1000L);

        // check that the DiskThresholdDecider still works even if the source index has been deleted
        ClusterState clusterStateWithMissingSourceIndex = ClusterState.builder(clusterState)
            .metadata(Metadata.builder(metadata).remove(index.getUUID()))
            .routingTable(RoutingTable.builder(clusterState.routingTable()).remove(index.getUUID()).build())
            .build();

        allocationService.reroute(clusterState, "foo");
        RoutingAllocation allocationWithMissingSourceIndex = new RoutingAllocation(null,
            clusterStateWithMissingSourceIndex.getRoutingNodes(), clusterStateWithMissingSourceIndex, info, null,0);
        assertThat(getExpectedShardSize(target, 42L, allocationWithMissingSourceIndex)).isEqualTo(42L);
        assertThat(getExpectedShardSize(target2, 42L, allocationWithMissingSourceIndex)).isEqualTo(42L);
    }
}
