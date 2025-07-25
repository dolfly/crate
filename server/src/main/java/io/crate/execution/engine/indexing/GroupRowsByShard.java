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

package io.crate.execution.engine.indexing;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.TriFunction;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.index.IndexNotFoundException;
import org.jetbrains.annotations.Nullable;

import io.crate.data.Input;
import io.crate.data.Row;
import io.crate.data.UnsafeArrayRow;
import io.crate.execution.dml.IndexItem;
import io.crate.execution.dml.ShardRequest;
import io.crate.execution.engine.collect.CollectExpression;
import io.crate.execution.engine.collect.RowShardResolver;
import io.crate.metadata.PartitionName;

public final class GroupRowsByShard<TReq extends ShardRequest<TReq, TItem>, TItem extends ShardRequest.Item>
    implements TriFunction<ShardedRequests<TReq, TItem>, Row, Boolean, TItem>,
               BiConsumer<ShardedRequests<TReq, TItem>, Row> {

    private static final Logger LOGGER = LogManager.getLogger(GroupRowsByShard.class);

    private final RowShardResolver rowShardResolver;
    private final List<? extends CollectExpression<Row, ?>> expressions;
    private final List<? extends CollectExpression<Row, ?>> sourceInfoExpressions;
    private final ItemFactory<TItem> itemFactory;
    private final Supplier<String> indexNameResolver;
    private final Supplier<String> indexUUIDResolver;
    private final ClusterService clusterService;
    private final boolean autoCreateIndices;
    private final BiConsumer<ShardedRequests, String> itemFailureRecorder;
    private final Predicate<ShardedRequests> hasSourceFailure;
    private final Input<String> sourceUriInput;
    private final Input<Long> lineNumberInput;
    private final UnsafeArrayRow spareRow = new UnsafeArrayRow();
    private Object[] spareCells;

    private final BiConsumer<String, IndexItem> constraintsChecker;

    public GroupRowsByShard(ClusterService clusterService,
                            BiConsumer<String, IndexItem> constraintsChecker,
                            RowShardResolver rowShardResolver,
                            Supplier<String> indexNameResolver,
                            Supplier<String> indexUUIDResolver,
                            List<? extends CollectExpression<Row, ?>> expressions,
                            ItemFactory<TItem> itemFactory,
                            boolean autoCreateIndices,
                            UpsertResultContext upsertContext) {
        assert expressions instanceof RandomAccess
            : "expressions should be a RandomAccess list for zero allocation iterations";

        this.clusterService = clusterService;
        this.constraintsChecker = constraintsChecker;
        this.rowShardResolver = rowShardResolver;
        this.indexNameResolver = indexNameResolver;
        this.indexUUIDResolver = indexUUIDResolver;
        this.expressions = expressions;
        this.sourceInfoExpressions = upsertContext.getSourceInfoExpressions();
        this.itemFactory = itemFactory;
        this.itemFailureRecorder = upsertContext.getItemFailureRecorder();
        this.hasSourceFailure = upsertContext.getHasSourceFailureChecker();
        this.sourceUriInput = upsertContext.getSourceUriInput();
        this.lineNumberInput = upsertContext.getLineNumberInput();
        this.autoCreateIndices = autoCreateIndices;
    }

    public GroupRowsByShard(ClusterService clusterService,
                            BiConsumer<String, IndexItem> constraintsChecker,
                            RowShardResolver rowShardResolver,
                            Supplier<String> indexNameResolver,
                            Supplier<String> indexUUIDResolver,
                            List<? extends CollectExpression<Row, ?>> expressions,
                            ItemFactory<TItem> itemFactory,
                            boolean autoCreateIndices) {
        this(clusterService,
            constraintsChecker,
            rowShardResolver,
            indexNameResolver,
            indexUUIDResolver,
            expressions,
            itemFactory,
            autoCreateIndices,
            UpsertResultContext.forRowCount());
    }

    /**
     * BiConsumer is needed for compatibility of the grouper with BatchIterators.partition
     */
    @Override
    public void accept(ShardedRequests<TReq, TItem> shardedRequests, Row row) {
        apply(shardedRequests, row, false);
    }

    @Override
    public TItem apply(ShardedRequests<TReq, TItem> shardedRequests, Row row, Boolean propagateError) {
        // `Row` can be a `InputRow` which may be backed by expressions which have expensive `.value()` implementations
        // The code below (RowShardResolver.setNextRow, and estimateRowSize)
        // would lead to multiple `.value()` calls on the same underlying instance
        // To prevent that, this uses a spare row to trigger eager evaluation and copy/link the values
        if (spareCells == null) {
            // all rows must have the same amount of columns
            spareCells = new Object[row.numColumns()];
            spareRow.cells(spareCells);
        }

        Throwable err = null;
        for (int c = 0; c < row.numColumns(); c++) {
            // itemFailureRecorder.accept(shardedRequests, err.getMessage());
            // uses information from the `sourceInfoExpressions`, must make sure that
            // the cells for them get set
            try {
                spareCells[c] = row.get(c);
            } catch (Throwable t) {
                err = t;
            }
        }
        for (int i = 0; i < sourceInfoExpressions.size(); i++) {
            sourceInfoExpressions.get(i).setNextRow(spareRow);
        }
        if (hasSourceFailure.test(shardedRequests)) {
            // source uri failed processing (reading)
            return null;
        }
        if (err != null) {
            itemFailureRecorder.accept(shardedRequests, err.getMessage());
            return null;
        }
        try {
            rowShardResolver.setNextRow(spareRow);
            for (int i = 0; i < expressions.size(); i++) {
                expressions.get(i).setNextRow(spareRow);
            }
            String id = rowShardResolver.id();
            TItem item = itemFactory.create(id, rowShardResolver.pkValues(), rowShardResolver.autoGeneratedTimestamp());
            String indexUUID = indexUUIDResolver.get();
            String routing = rowShardResolver.routing();
            String sourceUri = sourceUriInput.value();
            Long lineNumber = lineNumberInput.value();

            RowSourceInfo rowSourceInfo = RowSourceInfo.emptyMarkerOrNewInstance(sourceUri, lineNumber);
            ShardLocation shardLocation = getShardLocation(indexUUID, id, routing);
            if (shardLocation == null) {
                String indexName = indexNameResolver.get();
                // Validation is done before creating an index in order to ensure
                // that no "bad partitions" will be left behind in case of validation failure.
                if (item instanceof IndexItem indexItem) {
                    constraintsChecker.accept(indexUUID, indexItem);
                }
                shardedRequests.add(item, indexName, routing, rowSourceInfo);
            } else {
                shardedRequests.add(item, shardLocation, rowSourceInfo);
            }
            return item;
        } catch (CircuitBreakingException e) {
            throw e;
        } catch (Throwable t) {
            if (propagateError) {
                throw t;
            }
            itemFailureRecorder.accept(shardedRequests, t.getMessage());
            return null;
        }
    }

    @Nullable
    private ShardLocation getShardLocation(String indexUUID, String id, @Nullable String routing) {
        try {
            ShardIterator shardIterator = clusterService.operationRouting().indexShards(
                clusterService.state(),
                indexUUID,
                id,
                routing
            );

            final String nodeId;
            ShardRouting shardRouting = shardIterator.nextOrNull();
            if (shardRouting == null) {
                nodeId = null;
            } else if (shardRouting.active() == false) {
                nodeId = shardRouting.relocatingNodeId();
            } else {
                nodeId = shardRouting.currentNodeId();
            }
            if (nodeId == null && LOGGER.isDebugEnabled()) {
                LOGGER.debug("Unable to get the node id for index {} and shard {}", indexUUID, id);
            }
            return new ShardLocation(shardIterator.shardId(), nodeId);
        } catch (IndexNotFoundException e) {
            if (!autoCreateIndices) {
                throw e;
            }
            return null;
        }
    }

    /**
     * @throws IllegalStateException if a shardLocation still can't be resolved
     */
    void reResolveShardLocations(ShardedRequests<TReq, TItem> requests) {
        Iterator<Map.Entry<PartitionName, List<ShardedRequests.ItemAndRoutingAndSourceInfo<TItem>>>> entryIt =
            requests.itemsByMissingPartition.entrySet().iterator();
        Metadata metadata = clusterService.state().metadata();
        while (entryIt.hasNext()) {
            Map.Entry<PartitionName, List<ShardedRequests.ItemAndRoutingAndSourceInfo<TItem>>> e = entryIt.next();
            List<ShardedRequests.ItemAndRoutingAndSourceInfo<TItem>> items = e.getValue();
            Iterator<ShardedRequests.ItemAndRoutingAndSourceInfo<TItem>> it = items.iterator();
            while (it.hasNext()) {
                ShardedRequests.ItemAndRoutingAndSourceInfo<TItem> itemAndRoutingAndSourceInfo = it.next();
                PartitionName partitionName = e.getKey();
                String indexUUID = metadata.getIndices(partitionName.relationName(), partitionName.values(), true, IndexMetadata::getIndexUUID)
                    .getFirst();
                ShardLocation shardLocation =
                    getShardLocation(indexUUID, itemAndRoutingAndSourceInfo.item.id(), itemAndRoutingAndSourceInfo.routing);
                if (shardLocation == null) {
                    throw new IllegalStateException("shardLocation not resolvable after createIndices");
                }
                requests.add(itemAndRoutingAndSourceInfo.item, shardLocation, itemAndRoutingAndSourceInfo.rowSourceInfo);
                it.remove();
            }
            if (items.isEmpty()) {
                entryIt.remove();
            }
        }
    }
}
