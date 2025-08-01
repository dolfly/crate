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

package io.crate.planner.node.management;

import static io.crate.data.SentinelRow.SENTINEL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.elasticsearch.cluster.metadata.RelationMetadata;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import io.crate.analyze.BoundCopyFrom;
import io.crate.breaker.ConcurrentRamAccounting;
import io.crate.common.collections.MapBuilder;
import io.crate.data.InMemoryBatchIterator;
import io.crate.data.Row;
import io.crate.data.Row1;
import io.crate.data.RowConsumer;
import io.crate.data.RowN;
import io.crate.data.breaker.RamAccounting;
import io.crate.execution.MultiPhaseExecutor;
import io.crate.execution.dsl.phases.ExecutionPhase;
import io.crate.execution.dsl.phases.NodeOperation;
import io.crate.execution.dsl.phases.NodeOperationGrouper;
import io.crate.execution.dsl.phases.NodeOperationTree;
import io.crate.execution.engine.profile.CollectProfileNodeAction;
import io.crate.execution.engine.profile.CollectProfileRequest;
import io.crate.execution.engine.profile.NodeCollectProfileResponse;
import io.crate.execution.engine.profile.TransportCollectProfileOperation;
import io.crate.execution.support.ActionExecutor;
import io.crate.execution.support.NodeRequest;
import io.crate.execution.support.OneRowActionListener;
import io.crate.expression.symbol.SelectSymbol;
import io.crate.planner.DependencyCarrier;
import io.crate.planner.ExecutionPlan;
import io.crate.planner.Plan;
import io.crate.planner.PlanPrinter;
import io.crate.planner.PlannerContext;
import io.crate.planner.operators.Collect;
import io.crate.planner.operators.LogicalPlan;
import io.crate.planner.operators.LogicalPlanVisitor;
import io.crate.planner.operators.LogicalPlanner;
import io.crate.planner.operators.PrintContext;
import io.crate.planner.operators.SubQueryResults;
import io.crate.planner.optimizer.costs.PlanStats;
import io.crate.planner.optimizer.symbol.Optimizer;
import io.crate.planner.statement.CopyFromPlan;
import io.crate.profile.ProfilingContext;
import io.crate.profile.Timer;
import io.crate.session.BaseResultReceiver;
import io.crate.session.RowConsumerToResultReceiver;
import io.crate.types.DataTypes;

public class ExplainPlan implements Plan {

    private static final CastOptimizer CAST_OPTIMIZER = new CastOptimizer();

    public enum Phase {
        Analyze,
        Plan,
        Execute
    }

    private final Plan subPlan;
    @Nullable
    private final ProfilingContext context;
    private final boolean showCosts;
    private final boolean verbose;
    private final List<OptimizerStep> optimizerSteps;

    public ExplainPlan(Plan subExecutionPlan,
                       boolean showCosts,
                       @Nullable ProfilingContext context,
                       boolean verbose,
                       List<OptimizerStep> optimizerSteps) {
        this.subPlan = subExecutionPlan;
        this.context = context;
        this.showCosts = showCosts;
        this.verbose = verbose;
        this.optimizerSteps = optimizerSteps;
    }

    public Plan subPlan() {
        return subPlan;
    }

    public boolean showCosts() {
        return showCosts;
    }

    public boolean verbose() {
        return verbose;
    }

    @Override
    public StatementType type() {
        return StatementType.MANAGEMENT;
    }

    @Override
    public void executeOrFail(DependencyCarrier dependencies,
                              PlannerContext plannerContext,
                              RowConsumer consumer,
                              Row params,
                              SubQueryResults subQueryResults) {
        if (context != null) {
            assert subPlan instanceof LogicalPlan : "subPlan must be a LogicalPlan";
            LogicalPlan plan = (LogicalPlan) subPlan;
            executePlan(
                plan,
                dependencies,
                plannerContext,
                consumer,
                params,
                subQueryResults,
                new IdentityHashMap<>(),
                new ArrayList<>(plan.dependencies().size()),
                null,
                null
            );
        } else {
            if (subPlan instanceof LogicalPlan logicalPlan) {
                if (verbose) {
                    List<RowN> rows = optimizerSteps.stream()
                        .map(step -> {
                            if (step.isInitial()) {
                                return new RowN("Initial logical plan", step.planAsString());
                            }
                            return new RowN(step.rule().sessionSettingName(), step.planAsString());
                        })
                        .collect(Collectors.toCollection(ArrayList::new));
                    rows.add(new RowN("Final logical plan", printLogicalPlan(logicalPlan, plannerContext, showCosts)));
                    consumer.accept(InMemoryBatchIterator.of(rows, SENTINEL, false), null);
                } else {
                    var planAsString = printLogicalPlan(logicalPlan, plannerContext, showCosts);
                    consumer.accept(InMemoryBatchIterator.of(new Row1(planAsString), SENTINEL), null);
                }
            } else if (subPlan instanceof CopyFromPlan copyFromPlan && !verbose) {
                BoundCopyFrom boundCopyFrom = CopyFromPlan.bind(
                    copyFromPlan.copyFrom(),
                    plannerContext.transactionContext(),
                    plannerContext.nodeContext(),
                    params,
                    subQueryResults);
                ExecutionPlan executionPlan = CopyFromPlan.planCopyFromExecution(
                    copyFromPlan.copyFrom(),
                    boundCopyFrom,
                    dependencies.clusterService().state().nodes(),
                    plannerContext);
                Function<String, String> uuidToIndexName = indexUUID -> {
                    RelationMetadata relation = dependencies.clusterService().state().metadata().getRelation(indexUUID);
                    if (relation != null) {
                        return relation.name().sqlFqn();
                    }
                    throw new IllegalStateException("RelationMetadata for " + indexUUID + " is not available");
                };
                String planAsJson = DataTypes.STRING.implicitCast(PlanPrinter.objectMap(executionPlan, uuidToIndexName));
                consumer.accept(InMemoryBatchIterator.of(new Row1(planAsJson), SENTINEL), null);
            } else if (verbose) {
                consumer.accept(null,
                    new UnsupportedOperationException("EXPLAIN VERBOSE not supported for " + subPlan.getClass().getSimpleName()));
            } else {
                consumer.accept(InMemoryBatchIterator.of(
                    new Row1("EXPLAIN not supported for " + subPlan.getClass().getSimpleName()), SENTINEL), null);
            }
        }
    }

    private CompletableFuture<?> executeTopLevelPlan(LogicalPlan plan,
                                                     DependencyCarrier executor,
                                                     PlannerContext plannerContext,
                                                     RowConsumer consumer,
                                                     Row params,
                                                     SubQueryResults subQueryResults,
                                                     List<Map<String, Object>> subQueryExplainResults) {
        assert context != null : "profilingContext must NOT be null when executing plans";

        Timer timer = context.createTimer(Phase.Execute.name());
        timer.start();
        BaseResultReceiver resultReceiver = new BaseResultReceiver();
        RowConsumer noopRowConsumer = new RowConsumerToResultReceiver(resultReceiver, 0, t -> {});
        NodeOperationTree operationTree = null;
        try {
            operationTree = LogicalPlanner.getNodeOperationTree(plan, executor, plannerContext, params, subQueryResults);
        } catch (Throwable e) {
            consumer.accept(null, e);
        }

        resultReceiver.completionFuture()
            .whenComplete(createResultConsumer(executor, consumer, plannerContext.jobId(), timer, operationTree, subQueryExplainResults));

        LogicalPlanner.executeNodeOpTree(
            executor,
            plannerContext.transactionContext(),
            plannerContext.jobId(),
            noopRowConsumer,
            true,
            operationTree
        );
        return consumer.completionFuture();
    }

    private CompletableFuture<?> executePlan(LogicalPlan plan,
                                             DependencyCarrier executor,
                                             PlannerContext plannerContext,
                                             RowConsumer consumer,
                                             Row params,
                                             SubQueryResults subQueryResults,
                                             Map<SelectSymbol, Object> valuesBySubQuery,
                                             List<Map<String, Object>> explainResults,
                                             @Nullable RamAccounting ramAccounting,
                                             @Nullable SelectSymbol selectSymbol) {
        boolean isTopLevel = selectSymbol == null;

        assert ramAccounting != null || isTopLevel : "ramAccounting must NOT be null for subPlans";

        if (ramAccounting == null) {
            ramAccounting = ConcurrentRamAccounting.forCircuitBreaker(
                "multi-phase",
                executor.circuitBreaker(HierarchyCircuitBreakerService.QUERY),
                plannerContext.transactionContext().sessionSettings().memoryLimitInBytes()
            );
        }

        if (isTopLevel == false) {
            plannerContext = PlannerContext.forSubPlan(plannerContext);
        }

        IdentityHashMap<SelectSymbol, Object> subPlanValueBySubQuery = new IdentityHashMap<>();
        ArrayList<Map<String, Object>> subPlansExplainResults = new ArrayList<>(plan.dependencies().size());
        List<CompletableFuture<?>> subPlansFutures = new ArrayList<>(plan.dependencies().size());

        for (Map.Entry<LogicalPlan, SelectSymbol> entry : plan.dependencies().entrySet()) {
            SelectSymbol subPlanSelectSymbol = entry.getValue();

            // Some optimizers may have created new sub plans which aren't optimized by itself yet.
            final LogicalPlan subPlan = plannerContext.optimize().apply(entry.getKey(), plannerContext);

            subPlansFutures.add(executePlan(
                subPlan,
                executor,
                PlannerContext.forSubPlan(plannerContext),
                consumer,
                params,
                subQueryResults,
                subPlanValueBySubQuery,
                subPlansExplainResults,
                ramAccounting,
                subPlanSelectSymbol
            ));
        }
        var subPlansFuture = CompletableFuture
            .allOf(subPlansFutures.toArray(new CompletableFuture[0]));

        final var plannerContextFinal = plannerContext;

        if (isTopLevel) {
            return subPlansFuture.thenCompose(ignored ->
                executeTopLevelPlan(
                    plan,
                    executor,
                    plannerContextFinal,
                    consumer,
                    params,
                    SubQueryResults.merge(subQueryResults, new SubQueryResults(subPlanValueBySubQuery)),
                    subPlansExplainResults
                ));
        } else {
            final var ramAccountingFinal = ramAccounting;
            return subPlansFuture.thenCompose(ignored ->
                executeSingleSubPlan(
                    plan,
                    selectSymbol,
                    executor,
                    plannerContextFinal,
                    ramAccountingFinal,
                    params,
                    SubQueryResults.merge(subQueryResults, new SubQueryResults(subPlanValueBySubQuery)),
                    subPlansExplainResults
                ).thenCompose(subQueryResultAndExplain -> {
                    synchronized (valuesBySubQuery) {
                        valuesBySubQuery.put(selectSymbol, subQueryResultAndExplain.value());
                    }

                    synchronized (explainResults) {
                        explainResults.add(subQueryResultAndExplain.explainResult());
                    }
                    return CompletableFuture.completedFuture(null);
                }));
        }
    }

    private CompletableFuture<SubQueryResultAndExplain> executeSingleSubPlan(LogicalPlan plan,
                                                                             SelectSymbol selectSymbol,
                                                                             DependencyCarrier executor,
                                                                             PlannerContext plannerContext,
                                                                             RamAccounting ramAccounting,
                                                                             Row params,
                                                                             SubQueryResults subQueryResults,
                                                                             List<Map<String, Object>> explainResults) {
        RowConsumer rowConsumer = MultiPhaseExecutor.getConsumer(selectSymbol, ramAccounting);

        var subPlanContext = new ProfilingContext(Map.of(), executor.clusterService().state());
        Timer subPlanTimer = subPlanContext.createTimer(Phase.Execute.name());
        subPlanTimer.start();

        NodeOperationTree operationTree = LogicalPlanner.getNodeOperationTree(
            plan, executor, plannerContext, params, subQueryResults);

        LogicalPlanner.executeNodeOpTree(
            executor,
            plannerContext.transactionContext(),
            plannerContext.jobId(),
            rowConsumer,
            true,
            operationTree
        );

        return rowConsumer.completionFuture()
            .thenCompose(val -> {
                subPlanContext.stopTimerAndStoreDuration(subPlanTimer);
                return collectTimingResults(plannerContext.jobId(), executor, operationTree.nodeOperations())
                    .thenCompose((timingResults) -> {
                        var explainOutput = buildResponse(
                            subPlanContext.getDurationInMSByTimer(),
                            timingResults,
                            operationTree,
                            explainResults,
                            true
                        );
                        return CompletableFuture.completedFuture(
                            new SubQueryResultAndExplain(val, explainOutput)
                        );
                    });
            });
    }


    @VisibleForTesting
    public static String printLogicalPlan(LogicalPlan logicalPlan, PlannerContext plannerContext, boolean showCosts) {
        final PrintContext printContext = createPrintContext(plannerContext.planStats(), showCosts);
        var optimizedLogicalPlan = logicalPlan.accept(CAST_OPTIMIZER, plannerContext);
        optimizedLogicalPlan.print(printContext);
        return printContext.toString();
    }

    public static PrintContext createPrintContext(PlanStats planStats, boolean showCosts) {
        if (showCosts) {
            return new PrintContext(planStats);
        } else {
            return new PrintContext(null);
        }
    }

    private BiConsumer<Void, Throwable> createResultConsumer(DependencyCarrier executor,
                                                             RowConsumer consumer,
                                                             UUID jobId,
                                                             Timer timer,
                                                             NodeOperationTree operationTree,
                                                             List<Map<String, Object>> subQueryExplainResults) {
        assert context != null : "profilingContext must be available if createResultconsumer is used";
        return (ignored, t) -> {
            context.stopTimerAndStoreDuration(timer);
            if (t == null) {
                OneRowActionListener<Map<String, Map<String, Object>>> actionListener =
                    new OneRowActionListener<>(consumer,
                        resp -> new Row1(buildResponse(context.getDurationInMSByTimer(), resp, operationTree, subQueryExplainResults, false)));
                collectTimingResults(jobId, executor, operationTree.nodeOperations())
                    .whenComplete(actionListener);
            } else {
                consumer.accept(null, t);
            }
        };
    }

    private TransportCollectProfileOperation getCollectOperation(DependencyCarrier executor, UUID jobId) {
        ActionExecutor<NodeRequest<CollectProfileRequest>, NodeCollectProfileResponse> nodeAction =
            req -> executor.client().execute(CollectProfileNodeAction.INSTANCE, req);
        return new TransportCollectProfileOperation(nodeAction, jobId);
    }

    private Map<String, Object> buildResponse(Map<String, Object> apeTimings,
                                              Map<String, Map<String, Object>> timingsByNodeId,
                                              NodeOperationTree operationTree,
                                              List<Map<String, Object>> subQueryExplainResults,
                                              boolean isSubQuery) {
        MapBuilder<String, Object> mapBuilder = MapBuilder.newMapBuilder();

        if (isSubQuery == false) {
            apeTimings.forEach(mapBuilder::put);
        }

        // Each node collects the timings for each phase it executes. We want to extract the phases from each node
        // under a dedicated "Phases" key so it's easier for the user to follow the execution.
        // So we'll transform the response from what the nodes send which looks like this:
        //
        // "Execute": {
        //      "nodeId1": {"0-collect": 23, "2-fetchPhase": 334, "QueryBreakDown": {...}}
        //      "nodeId2": {"0-collect": 12, "2-fetchPhase": 222, "QueryBreakDown": {...}}
        //  }
        //
        // To:
        // "Execute": {
        //      "Phases": {
        //         "0-collect": {
        //              "nodes": {"nodeId1": 23, "nodeId2": 12}
        //          },
        //         "2-fetchPhase": {
        //              "nodes": {"nodeId1": 334, "nodeId2": 222}
        //          }
        //      }
        //      "nodeId1": {"QueryBreakDown": {...}}
        //      "nodeId2": {"QueryBreakDown": {...}}
        //  }
        //
        // Additionally, execution timings for sub-queries are stored under a "Sub-Queries" key in the same structure as above.
        //
        // "Execute": {
        //     "Sub-Queries": {
        //          "Phases": {
        //              "0-collect": {
        //                  "nodes": {"nodeId1": 23, "nodeId2": 12}
        //              },
        //              ...
        //          "nodeId1": {"QueryBreakDown": {...}}
        //      }
        //      "Phases": {
        //         "1-collect": {
        //              "nodes": {"nodeId1": 23, "nodeId2": 12}
        //          },
        //          ...
        //      }
        //      "nodeId1": {"QueryBreakDown": {...}}
        //      "nodeId2": {"QueryBreakDown": {...}}
        //  }
        //

        Map<String, Object> phasesTimings = extractPhasesTimingsFrom(timingsByNodeId, operationTree);
        Map<String, Map<String, Object>> resultNodeTimings = getNodeTimingsWithoutPhases(phasesTimings.keySet(), timingsByNodeId);
        MapBuilder<String, Object> executionTimingsMap = MapBuilder.newMapBuilder();
        executionTimingsMap.put("Phases", phasesTimings);
        resultNodeTimings.forEach(executionTimingsMap::put);
        executionTimingsMap.put("Total", apeTimings.get(Phase.Execute.name()));

        if (subQueryExplainResults.isEmpty() == false) {
            ArrayList<Object> subQueryExplainResultsList = new ArrayList<>(subQueryExplainResults.size());
            for (var subQueryExplainResult : subQueryExplainResults) {
                subQueryExplainResultsList.add(subQueryExplainResult.get(Phase.Execute.name()));
            }

            executionTimingsMap.put("Sub-Queries", subQueryExplainResultsList);
        }

        mapBuilder.put(Phase.Execute.name(), executionTimingsMap.immutableMap());
        return mapBuilder.immutableMap();
    }

    private static Map<String, Object> extractPhasesTimingsFrom(Map<String, Map<String, Object>> timingsByNodeId,
                                                                NodeOperationTree operationTree) {
        Map<String, Object> allPhases = new TreeMap<>();
        for (NodeOperation operation : operationTree.nodeOperations()) {
            ExecutionPhase phase = operation.executionPhase();
            getPhaseTimingsAndAddThemToPhasesMap(phase, timingsByNodeId, allPhases);
        }

        ExecutionPhase leafExecutionPhase = operationTree.leaf();
        getPhaseTimingsAndAddThemToPhasesMap(leafExecutionPhase, timingsByNodeId, allPhases);

        return allPhases;
    }

    private static void getPhaseTimingsAndAddThemToPhasesMap(ExecutionPhase leafExecutionPhase,
                                                             Map<String, Map<String, Object>> timingsByNodeId,
                                                             Map<String, Object> allPhases) {
        String phaseName = ProfilingContext.generateProfilingKey(leafExecutionPhase.phaseId(), leafExecutionPhase.name());
        Map<String, Object> phaseTimingsAcrossNodes = getPhaseTimingsAcrossNodes(phaseName, timingsByNodeId);

        if (!phaseTimingsAcrossNodes.isEmpty()) {
            allPhases.put(phaseName, Map.of("nodes", phaseTimingsAcrossNodes));
        }
    }

    private static Map<String, Object> getPhaseTimingsAcrossNodes(String phaseName,
                                                                  Map<String, Map<String, Object>> timingsByNodeId) {
        Map<String, Object> timingsForPhaseAcrossNodes = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> nodeToTimingsEntry : timingsByNodeId.entrySet()) {
            Map<String, Object> timingsForNode = nodeToTimingsEntry.getValue();
            if (timingsForNode != null) {
                Object phaseTiming = timingsForNode.get(phaseName);
                if (phaseTiming != null) {
                    String node = nodeToTimingsEntry.getKey();
                    timingsForPhaseAcrossNodes.put(node, phaseTiming);
                }
            }
        }

        return Collections.unmodifiableMap(timingsForPhaseAcrossNodes);
    }

    private static Map<String, Map<String, Object>> getNodeTimingsWithoutPhases(Set<String> phasesNames,
                                                                                Map<String, Map<String, Object>> timingsByNodeId) {
        Map<String, Map<String, Object>> nodeTimingsWithoutPhases = HashMap.newHashMap(timingsByNodeId.size());
        for (Map.Entry<String, Map<String, Object>> nodeToTimingsEntry : timingsByNodeId.entrySet()) {
            nodeTimingsWithoutPhases.put(nodeToTimingsEntry.getKey(), new HashMap<>(nodeToTimingsEntry.getValue()));
        }

        for (Map<String, Object> timings : nodeTimingsWithoutPhases.values()) {
            for (String phaseToRemove : phasesNames) {
                timings.remove(phaseToRemove);
            }
        }

        return Collections.unmodifiableMap(nodeTimingsWithoutPhases);
    }

    private CompletableFuture<Map<String, Map<String, Object>>> collectTimingResults(UUID jobId,
                                                                                     DependencyCarrier executor,
                                                                                     Collection<NodeOperation> nodeOperations) {
        Set<String> nodeIds = new HashSet<>(NodeOperationGrouper.groupByServer(nodeOperations).keySet());
        nodeIds.add(executor.localNodeId());

        CompletableFuture<Map<String, Map<String, Object>>> resultFuture = new CompletableFuture<>();
        TransportCollectProfileOperation collectOperation = getCollectOperation(executor, jobId);

        ConcurrentHashMap<String, Map<String, Object>> timingsByNodeId = new ConcurrentHashMap<>(nodeIds.size());

        AtomicInteger remainingCollectOps = new AtomicInteger(nodeIds.size());

        for (String nodeId : nodeIds) {
            collectOperation.collect(nodeId)
                .whenComplete(mergeResultsAndCompleteFuture(resultFuture, timingsByNodeId, remainingCollectOps, nodeId));
        }

        return resultFuture;
    }

    private static BiConsumer<Map<String, Object>, Throwable> mergeResultsAndCompleteFuture(CompletableFuture<Map<String, Map<String, Object>>> resultFuture,
                                                                                            ConcurrentHashMap<String, Map<String, Object>> timingsByNodeId,
                                                                                            AtomicInteger remainingOperations,
                                                                                            String nodeId) {
        return (map, throwable) -> {
            if (throwable == null) {
                timingsByNodeId.put(nodeId, map);
                if (remainingOperations.decrementAndGet() == 0) {
                    resultFuture.complete(timingsByNodeId);
                }
            } else {
                resultFuture.completeExceptionally(throwable);
            }
        };
    }

    @VisibleForTesting
    public boolean doAnalyze() {
        return context != null;
    }

    /**
     * Optimize casts of the {@link io.crate.analyze.WhereClause} query symbol. For executions, this is done while
     * building an {@link ExecutionPlan} to take subquery and parameter symbols into account.
     * See also {@link Collect}.
     */
    private static class CastOptimizer extends LogicalPlanVisitor<PlannerContext, LogicalPlan> {

        @Override
        public LogicalPlan visitPlan(LogicalPlan logicalPlan, PlannerContext context) {
            ArrayList<LogicalPlan> newSources = new ArrayList<>(logicalPlan.sources().size());
            for (var source : logicalPlan.sources()) {
                newSources.add(source.accept(this, context));
            }
            return logicalPlan.replaceSources(newSources);
        }

        @Override
        public LogicalPlan visitCollect(Collect collect, PlannerContext context) {
            var optimizedCollect = new Collect(
                collect.relation(),
                collect.outputs(),
                collect.where().map(s -> Optimizer.optimizeCasts(s, context))
            );
            return visitPlan(optimizedCollect, context);
        }
    }

    record SubQueryResultAndExplain(Object value, Map<String, Object> explainResult) {
    }
}
