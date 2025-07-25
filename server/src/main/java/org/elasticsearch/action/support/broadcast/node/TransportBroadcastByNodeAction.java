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

package org.elasticsearch.action.support.broadcast.node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.NoShardAvailableActionException;
import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.broadcast.BroadcastRequest;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.action.support.broadcast.BroadcastShardOperationFailedException;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardsIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.NodeShouldNotConnectException;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import io.crate.exceptions.SQLExceptions;

/**
 * Abstraction for transporting aggregated shard-level operations in a single request (NodeRequest) per-node
 * and executing the shard-level operations serially on the receiving node. Each shard-level operation can produce a
 * result (ShardOperationResult), these per-node shard-level results are aggregated into a single result
 * (BroadcastByNodeResponse) to the coordinating node. These per-node results are aggregated into a single result (Result)
 * to the client.
 *
 * @param <Request>              the underlying client request
 * @param <Response>             the response to the client request
 * @param <ShardOperationResult> per-shard operation results
 */
public abstract class TransportBroadcastByNodeAction<Request extends BroadcastRequest,
        Response extends BroadcastResponse,
        ShardOperationResult extends Writeable> extends HandledTransportAction<Request, Response> {

    private final ClusterService clusterService;
    private final TransportService transportService;

    private final String transportNodeBroadcastAction;

    public TransportBroadcastByNodeAction(
            String actionName,
            ClusterService clusterService,
            TransportService transportService,
            Writeable.Reader<Request> reader,
            String executor,
            boolean canTripCircuitBreaker) {
        super(actionName, canTripCircuitBreaker, transportService, reader);

        this.clusterService = clusterService;
        this.transportService = transportService;

        transportNodeBroadcastAction = actionName + "[n]";

        transportService.registerRequestHandler(
            transportNodeBroadcastAction,
            executor,
            false,
            canTripCircuitBreaker,
            NodeRequest::new,
            new BroadcastByNodeTransportRequestHandler()
        );
    }

    private Response newResponse(
            Request request,
            AtomicReferenceArray<Object> responses,
            List<NoShardAvailableActionException> unavailableShardExceptions,
            Map<String, List<ShardRouting>> nodes,
            ClusterState clusterState) {
        int totalShards = 0;
        int successfulShards = 0;
        List<ShardOperationResult> broadcastByNodeResponses = new ArrayList<>();
        List<DefaultShardOperationFailedException> exceptions = new ArrayList<>();
        for (int i = 0; i < responses.length(); i++) {
            if (responses.get(i) instanceof FailedNodeException exception) {
                totalShards += nodes.get(exception.nodeId()).size();
                for (ShardRouting shard : nodes.get(exception.nodeId())) {
                    exceptions.add(new DefaultShardOperationFailedException(shard.index().toString(), shard.getId(), exception));
                }
            } else {
                NodeResponse response = (NodeResponse) responses.get(i);
                broadcastByNodeResponses.addAll(response.results);
                totalShards += response.getTotalShards();
                successfulShards += response.getSuccessfulShards();
                for (BroadcastShardOperationFailedException throwable : response.getExceptions()) {
                    if (!SQLExceptions.isShardNotAvailable(throwable)) {
                        exceptions.add(new DefaultShardOperationFailedException(throwable.getShardId().getIndex().toString(), throwable.getShardId().id(), throwable));
                    }
                }
            }
        }
        totalShards += unavailableShardExceptions.size();
        int failedShards = exceptions.size();
        return newResponse(request, totalShards, successfulShards, failedShards, broadcastByNodeResponses, exceptions, clusterState);
    }

    /**
     * Deserialize a shard-level result from an input stream
     *
     * @param in input stream
     * @return a deserialized shard-level result
     */
    protected abstract ShardOperationResult readShardResult(StreamInput in) throws IOException;

    /**
     * Creates a new response to the underlying request.
     *
     * @param request          the underlying request
     * @param totalShards      the total number of shards considered for execution of the operation
     * @param successfulShards the total number of shards for which execution of the operation was successful
     * @param failedShards     the total number of shards for which execution of the operation failed
     * @param results          the per-node aggregated shard-level results
     * @param shardFailures    the exceptions corresponding to shard operation failures
     * @param clusterState     the cluster state
     * @return the response
     */
    protected abstract Response newResponse(Request request, int totalShards, int successfulShards, int failedShards, List<ShardOperationResult> results, List<DefaultShardOperationFailedException> shardFailures, ClusterState clusterState);

    /**
     * Deserialize a request from an input stream
     *
     * @param in input stream
     * @return a de-serialized request
     */
    protected abstract Request readRequestFrom(StreamInput in) throws IOException;

    /**
     * Executes the shard-level operation. This method is called once per shard serially on the receiving node.
     *
     * @param request      the node-level request
     * @param shardRouting the shard on which to execute the operation
     * @param listener     a listener to receive the shard-level result of the operation
     */
    protected abstract void shardOperation(Request request, ShardRouting shardRouting, ActionListener<ShardOperationResult> listener) throws IOException;

    /**
     * Determines the shards on which this operation will be executed on. The operation is executed once per shard.
     *
     * @param clusterState    the cluster state
     * @param request         the underlying request
     * @param concreteIndices the concrete indices on which to execute the operation
     * @return the shards on which to execute the operation
     */
    protected abstract ShardsIterator shards(ClusterState clusterState, Request request, String[] concreteIndices);

    /**
     * Executes a global block check before polling the cluster state.
     *
     * @param state   the cluster state
     * @param request the underlying request
     * @return a non-null exception if the operation is blocked
     */
    protected abstract ClusterBlockException checkGlobalBlock(ClusterState state, Request request);

    /**
     * Executes a global request-level check before polling the cluster state.
     *
     * @param state           the cluster state
     * @param request         the underlying request
     * @param concreteIndices the concrete indices on which to execute the operation
     * @return a non-null exception if the operation if blocked
     */
    protected abstract ClusterBlockException checkRequestBlock(ClusterState state, Request request, String[] concreteIndices);

    @Override
    protected void doExecute(Request request, ActionListener<Response> listener) {
        new AsyncAction(request, listener).start();
    }

    protected class AsyncAction {
        private final Request request;
        private final ActionListener<Response> listener;
        private final ClusterState clusterState;
        private final DiscoveryNodes nodes;
        private final Map<String, List<ShardRouting>> nodeIds;
        private final AtomicReferenceArray<Object> responses;
        private final AtomicInteger counter = new AtomicInteger();
        private final List<NoShardAvailableActionException> unavailableShardExceptions = new ArrayList<>();

        protected AsyncAction(Request request, ActionListener<Response> listener) {
            this.request = request;
            this.listener = listener;

            clusterState = clusterService.state();
            nodes = clusterState.nodes();

            ClusterBlockException globalBlockException = checkGlobalBlock(clusterState, request);
            if (globalBlockException != null) {
                throw globalBlockException;
            }

            String[] concreteIndices
                = clusterState.metadata().getIndices(request.partitions(), false, im -> im.getIndex().getUUID()).toArray(String[]::new);
            ClusterBlockException requestBlockException = checkRequestBlock(clusterState, request, concreteIndices);
            if (requestBlockException != null) {
                throw requestBlockException;
            }

            if (logger.isTraceEnabled()) {
                logger.trace("resolving shards for [{}] based on cluster state version [{}]", actionName, clusterState.version());
            }
            ShardsIterator shardIt = shards(clusterState, request, concreteIndices);
            nodeIds = new HashMap<>();

            for (ShardRouting shard : shardIt) {
                // send a request to the shard only if it is assigned to a node that is in the local node's cluster state
                // a scenario in which a shard can be assigned but to a node that is not in the local node's cluster state
                // is when the shard is assigned to the master node, the local node has detected the master as failed
                // and a new master has not yet been elected; in this situation the local node will have removed the
                // master node from the local cluster state, but the shards assigned to the master will still be in the
                // routing table as such
                if (shard.assignedToNode() && nodes.get(shard.currentNodeId()) != null) {
                    String nodeId = shard.currentNodeId();
                    if (!nodeIds.containsKey(nodeId)) {
                        nodeIds.put(nodeId, new ArrayList<>());
                    }
                    nodeIds.get(nodeId).add(shard);
                } else {
                    unavailableShardExceptions.add(
                            new NoShardAvailableActionException(
                                    shard.shardId(),
                                    " no shards available for shard " + shard.toString() + " while executing " + actionName
                            )
                    );
                }
            }

            responses = new AtomicReferenceArray<>(nodeIds.size());
        }

        public void start() {
            if (nodeIds.isEmpty()) {
                try {
                    onCompletion();
                } catch (Exception e) {
                    listener.onFailure(e);
                }
            } else {
                int nodeIndex = -1;
                for (Map.Entry<String, List<ShardRouting>> entry : nodeIds.entrySet()) {
                    nodeIndex++;
                    DiscoveryNode node = nodes.get(entry.getKey());
                    sendNodeRequest(node, entry.getValue(), nodeIndex);
                }
            }
        }

        private void sendNodeRequest(final DiscoveryNode node, List<ShardRouting> shards, final int nodeIndex) {
            try {
                NodeRequest nodeRequest = new NodeRequest(node.getId(), request, shards);
                transportService.sendRequest(node, transportNodeBroadcastAction, nodeRequest, new TransportResponseHandler<NodeResponse>() {

                    @Override
                    public NodeResponse read(StreamInput in) throws IOException {
                        return new NodeResponse(in);
                    }

                    @Override
                    public void handleResponse(NodeResponse response) {
                        onNodeResponse(node, nodeIndex, response);
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        onNodeFailure(node, nodeIndex, exp);
                    }

                    @Override
                    public String executor() {
                        return ThreadPool.Names.SAME;
                    }
                });
            } catch (Exception e) {
                onNodeFailure(node, nodeIndex, e);
            }
        }

        private void onNodeResponse(DiscoveryNode node, int nodeIndex, NodeResponse response) {
            if (logger.isTraceEnabled()) {
                logger.trace("received response for [{}] from node [{}]", actionName, node.getId());
            }

            // this is defensive to protect against the possibility of double invocation
            // the current implementation of TransportService#sendRequest guards against this
            // but concurrency is hard, safety is important, and the small performance loss here does not matter
            if (responses.compareAndSet(nodeIndex, null, response)) {
                if (counter.incrementAndGet() == responses.length()) {
                    onCompletion();
                }
            }
        }

        protected void onNodeFailure(DiscoveryNode node, int nodeIndex, Throwable t) {
            String nodeId = node.getId();
            if (logger.isDebugEnabled() && !(t instanceof NodeShouldNotConnectException)) {
                logger.debug(new ParameterizedMessage("failed to execute [{}] on node [{}]", actionName, nodeId), t);
            }

            // this is defensive to protect against the possibility of double invocation
            // the current implementation of TransportService#sendRequest guards against this
            // but concurrency is hard, safety is important, and the small performance loss here does not matter
            if (responses.compareAndSet(nodeIndex, null, new FailedNodeException(nodeId, "Failed node [" + nodeId + "]", t))) {
                if (counter.incrementAndGet() == responses.length()) {
                    onCompletion();
                }
            }
        }

        protected void onCompletion() {
            Response response = null;
            try {
                response = newResponse(request, responses, unavailableShardExceptions, nodeIds, clusterState);
            } catch (Exception e) {
                logger.debug("failed to combine responses from nodes", e);
                listener.onFailure(e);
            }
            if (response != null) {
                try {
                    listener.onResponse(response);
                } catch (Exception e) {
                    listener.onFailure(e);
                }
            }
        }
    }

    class BroadcastByNodeTransportRequestHandler implements TransportRequestHandler<NodeRequest> {
        @Override
        public void messageReceived(final NodeRequest request, TransportChannel channel) throws Exception {
            List<ShardRouting> shards = request.getShards();
            final int totalShards = shards.size();
            if (logger.isTraceEnabled()) {
                logger.trace("[{}] executing operation on [{}] shards", actionName, totalShards);
            }
            List<BroadcastShardOperationFailedException> accumulatedExceptions = Collections.synchronizedList(new ArrayList<>());
            List<ShardOperationResult> results = Collections.synchronizedList(new ArrayList<>());

            ActionListener<ShardOperationResult> listener = new ActionListener<ShardOperationResult>() {

                final AtomicInteger counter = new AtomicInteger(totalShards);

                @Override
                public void onResponse(ShardOperationResult shardOperationResult) {
                    results.add(shardOperationResult);
                    if (counter.decrementAndGet() == 0) {
                        finish();
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    accumulatedExceptions.add((BroadcastShardOperationFailedException) e);
                    if (counter.decrementAndGet() == 0) {
                        finish();
                    }
                }

                void finish() {
                    try {
                        channel.sendResponse(new NodeResponse(request.getNodeId(), totalShards, results, accumulatedExceptions));
                    } catch (Exception e) {
                        try {
                            channel.sendResponse(e);
                        } catch (Exception e1) {
                            logger.warn(() -> new ParameterizedMessage(
                                "Failed to send error response for action [{}] and request [{}]", actionName, request), e1);
                        }
                    }
                }
            };

            for (final ShardRouting shardRouting : shards) {
                onShardOperation(request, shardRouting, listener);
            }
        }

        private void onShardOperation(final NodeRequest request, final ShardRouting shardRouting, ActionListener<ShardOperationResult> listener) {
            var wrappedListener = listener.withOnFailure((l, e) ->
                l.onFailure(new BroadcastShardOperationFailedException(shardRouting.shardId(), "operation " + actionName + " failed", e))
            );
            try {
                if (logger.isTraceEnabled()) {
                    logger.trace("[{}]  executing operation for shard [{}]", actionName, shardRouting.shortSummary());
                }
                shardOperation(request.indicesLevelRequest, shardRouting, wrappedListener);
                if (logger.isTraceEnabled()) {
                    logger.trace("[{}]  completed operation for shard [{}]", actionName, shardRouting.shortSummary());
                }
            } catch (Exception e) {
                wrappedListener.onFailure(e);
                if (SQLExceptions.isShardNotAvailable(e)) {
                    if (logger.isTraceEnabled()) {
                        logger.trace(new ParameterizedMessage(
                            "[{}] failed to execute operation for shard [{}]", actionName, shardRouting.shortSummary()), e);
                    }
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug(new ParameterizedMessage(
                            "[{}] failed to execute operation for shard [{}]", actionName, shardRouting.shortSummary()), e);
                    }
                }
            }
        }
    }

    public class NodeRequest extends TransportRequest {

        private final String nodeId;
        private final List<ShardRouting> shards;

        protected Request indicesLevelRequest;

        public NodeRequest(String nodeId, Request request, List<ShardRouting> shards) {
            this.indicesLevelRequest = request;
            this.shards = shards;
            this.nodeId = nodeId;
        }

        public List<ShardRouting> getShards() {
            return shards;
        }

        public String getNodeId() {
            return nodeId;
        }

        public NodeRequest(StreamInput in) throws IOException {
            super(in);
            indicesLevelRequest = readRequestFrom(in);
            shards = in.readList(ShardRouting::new);
            nodeId = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            indicesLevelRequest.writeTo(out);
            out.writeList(shards);
            out.writeString(nodeId);
        }
    }

    class NodeResponse extends TransportResponse {

        private final String nodeId;
        private final int totalShards;
        private final List<BroadcastShardOperationFailedException> exceptions;
        private final List<ShardOperationResult> results;

        NodeResponse(String nodeId,
                            int totalShards,
                            List<ShardOperationResult> results,
                            List<BroadcastShardOperationFailedException> exceptions) {
            this.nodeId = nodeId;
            this.totalShards = totalShards;
            this.results = results;
            this.exceptions = exceptions;
        }

        public String getNodeId() {
            return nodeId;
        }

        public int getTotalShards() {
            return totalShards;
        }

        public int getSuccessfulShards() {
            return results.size();
        }

        public List<BroadcastShardOperationFailedException> getExceptions() {
            return exceptions;
        }

        public NodeResponse(StreamInput in) throws IOException {
            nodeId = in.readString();
            totalShards = in.readVInt();
            results = in.readList((stream) -> stream.readBoolean() ? readShardResult(stream) : null);
            if (in.readBoolean()) {
                exceptions = in.readList(BroadcastShardOperationFailedException::new);
            } else {
                exceptions = null;
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(nodeId);
            out.writeVInt(totalShards);
            out.writeVInt(results.size());
            for (ShardOperationResult result : results) {
                out.writeOptionalWriteable(result);
            }
            out.writeBoolean(exceptions != null);
            if (exceptions != null) {
                out.writeList(exceptions);
            }
        }
    }

    /**
     * Can be used for implementations of {@link #shardOperation(BroadcastRequest, ShardRouting, ActionListener) shardOperation} for
     * which there is no shard-level return value.
     */
    public static final class EmptyResult implements Writeable {

        public static final EmptyResult INSTANCE = new EmptyResult();

        private EmptyResult() {
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
        }
    }
}
