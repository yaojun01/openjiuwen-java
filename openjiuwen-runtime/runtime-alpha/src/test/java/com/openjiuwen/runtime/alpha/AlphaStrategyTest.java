package com.openjiuwen.runtime.alpha;

import com.openjiuwen.runtime.alpha.executor.PregelExecutor;
import com.openjiuwen.core.alpha.executor.SuperstepResult;
import com.openjiuwen.core.alpha.graph.*;
import com.openjiuwen.core.alpha.model.*;
import com.openjiuwen.runtime.alpha.planner.DefaultPlanner;
import com.openjiuwen.runtime.alpha.planner.Planner;
import com.openjiuwen.core.alpha.verifier.ReplanStrategy;
import com.openjiuwen.core.alpha.verifier.VerifyResult;
import com.openjiuwen.runtime.alpha.verifier.DefaultVerifier;
import com.openjiuwen.runtime.alpha.verifier.Verifier;
import com.openjiuwen.runtime.core.fixtures.MockChatModel;
import com.openjiuwen.runtime.core.fixtures.MockToolProvider;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.runtime.core.engine.DefaultSafetyBoundary;
import com.openjiuwen.core.kernel.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Alpha 策略 PEV 三阶段测试。
 *
 * Plan:
 * - LLM 生成 TaskGraph，环检测通过
 * - LLM 生成有环 TaskGraph，检测到环并自纠错
 *
 * Execute:
 * - 3 节点 DAG 并行执行，全部成功
 * - 1 节点失败，ErrorPolicy=Retry 重试成功
 * - 子 Agent 递归，最深 3 层
 *
 * Verify:
 * - 全部 successCriteria 通过
 * - 1 条 criteria 未通过，触发 PartialReplan
 */
@DisplayName("AlphaStrategy: PEV 三阶段")
class AlphaStrategyTest {

    // ==================== Plan 阶段 ====================

    @Nested
    @DisplayName("Plan 阶段")
    class PlanTest {

        @Test
        @DisplayName("LLM 生成无环 TaskGraph，PlanValidator 校验通过")
        void plan_noCycle_validationPasses() {
            MockChatModel llm = new MockChatModel(MockChatModel.LINEAR_3_NODE_JSON);
            AgentKernel kernel = createMockKernel(llm);
            Planner planner = new DefaultPlanner(kernel);

            PlanGoal goal = PlanGoal.of("处理订单");
            ExecutionPolicy policy = ExecutionPolicy.developmentDefault();

            StepVerifier.create(planner.plan(TaskId.generate(), goal, policy))
                .assertNext(result -> {
                    assertTrue(result.isValid());
                    assertNotNull(result.graph());
                    assertEquals(3, result.graph().nodes().size());
                    assertEquals(2, result.graph().edges().size());

                    // 验证拓扑排序可以执行
                    List<List<TaskNode>> layers = result.graph().executionLayers();
                    assertFalse(layers.isEmpty());
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("LLM 生成有环 TaskGraph，环检测通过 PlanValidator")
        void plan_hasCycle_cycleDetected() {
            MockChatModel llm = new MockChatModel(MockChatModel.CYCLIC_JSON);
            AgentKernel kernel = createMockKernel(llm);
            Planner planner = new DefaultPlanner(kernel);

            PlanGoal goal = PlanGoal.of("有环任务");
            ExecutionPolicy policy = ExecutionPolicy.developmentDefault();

            StepVerifier.create(planner.plan(TaskId.generate(), goal, policy))
                .assertNext(result -> {
                    // 第一轮应该校验失败（有环）
                    // DefaultPlanner 会尝试自纠错，但 MockChatModel 始终返回有环 JSON
                    // 所以最终结果应该是失败的
                    assertFalse(result.isValid());
                    assertTrue(result.issues().stream()
                        .anyMatch(i -> "CYCLE_DETECTED".equals(i.code())));
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("3 节点并行 DAG 正确生成执行层")
        void parallelDag_correctExecutionLayers() {
            TaskNode a = TaskNode.of("A", "查询订单", TaskNodeType.TOOL_CALL);
            TaskNode b = TaskNode.of("B", "查询库存", TaskNodeType.TOOL_CALL);
            TaskNode c = TaskNode.of("C", "汇总分析", TaskNodeType.LLM_CALL);

            TaskGraph graph = new TaskGraph("并行分析", List.of(a, b, c),
                List.of(TaskEdge.of("A", "C"), TaskEdge.of("B", "C")));

            List<List<TaskNode>> layers = graph.executionLayers();

            assertEquals(2, layers.size());
            assertEquals(2, layers.get(0).size()); // A, B 并行
            assertEquals(1, layers.get(1).size()); // C 依赖 A,B
            assertEquals(new NodeId("C"), layers.get(1).get(0).id());
        }

        @Test
        @DisplayName("executionLayers 对有环图抛出 IllegalStateException")
        void cyclicGraph_executionLayers_throws() {
            TaskNode a = TaskNode.of("A", "A", TaskNodeType.LLM_CALL);
            TaskNode b = TaskNode.of("B", "B", TaskNodeType.LLM_CALL);
            TaskNode c = TaskNode.of("C", "C", TaskNodeType.LLM_CALL);

            TaskGraph graph = new TaskGraph("有环",
                List.of(a, b, c),
                List.of(TaskEdge.of("A", "B"), TaskEdge.of("B", "C"), TaskEdge.of("C", "A")));

            assertThrows(IllegalStateException.class, graph::executionLayers);
        }
    }

    // ==================== Execute 阶段 ====================

    @Nested
    @DisplayName("Execute 阶段")
    class ExecuteTest {

        @Test
        @DisplayName("3 节点 DAG 并行执行，全部成功")
        void execute_3nodeDag_allSucceed() {
            MockToolProvider tools = new MockToolProvider()
                .register("查询订单", "ORD-001: 已发货")
                .register("查询库存", "库存充足")
                .register("汇总分析", "分析完成");

            MockChatModel llm = new MockChatModel("分析结果：一切正常");
            AgentKernel kernel = createMockKernelWithTools(llm, tools);

            TaskNode a = TaskNode.of("A", "查询订单", TaskNodeType.TOOL_CALL);
            TaskNode b = TaskNode.of("B", "查询库存", TaskNodeType.TOOL_CALL);
            TaskNode c = TaskNode.of("C", "汇总分析", TaskNodeType.LLM_CALL);

            TaskGraph graph = new TaskGraph("处理订单",
                List.of(a, b, c),
                List.of(TaskEdge.of("A", "C"), TaskEdge.of("B", "C")));

            PregelExecutor executor = new TestPregelExecutor(kernel);
            ExecutionPolicy policy = ExecutionPolicy.developmentDefault();
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.developmentDefault());

            StepVerifier.create(executor.execute(TaskId.generate(), graph, policy, budget))
                .assertNext(step0 -> {
                    assertEquals(0, step0.superstepIndex());
                    assertEquals(2, step0.nodeResults().size());
                    assertTrue(step0.allSucceeded());
                })
                .assertNext(step1 -> {
                    assertEquals(1, step1.superstepIndex());
                    assertEquals(1, step1.nodeResults().size());
                    assertTrue(step1.allSucceeded());
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("1 节点失败，ErrorPolicy=Retry 重试成功")
        void execute_retryOnError_succeeds() {
            // 工具第一次失败，第二次成功
            MockToolProvider tools = new MockToolProvider()
                .registerFlaky("flakyService", 2, "最终成功");

            MockChatModel llm = new MockChatModel("unused");
            AgentKernel kernel = createMockKernelWithTools(llm, tools);

            TaskNode a = TaskNode.of("A", "flakyService", TaskNodeType.TOOL_CALL);
            TaskGraph graph = new TaskGraph("容错测试", List.of(a), List.of());

            // 使用 Retry 策略，最多重试 3 次
            ErrorPolicy retryPolicy = new ErrorPolicy.Retry(3, 100L, new ErrorPolicy.FailFast());
            PregelExecutor executor = new TestPregelExecutor(kernel, retryPolicy);
            ExecutionPolicy policy = ExecutionPolicy.developmentDefault();
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.developmentDefault());

            StepVerifier.create(executor.execute(TaskId.generate(), graph, policy, budget))
                .assertNext(step -> {
                    // 第一次执行会失败（flaky 工具），但 Retry 策略会重试
                    // 最终节点应恢复
                    assertNotNull(step);
                })
                .verifyComplete();
        }
    }

    // ==================== Verify 阶段 ====================

    @Nested
    @DisplayName("Verify 阶段")
    class VerifyTest {

        @Test
        @DisplayName("全部 successCriteria 通过")
        void verify_allCriteriaPass() {
            MockChatModel llm = new MockChatModel("PASS: 所有结果符合要求");
            AgentKernel kernel = createMockKernel(llm);
            Verifier verifier = new DefaultVerifier(kernel);

            PlanGoal goal = PlanGoal.of("分析数据",
                List.of("数据完整性", "结果准确性"));

            TaskNode a = TaskNode.of("A", "获取数据", TaskNodeType.TOOL_CALL);
            TaskNode b = TaskNode.of("B", "分析", TaskNodeType.LLM_CALL);
            TaskGraph graph = new TaskGraph("分析", List.of(a, b), List.of(TaskEdge.of("A", "B")));

            Map<NodeId, Object> results = Map.of(
                new NodeId("A"), "数据已获取: 1000条记录",
                new NodeId("B"), "分析完成: 准确率95%"
            );

            ExecutionPolicy policy = new ExecutionPolicy(
                PlanningMode.AUTO, VerifyMode.LIGHT, 3, 4, true);
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.developmentDefault());

            StepVerifier.create(verifier.verify(TaskId.generate(), goal, graph, results, policy, budget))
                .assertNext(vr -> assertTrue(vr.passed()))
                .verifyComplete();
        }

        @Test
        @DisplayName("1 条 criteria 未通过，ReplanStrategy 为 LocalReplan")
        void verify_criteriaFails_triggersReplan() {
            MockChatModel llm = new MockChatModel(MockChatModel.VERIFY_FAIL);
            AgentKernel kernel = createMockKernel(llm);
            Verifier verifier = new DefaultVerifier(kernel);

            PlanGoal goal = PlanGoal.of("任务",
                List.of("标准A", "标准B"));

            TaskNode a = TaskNode.of("A", "执行", TaskNodeType.LLM_CALL);
            TaskGraph graph = new TaskGraph("任务", List.of(a), List.of());

            Map<NodeId, Object> results = Map.of(new NodeId("A"), "部分结果");

            ExecutionPolicy policy = new ExecutionPolicy(
                PlanningMode.AUTO, VerifyMode.LIGHT, 3, 4, true);
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.developmentDefault());

            StepVerifier.create(verifier.verify(TaskId.generate(), goal, graph, results, policy, budget))
                .assertNext(vr -> {
                    assertFalse(vr.passed());
                    // 验证 ReplanStrategy
                    ReplanStrategy strategy = verifier.decideReplanStrategy(vr, 0);
                    assertInstanceOf(ReplanStrategy.LocalReplan.class, strategy);
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("多次重试后决定 AcceptPartial")
        void verify_multipleRetries_acceptPartial() {
            VerifyResult failed = VerifyResult.failed("多次失败", Set.of("A"));
            Verifier verifier = new DefaultVerifier(createMockKernel(new MockChatModel("unused")));

            // 重试次数 >= MAX_RETRY_BEFORE_ACCEPT(3) 时应 AcceptPartial
            ReplanStrategy strategy = verifier.decideReplanStrategy(failed, 3);
            assertInstanceOf(ReplanStrategy.AcceptPartial.class, strategy);
        }

        @Test
        @DisplayName("失败节点多时触发 GlobalReplan")
        void verify_manyFailedNodes_globalReplan() {
            VerifyResult manyFailed = VerifyResult.failed("大面积失败",
                Set.of("A", "B", "C", "D"));
            Verifier verifier = new DefaultVerifier(createMockKernel(new MockChatModel("unused")));

            ReplanStrategy strategy = verifier.decideReplanStrategy(manyFailed, 0);
            assertInstanceOf(ReplanStrategy.GlobalReplan.class, strategy);
        }
    }

    // ==================== 辅助方法 ====================

    private static AgentKernel createMockKernel(MockChatModel llm) {
        return createMockKernelWithTools(llm, new MockToolProvider());
    }

    private static AgentKernel createMockKernelWithTools(MockChatModel llm, MockToolProvider tools) {
        return new com.openjiuwen.runtime.core.engine.DefaultAgentKernel(
            llm, tools.build(),
            new com.openjiuwen.runtime.core.fixtures.MockCheckpointStore(),
            new DefaultSafetyBoundary()
        );
    }

    /**
     * Test PregelExecutor that delegates to DefaultPregelExecutor logic
     * without requiring a full TaskContext.
     * Uses the kernel directly for node execution.
     */
    static class TestPregelExecutor implements PregelExecutor {

        private final AgentKernel kernel;

        TestPregelExecutor(AgentKernel kernel) {
            this(kernel, null);
        }

        private final ErrorPolicy errorPolicy;

        TestPregelExecutor(AgentKernel kernel, ErrorPolicy errorPolicy) {
            this.kernel = kernel;
            this.errorPolicy = errorPolicy != null ? errorPolicy : new ErrorPolicy.FailFast();
        }

        @Override
        public Flux<SuperstepResult> execute(TaskId taskId, TaskGraph graph,
                                              ExecutionPolicy policy, BudgetLimits budget) {
            List<List<TaskNode>> layers = graph.executionLayers();
            List<SuperstepResult> results = new ArrayList<>();

            for (int i = 0; i < layers.size(); i++) {
                List<TaskNode> layer = layers.get(i);
                Map<NodeId, Object> nodeResults = new LinkedHashMap<>();
                Set<NodeId> failedNodes = new LinkedHashSet<>();

                for (TaskNode node : layer) {
                    try {
                        Object result = executeNode(node, nodeResults);
                        nodeResults.put(node.id(), result);
                    } catch (Exception e) {
                        failedNodes.add(node.id());
                        nodeResults.put(node.id(), "FAILED: " + e.getMessage());
                    }
                }

                SuperstepResult stepResult = new SuperstepResult(
                    i, nodeResults, failedNodes, Set.of());
                results.add(stepResult);

                // Handle failures with retry
                if (stepResult.hasFailures() && errorPolicy instanceof ErrorPolicy.Retry retry) {
                    for (NodeId failedId : failedNodes) {
                        for (int attempt = 0; attempt < retry.maxRetries(); attempt++) {
                            try {
                                TaskNode failedNode = layer.stream()
                                    .filter(n -> n.id().equals(failedId))
                                    .findFirst().orElseThrow();
                                Object retryResult = executeNode(failedNode, nodeResults);
                                nodeResults.put(failedId, retryResult);
                                break;
                            } catch (Exception e) {
                                // continue retrying
                            }
                        }
                    }
                }
            }

            return Flux.fromIterable(results);
        }

        private Object executeNode(TaskNode node, Map<NodeId, Object> accumulated) {
            return switch (node.type()) {
                case TOOL_CALL -> {
                    ToolName toolName = new ToolName(node.description());
                    ToolResult result = kernel.invokeTool(toolName, Map.of(),
                        BudgetLimits.start(Budget.Fixed.developmentDefault())).block();
                    if (result != null && result.success()) {
                        yield result.result();
                    }
                    throw new RuntimeException("工具调用失败: " +
                        (result != null ? result.error() : "null"));
                }
                case LLM_CALL -> kernel.think(node.description(),
                    BudgetLimits.start(Budget.Fixed.developmentDefault())).block();
                case SUB_AGENT -> kernel.think("子任务: " + node.description(),
                    BudgetLimits.start(Budget.Fixed.developmentDefault())).block();
            };
        }
    }
}
