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
package org.elasticsearch.cluster.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.coordination.FailedToCommitClusterStateException;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.node.DiscoveryNodes.Builder;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

public class BatchedRerouteServiceTests extends ESTestCase {

    private ThreadPool threadPool;
    private ClusterService clusterService;

    @Before
    public void beforeTest() {
        threadPool = new TestThreadPool("test");
        clusterService = ClusterServiceUtils.createClusterService(threadPool);
    }

    @After
    public void afterTest() {
        clusterService.stop();
        threadPool.shutdown();
    }

    public void testReroutesWhenRequested() throws InterruptedException {
        final AtomicLong rerouteCount = new AtomicLong();
        final BatchedRerouteService batchedRerouteService = new BatchedRerouteService(clusterService, (s, r) -> {
            rerouteCount.incrementAndGet();
            return s;
        });

        long rerouteCountBeforeReroute = 0L;
        final int iterations = between(1, 100);
        final CountDownLatch countDownLatch = new CountDownLatch(iterations);
        for (int i = 0; i < iterations; i++) {
            rerouteCountBeforeReroute = Math.max(rerouteCountBeforeReroute, rerouteCount.get());
            batchedRerouteService.reroute("iteration " + i, randomFrom(EnumSet.allOf(Priority.class)),
                ActionListener.wrap(countDownLatch::countDown));
        }
        countDownLatch.await(10, TimeUnit.SECONDS);
        assertThat(rerouteCountBeforeReroute).isLessThan(rerouteCount.get());
    }

    public void testBatchesReroutesTogetherAtPriorityOfHighestSubmittedReroute() throws BrokenBarrierException, InterruptedException {
        final CyclicBarrier cyclicBarrier = new CyclicBarrier(2);
        clusterService.submitStateUpdateTask("block master service", new ClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                cyclicBarrier.await(); // notify test that we are blocked
                cyclicBarrier.await(); // wait to be unblocked by test
                return currentState;
            }

            @Override
            public void onFailure(String source, Exception e) {
                throw new AssertionError(source, e);
            }
        });

        cyclicBarrier.await(); // wait for master thread to be blocked

        final AtomicBoolean rerouteExecuted = new AtomicBoolean();
        final BatchedRerouteService batchedRerouteService = new BatchedRerouteService(clusterService, (s, r) -> {
            assertThat(rerouteExecuted.compareAndSet(false, true)).isTrue(); // only called once
            return s;
        });

        final int iterations = scaledRandomIntBetween(1, 100);
        final CountDownLatch tasksSubmittedCountDown = new CountDownLatch(iterations);
        final CountDownLatch tasksCompletedCountDown = new CountDownLatch(iterations);
        final List<Runnable> actions = new ArrayList<>(iterations);
        final Function<Priority, Runnable> rerouteFromPriority = priority -> () -> {
            final AtomicBoolean alreadyRun = new AtomicBoolean();
            batchedRerouteService.reroute("reroute at " + priority, priority, ActionListener.wrap(() -> {
                assertThat(alreadyRun.compareAndSet(false, true)).isTrue();
                tasksCompletedCountDown.countDown();
            }));
            tasksSubmittedCountDown.countDown();
        };
        actions.add(rerouteFromPriority.apply(Priority.URGENT)); // ensure at least one URGENT priority reroute
        for (int i = 1; i < iterations; i++) {
            final int iteration = i;
            if (randomBoolean()) {
                actions.add(rerouteFromPriority.apply(randomFrom(Priority.LOW, Priority.NORMAL, Priority.HIGH, Priority.URGENT)));
            } else {
                final Priority priority = randomFrom(Priority.NORMAL, Priority.HIGH, Priority.URGENT, Priority.IMMEDIATE);
                final boolean submittedConcurrentlyWithReroute = randomBoolean();
                if (submittedConcurrentlyWithReroute == false) {
                    tasksSubmittedCountDown.countDown(); // this task might be submitted later
                }
                actions.add(() -> {
                    clusterService.submitStateUpdateTask("other task " + iteration + " at " + priority,
                        new ClusterStateUpdateTask(priority) {

                            @Override
                            public ClusterState execute(ClusterState currentState) {
                                switch (priority) {
                                    case IMMEDIATE:
                                        if (submittedConcurrentlyWithReroute) {
                                            assertThat(rerouteExecuted.get()).as("should have rerouted after " + priority + " priority task").isFalse();
                                        } // else this task might be submitted too late to precede the reroute
                                        break;
                                    case URGENT:
                                        // may run either before or after reroute
                                        break;
                                    case HIGH:
                                    case NORMAL:
                                        assertThat(rerouteExecuted.get()).as("should have rerouted before " + priority + " priority task").isTrue();
                                        break;
                                    default:
                                        fail("unexpected priority: " + priority);
                                        break;
                                }
                                return currentState;
                            }

                            @Override
                            public void onFailure(String source, Exception e) {
                                throw new AssertionError(source, e);
                            }

                            @Override
                            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                                tasksCompletedCountDown.countDown();
                            }
                        });
                    if (submittedConcurrentlyWithReroute) {
                        tasksSubmittedCountDown.countDown();
                    }
                });
            }
        }
        Randomness.shuffle(actions);
        actions.forEach(threadPool.generic()::execute);
        assertThat(tasksSubmittedCountDown.await(10, TimeUnit.SECONDS)).isTrue();

        cyclicBarrier.await(); // allow master thread to continue;
        assertThat(tasksCompletedCountDown.await(10, TimeUnit.SECONDS)).isTrue(); // wait for reroute to complete
        assertThat(rerouteExecuted.get()).isTrue(); // see above for assertion that it's only called once
    }

    public void testNotifiesOnFailure() throws InterruptedException {

        final BatchedRerouteService batchedRerouteService = new BatchedRerouteService(clusterService, (s, r) -> {
            if (rarely()) {
                throw new ElasticsearchException("simulated");
            }
            return randomBoolean() ? s : ClusterState.builder(s).build();
        });

        final int iterations = between(1, 100);
        final CountDownLatch countDownLatch = new CountDownLatch(iterations);
        for (int i = 0; i < iterations; i++) {
            batchedRerouteService.reroute("iteration " + i,
                randomFrom(EnumSet.allOf(Priority.class)), ActionListener.wrap(
                    r -> {
                        countDownLatch.countDown();
                        if (rarely()) {
                            throw new ElasticsearchException("failure during notification");
                        }
                    }, e -> {
                        countDownLatch.countDown();
                        if (randomBoolean()) {
                            throw new ElasticsearchException("failure during failure notification", e);
                        }
                    }));
            if (rarely()) {
                clusterService.getMasterService().setClusterStatePublisher(
                    randomBoolean()
                        ? ClusterServiceUtils.createClusterStatePublisher(clusterService.getClusterApplierService())
                        : (event, publishListener, ackListener)
                        -> publishListener.onFailure(new FailedToCommitClusterStateException("simulated")));
            }

            if (rarely()) {
                clusterService.getClusterApplierService().onNewClusterState(
                    "simulated",
                    () -> {
                        ClusterState state = clusterService.state();
                        Builder nodesBuilder = DiscoveryNodes.builder(state.nodes())
                            .masterNodeId(randomBoolean() ? null : state.nodes().getLocalNodeId());
                        return ClusterState.builder(state)
                            .nodes(nodesBuilder)
                            .build();
                    },
                    ActionListener.wrap(() -> {})
                );
            }
        }

        assertThat(countDownLatch.await(10, TimeUnit.SECONDS)).isTrue(); // i.e. it doesn't leak any listeners
    }
}
