package com.openjiuwen.runtime.alpha;

import com.openjiuwen.core.alpha.executor.SubAgentBudget;
import com.openjiuwen.core.alpha.executor.SuperstepResult;
import com.openjiuwen.runtime.alpha.executor.DefaultPregelExecutor;
import com.openjiuwen.runtime.alpha.executor.PregelExecutor;
import com.openjiuwen.core.alpha.graph.*;
import com.openjiuwen.core.alpha.model.*;
import com.openjiuwen.runtime.core.fixtures.MockChatModel;
import com.openjiuwen.runtime.core.fixtures.MockCheckpointStore;
import com.openjiuwen.runtime.core.fixtures.MockToolProvider;
import com.openjiuwen.runtime.core.engine.DefaultAgentKernel;
import com.openjiuwen.runtime.core.engine.DefaultSafetyBoundary;
import com.openjiuwen.core.kernel.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PregelExecutor 执行器测试。
 *
 * - 2 个超步，每步 2 个节点并行
 * - 虚拟线程并发安全（100 个节点同时执行）
 * - 检查点保存和恢复
 */
@DisplayName("PregelExecutor: BSP 并行执行")
class PregelExecutorTest {

    // ==================== 2 个超步，每步 2 个节点并行 ====================

    @Nested
    @DisplayName("BSP 超步执行")
    class SuperstepTest {

        @Test
        @DisplayName("2 个超步，每步 2 个节点并行执行")
        void twoSupersteps_twoNodesEach() {
            // Graph: A,B → C,D (2 layers, 2 nodes each)
            MockToolProvider tools = new MockToolProvider()
                .register("taskA", "结果A")
                .register("taskB", "结果B");

            MockChatModel llm = new MockChatModel("LLM分析结果");

            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, tools.build(), new MockCheckpointStore(), new DefaultSafetyBoundary());

            TaskNode a = TaskNode.of("A", "taskA", TaskNodeType.TOOL_CALL);
            TaskNode b = TaskNode.of("B", "taskB", TaskNodeType.TOOL_CALL);
            TaskNode c = TaskNode.of("C", "分析步骤1", TaskNodeType.LLM_CALL);
            TaskNode d = TaskNode.of("D", "分析步骤2", TaskNodeType.LLM_CALL);

            TaskGraph graph = new TaskGraph("两步并行",
                List.of(a, b, c, d),
                List.of(TaskEdge.of("A", "C"), TaskEdge.of("A", "D"),
                        TaskEdge.of("B", "C"), TaskEdge.of("B", "D")));

            PregelExecutor executor = new AlphaStrategyTest.TestPregelExecutor(kernel);
            ExecutionPolicy policy = ExecutionPolicy.developmentDefault();
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.developmentDefault());

            StepVerifier.create(executor.execute(TaskId.generate(), graph, policy, budget))
                .assertNext(step0 -> {
                    assertEquals(0, step0.superstepIndex());
                    assertEquals(2, step0.nodeResults().size());
                    assertTrue(step0.allSucceeded());
                    // 验证并行执行的两个节点结果
                    assertTrue(step0.nodeResults().containsKey(new NodeId("A")));
                    assertTrue(step0.nodeResults().containsKey(new NodeId("B")));
                })
                .assertNext(step1 -> {
                    assertEquals(1, step1.superstepIndex());
                    assertEquals(2, step1.nodeResults().size());
                    assertTrue(step1.allSucceeded());
                    assertTrue(step1.nodeResults().containsKey(new NodeId("C")));
                    assertTrue(step1.nodeResults().containsKey(new NodeId("D")));
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("线性 4 节点图产生 4 个超步")
        void linearGraph_fourSupersteps() {
            MockToolProvider tools = new MockToolProvider()
                .register("step1", "s1").register("step2", "s2")
                .register("step3", "s3").register("step4", "s4");

            MockChatModel llm = new MockChatModel("result");
            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, tools.build(), new MockCheckpointStore(), new DefaultSafetyBoundary());

            TaskGraph graph = new TaskGraph("线性",
                List.of(
                    TaskNode.of("A", "step1", TaskNodeType.TOOL_CALL),
                    TaskNode.of("B", "step2", TaskNodeType.TOOL_CALL),
                    TaskNode.of("C", "step3", TaskNodeType.TOOL_CALL),
                    TaskNode.of("D", "step4", TaskNodeType.TOOL_CALL)
                ),
                List.of(
                    TaskEdge.of("A", "B"),
                    TaskEdge.of("B", "C"),
                    TaskEdge.of("C", "D")
                ));

            PregelExecutor executor = new AlphaStrategyTest.TestPregelExecutor(kernel);

            StepVerifier.create(executor.execute(TaskId.generate(), graph,
                ExecutionPolicy.developmentDefault(),
                BudgetLimits.start(Budget.Fixed.developmentDefault())))
                .expectNextCount(4)
                .verifyComplete();
        }
    }

    // ==================== 虚拟线程并发安全 ====================

    @Nested
    @DisplayName("虚拟线程并发安全")
    class ConcurrencyTest {

        @Test
        @DisplayName("100 个节点同时执行，无数据竞争")
        void hundredNodes_noRaceCondition() throws Exception {
            int nodeCount = 100;
            AtomicInteger executionCounter = new AtomicInteger(0);
            ConcurrentLinkedQueue<String> executionOrder = new ConcurrentLinkedQueue<>();

            // 所有节点都是 LLM_CALL，都在同一层（无依赖）
            List<TaskNode> nodes = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                nodes.add(TaskNode.of("N" + i, "节点" + i, TaskNodeType.LLM_CALL));
            }

            TaskGraph graph = new TaskGraph("并发测试", nodes, List.of());

            // 使用计数器追踪的 Mock LLM
            MockChatModel llm = new MockChatModel(prompt -> {
                executionCounter.incrementAndGet();
                executionOrder.add(prompt);
                return "结果: " + prompt;
            });

            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, Map.of(), new MockCheckpointStore(), new DefaultSafetyBoundary());

            PregelExecutor executor = new AlphaStrategyTest.TestPregelExecutor(kernel);

            // 执行并收集结果
            List<SuperstepResult> results = executor.execute(
                TaskId.generate(), graph,
                new ExecutionPolicy(PlanningMode.AUTO, VerifyMode.NONE, 1, nodeCount, false),
                BudgetLimits.start(new Budget.Fixed(nodeCount + 10, 10, 1_000_000L, 0L))
            ).collectList().block(Duration.ofSeconds(30));

            assertNotNull(results);
            assertEquals(1, results.size()); // 所有节点在同一层，一个超步

            SuperstepResult step = results.get(0);
            assertEquals(nodeCount, step.nodeResults().size());
            assertTrue(step.failedNodes().isEmpty());
            assertEquals(nodeCount, executionCounter.get());
        }

        @Test
        @DisplayName("并发写入 checkpoint store 无数据丢失")
        void concurrentCheckpointWrites_noDataLoss() throws Exception {
            MockCheckpointStore store = new MockCheckpointStore();
            int writerCount = 50;
            CountDownLatch latch = new CountDownLatch(writerCount);

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            for (int i = 0; i < writerCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        TaskId taskId = new TaskId("task-" + idx);
                        Checkpoint cp = Checkpoint.of(taskId, "EXECUTING", 0,
                            "{\"idx\":" + idx + "}");
                        store.save(cp).block();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(writerCount, store.count());
            executor.shutdown();
        }
    }

    // ==================== 检查点保存和恢复 ====================

    @Nested
    @DisplayName("检查点保存和恢复")
    class CheckpointTest {

        @Test
        @DisplayName("执行过程中保存检查点，可从断点恢复")
        void checkpointDuringExecution_canRestore() {
            MockCheckpointStore store = new MockCheckpointStore();
            MockChatModel llm = new MockChatModel("LLM回复");
            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, Map.of(), store, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();

            // 模拟执行过程中保存检查点
            Checkpoint cp1 = Checkpoint.of(taskId, "EXECUTING", 0, "{\"completed\":[\"A\"]}");
            Checkpoint cp2 = Checkpoint.of(taskId, "EXECUTING", 1, "{\"completed\":[\"A\",\"B\"]}");

            kernel.saveCheckpoint(cp1).block();
            kernel.saveCheckpoint(cp2).block();

            // 验证保存了 2 个检查点
            assertEquals(2, store.countForTask(taskId));

            // 恢复最新的检查点
            Checkpoint restored = kernel.restoreCheckpoint(taskId).block();
            assertNotNull(restored);
            assertEquals(1, restored.stepIndex());
            assertTrue(restored.stateJson().contains("B"));
        }

        @Test
        @DisplayName("yield 保存的检查点包含正确状态")
        void yieldCheckpoint_containsCorrectState() {
            MockCheckpointStore store = new MockCheckpointStore();
            MockChatModel llm = new MockChatModel("unused");
            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, Map.of(), store, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();
            String stateJson = "{\"phase\":\"EXECUTING\",\"completedNodes\":[\"A\",\"B\"],\"failedNodes\":[]}";

            kernel.yield(taskId,
                new YieldReason.WaitingForApproval("gate-1", "等待审批"), stateJson).block();

            // 验证 yield 保存了检查点
            Checkpoint restored = kernel.restoreCheckpoint(taskId).block();
            assertNotNull(restored);
            assertEquals("YIELDED", restored.phase());
            assertEquals(stateJson, restored.stateJson());
        }
    }
}
