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

package io.crate.execution.jobs.transport;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.profile.query.QueryProfiler;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import io.crate.common.concurrent.CompletableFutures;
import io.crate.execution.dsl.phases.NodeOperation;
import io.crate.execution.engine.distribution.StreamBucket;
import io.crate.execution.jobs.InstrumentedIndexSearcher;
import io.crate.execution.jobs.JobSetup;
import io.crate.execution.jobs.RootTask;
import io.crate.execution.jobs.SharedShardContexts;
import io.crate.execution.jobs.TasksService;
import io.crate.execution.support.NodeActionRequestHandler;
import io.crate.execution.support.NodeRequest;
import io.crate.execution.support.Transports;
import io.crate.metadata.IndexUUID;
import io.crate.metadata.upgrade.NodeOperationsUpgrader;
import io.crate.profile.ProfilingContext;

@Singleton
public class TransportJobAction extends TransportAction<NodeRequest<JobRequest>, JobResponse> {

    private final IndicesService indicesService;
    private final Transports transports;
    private final TasksService tasksService;
    private final JobSetup jobSetup;
    private final ClusterService clusterService;

    @Inject
    public TransportJobAction(TransportService transportService,
                              IndicesService indicesService,
                              Transports transports,
                              TasksService tasksService,
                              JobSetup jobSetup,
                              ClusterService clusterService) {
        super(JobAction.NAME);
        this.indicesService = indicesService;
        this.transports = transports;
        this.tasksService = tasksService;
        this.jobSetup = jobSetup;
        this.clusterService = clusterService;
        transportService.registerRequestHandler(
            JobAction.NAME,
            ThreadPool.Names.SEARCH,
            JobRequest::new,
            new NodeActionRequestHandler<>(this::nodeOperation));
    }

    @Override
    public void doExecute(NodeRequest<JobRequest> request, ActionListener<JobResponse> listener) {
        final String nodeId = request.nodeId();
        ClusterState clusterState = clusterService.state();
        DiscoveryNode receiverNode = clusterState.nodes().get(nodeId);
        if (receiverNode == null) {
            listener.onFailure(new IllegalArgumentException("Unknown node: " + nodeId));
            return;
        }
        Version receiverVersion = receiverNode.getVersion();
        // The upgrade does a version check as well, but we do it here to avoid re-creating the request if not needed
        if (receiverVersion.before(IndexUUID.INDICES_RESOLVED_BY_UUID_VERSION)) {
            JobRequest jobRequest = request.innerRequest();
            request = JobRequest.of(
                nodeId,
                jobRequest.jobId(),
                jobRequest.sessionSettings(),
                jobRequest.coordinatorNodeId(),
                NodeOperationsUpgrader.downgrade(
                    jobRequest.nodeOperations(),
                    receiverVersion,
                    clusterState.metadata()
                ),
                jobRequest.enableProfiling()
            );
        }

        transports.sendRequest(
            JobAction.NAME,
            nodeId,
            request.innerRequest(),
            listener,
            new ActionListenerResponseHandler<>(JobAction.NAME, listener, JobResponse::new)
        );
    }

    private CompletableFuture<JobResponse> nodeOperation(final JobRequest request) {
        RootTask.Builder contextBuilder = tasksService.newBuilder(
            request.jobId(),
            request.sessionSettings().userName(),
            request.coordinatorNodeId(),
            Collections.emptySet()
        );

        SharedShardContexts sharedShardContexts = maybeInstrumentProfiler(request.enableProfiling(), contextBuilder);

        Collection<? extends NodeOperation> nodeOperations = NodeOperationsUpgrader.upgrade(
            request.nodeOperations(),
            request.senderVersion(),
            clusterService.state().metadata()
        );

        List<CompletableFuture<StreamBucket>> directResponseFutures = jobSetup.prepareOnRemote(
            request.sessionSettings(),
            nodeOperations,
            contextBuilder,
            sharedShardContexts
        );

        CompletableFuture<Void> startFuture;
        try {
            RootTask context = tasksService.createTask(contextBuilder);
            startFuture = context.start();
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }

        if (directResponseFutures.size() == 0) {
            return startFuture.thenApply(ignored -> new JobResponse(List.of()));
        } else {
            return startFuture.thenCompose(ignored -> CompletableFutures.allAsList(directResponseFutures)).thenApply(JobResponse::new);
        }
    }

    private SharedShardContexts maybeInstrumentProfiler(boolean enableProfiling, RootTask.Builder contextBuilder) {
        if (enableProfiling) {
            var profilers = new HashMap<ShardId, QueryProfiler>();
            ProfilingContext profilingContext = new ProfilingContext(profilers, clusterService.state());
            contextBuilder.profilingContext(profilingContext);

            return new SharedShardContexts(
                indicesService,
                (shardId, indexSearcher) -> {
                    var queryProfiler = new QueryProfiler();
                    profilers.put(shardId, queryProfiler);
                    return new InstrumentedIndexSearcher(indexSearcher, queryProfiler);
                }
            );
        } else {
            return new SharedShardContexts(indicesService, (ignored, indexSearcher) -> indexSearcher);
        }
    }
}
