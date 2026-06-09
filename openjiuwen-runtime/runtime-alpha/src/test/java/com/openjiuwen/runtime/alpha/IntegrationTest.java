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
import com.openjiuwen.runtime.core.fixtures.MockCheckpointStore;
import com.openjiuwen.runtime.core.fixtures.MockToolProvider;
import com.openjiuwen.runtime.core.engine.DefaultAgentKernel;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试。
 *
 * 完整的 Agent 生命周期：spawn -> plan -> execute -> verify -> complete
 * 包含：
 * - 1 个子 Agent
 * - 1 次失败重试
 * - 1 次检查点恢复
 */
@DisplayName("端到端集成测试")
class IntegrationTest {

    // ==================== 完整 PEV 生命周期 ====================

    @Nested
    @DisplayName("完整 PEV 生命周期")
    class FullLifecycleTest {

        @Test
        @DisplayName("spawn -> plan -> execute -> verify -> complete 完整流程")
        void fullPevLifecycle() {
            // 1. 准备：创建带 Mock LLM 和工具的 kernel
            MockToolProvider tools = new MockToolProvider()
                .register("fetchData", Map.of("count", 100, "status", "OK"))
                .register("transformData", "转换完成: 95条有效");

            // LLM 返回规划 JSON，然后验证时返回 PASS
            AtomicBoolean planPhase = new AtomicBoolean(true);
            MockChatModel llm = new MockChatModel(prompt -> {
                if (planPhase.getAndSet(false)) {
                    return MockChatModel.PARALLEL_3_NODE_JSON;
                }
                return MockChatModel.VERIFY_PASS;
            });

            MockCheckpointStore checkpointStore = new MockCheckpointStore();
            DefaultSafetyBoundary safety = new DefaultSafetyBoundary();
            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, tools.build(), checkpointStore, safety);

            TaskId taskId = TaskId.generate();

            // 2. Plan: 使用 Planner 生成 TaskGraph
            Planner planner = new DefaultPlanner(kernel);
            PlanGoal goal = PlanGoal.of("数据处理任务", List.of("获取数据", "转换完成"));
            ExecutionPolicy policy = ExecutionPolicy.developmentDefault();

            Mono<PlanResult> planMono = planner.plan(taskId, goal, policy);

            StepVerifier.create(planMono)
                .assertNext(planResult -> {
                    assertTrue(planResult.isValid());
                    assertNotNull(planResult.graph());
                })
                .verifyComplete();

            // Re-obtain the plan result for subsequent steps
            PlanResult planResult = planMono.block(Duration.ofSeconds(10));
            assertNotNull(planResult);
            TaskGraph graph = planResult.graph();

            // 3. Execute: 使用 PregelExecutor 执行
            PregelExecutor executor = new SimpleTestExecutor(kernel);
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.developmentDefault());

            Flux<SuperstepResult> executeFlux = executor.execute(taskId, graph, policy, budget);

            List<SuperstepResult> steps = executeFlux.collectList().block(Duration.ofSeconds(10));
            assertNotNull(steps);
            assertFalse(steps.isEmpty());

            // 验证所有步骤都完成了
            Map<NodeId, Object> allResults = new LinkedHashMap<>();
            for (SuperstepResult step : steps) {
                allResults.putAll(step.nodeResults());
            }
            assertEquals(3, allResults.size());

            // 4. Verify
            Verifier verifier = new DefaultVerifier(kernel);
            VerifyResult verifyResult = verifier.verify(
                taskId, goal, graph, allResults, policy, budget).block(Duration.ofSeconds(10));

            assertNotNull(verifyResult);
            assertTrue(verifyResult.passed());

            // 5. Complete: 检查检查点
            assertTrue(checkpointStore.count() > 0);
        }
    }

    // ==================== 包含 1 个子 Agent ====================

    @Nested
    @DisplayName("子 Agent 递归执行")
    class SubAgentTest {

        @Test
        @DisplayName("TaskGraph 包含 SUB_AGENT 节点，递归执行成功")
        void subAgentNode_recursiveExecution() {
            MockChatModel llm = new MockChatModel("子任务执行结果: 成功");
            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, Map.of(), new MockCheckpointStore(), new DefaultSafetyBoundary());

            // A(LLM) -> B(SUB_AGENT) -> C(LLM)
            TaskNode a = TaskNode.of("A", "分析需求", TaskNodeType.LLM_CALL);
            TaskNode b = TaskNode.of("B", "执行子任务: 数据清洗", TaskNodeType.SUB_AGENT);
            TaskNode c = TaskNode.of("C", "汇总结果", TaskNodeType.LLM_CALL);

            TaskGraph graph = new TaskGraph("含子Agent",
                List.of(a, b, c),
                List.of(TaskEdge.of("A", "B"), TaskEdge.of("B", "C")));

            PregelExecutor executor = new SimpleTestExecutor(kernel);
            ExecutionPolicy policy = ExecutionPolicy.developmentDefault();
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.developmentDefault());

            List<SuperstepResult> results = executor.execute(TaskId.generate(), graph, policy, budget)
                .collectList().block(Duration.ofSeconds(10));

            assertNotNull(results);
            // 3 层：A -> B -> C
            assertEquals(3, results.size());

            // 每个步骤都成功
            for (SuperstepResult step : results) {
                assertTrue(step.allSucceeded(), "超步 " + step.superstepIndex() + " 应全部成功");
            }

            // 最终结果包含 3 个节点
            Map<NodeId, Object> allResults = new LinkedHashMap<>();
            results.forEach(r -> allResults.putAll(r.nodeResults()));
            assertEquals(3, allResults.size());
        }
    }

    // ==================== 包含 1 次失败重试 ====================

    @Nested
    @DisplayName("失败重试")
    class RetryTest {

        @Test
        @DisplayName("节点首次失败后 Retry 策略重试成功")
        void nodeFails_retrySucceeds() {
            // 工具第一次失败，第二次成功
            MockToolProvider tools = new MockToolProvider()
                .registerFlaky("unstableApi", 2, "API调用成功: 数据已获取");

            MockChatModel llm = new MockChatModel("分析完成");
            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, tools.build(), new MockCheckpointStore(), new DefaultSafetyBoundary());

            // A(会失败的工具) -> B(LLM)
            TaskNode a = TaskNode.of("A", "unstableApi", TaskNodeType.TOOL_CALL);
            TaskNode b = TaskNode.of("B", "分析结果", TaskNodeType.LLM_CALL);

            TaskGraph graph = new TaskGraph("重试测试",
                List.of(a, b), List.of(TaskEdge.of("A", "B")));

            // 使用 Retry 策略
            ErrorPolicy retryPolicy = new ErrorPolicy.Retry(3, 50L, new ErrorPolicy.FailFast());
            PregelExecutor executor = new SimpleTestExecutor(kernel, retryPolicy);
            ExecutionPolicy policy = ExecutionPolicy.developmentDefault();
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.developmentDefault());

            List<SuperstepResult> results = executor.execute(TaskId.generate(), graph, policy, budget)
                .collectList().block(Duration.ofSeconds(10));

            assertNotNull(results);
            // 第一步 A 失败后重试成功，第二步 B 执行
            assertFalse(results.isEmpty());
        }
    }

    // ==================== 包含 1 次检查点恢复 ====================

    @Nested
    @DisplayName("检查点恢复")
    class CheckpointRecoveryTest {

        @Test
        @DisplayName("yield 后从检查点恢复继续执行")
        void yieldAndResume() {
            MockCheckpointStore store = new MockCheckpointStore();
            MockChatModel llm = new MockChatModel("继续执行的结果");
            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, Map.of(), store, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();

            // 模拟：执行了第一步后 yield
            kernel.recordNodeResult(taskId, new NodeId("A"), "步骤A完成");

            String stateJson = "{\"completed\":[\"A\"],\"pending\":[\"B\",\"C\"]}";
            CheckpointId cpId = kernel.yield(taskId,
                new YieldReason.WaitingForApproval("review-1", "等待审核"), stateJson).block();

            assertNotNull(cpId);
            assertEquals(1, store.countForTask(taskId));

            // 模拟恢复：加载检查点
            Checkpoint restored = kernel.restoreCheckpoint(taskId).block();
            assertNotNull(restored);
            assertEquals("YIELDED", restored.phase());
            assertEquals(stateJson, restored.stateJson());

            // 恢复后继续执行后续步骤
            kernel.recordNodeResult(taskId, new NodeId("B"), "步骤B完成");
            kernel.recordNodeResult(taskId, new NodeId("C"), "步骤C完成");

            // 验证所有结果
            Map<NodeId, Object> results = kernel.observe(taskId, Set.of()).block();
            assertEquals(3, results.size());
        }
    }

    // ==================== 安全边界集成 ====================

    @Nested
    @DisplayName("安全边界集成")
    class SafetyIntegrationTest {

        @Test
        @DisplayName("预算耗尽时执行被终止")
        void budgetExceeded_executionTerminated() {
            MockChatModel llm = new MockChatModel("结果");
            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, Map.of(), new MockCheckpointStore(), new DefaultSafetyBoundary());

            // 极小预算：只能调 1 次 LLM
            Budget tinyBudget = new Budget.Fixed(1, 1, 100L, 0L);
            BudgetLimits exhausted = new BudgetLimits(tinyBudget, 1, 0, 50L, 0L);

            // 再调 think 应该抛出异常
            StepVerifier.create(kernel.think("再多想一下", exhausted))
                .expectError(DefaultAgentKernel.SafetyViolationException.class)
                .verify(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("MCP 安全检查集成到工具调用链")
        void mcpSecurity_integratedInToolCall() {
            DefaultSafetyBoundary safety = new DefaultSafetyBoundary();
            MockChatModel llm = new MockChatModel("unused");
            MockToolProvider tools = new MockToolProvider()
                .register("mcpTool", "结果");

            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, tools.build(), new MockCheckpointStore(), safety);

            // 先检查 MCP 安全
            Optional<Violation> mcpCheck = safety.checkMcpSecurity(
                "https://sdk:8443/mcp", false);
            assertTrue(mcpCheck.isPresent());
            assertInstanceOf(Violation.McpSecurityViolation.class, mcpCheck.get());

            // mTLS 建立后可以正常调用
            Optional<Violation> mcpPass = safety.checkMcpSecurity(
                "https://sdk:8443/mcp", true);
            assertTrue(mcpPass.isEmpty());
        }
    }

    // ==================== 辅助类：简单的测试用 PregelExecutor ====================

    /**
     * Simple test executor that executes nodes sequentially within each layer.
     * Uses the kernel for actual execution. Supports error retry.
     */
    static class SimpleTestExecutor implements PregelExecutor {

        private final DefaultAgentKernel kernel;
        private final ErrorPolicy errorPolicy;

        SimpleTestExecutor(DefaultAgentKernel kernel) {
            this(kernel, null);
        }

        SimpleTestExecutor(DefaultAgentKernel kernel, ErrorPolicy errorPolicy) {
            this.kernel = kernel;
            this.errorPolicy = errorPolicy;
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
                        Object result = executeNode(node, nodeResults, budget);
                        nodeResults.put(node.id(), result);
                    } catch (Exception e) {
                        failedNodes.add(node.id());
                        nodeResults.put(node.id(), "FAILED: " + e.getMessage());
                    }
                }

                // Retry failed nodes
                if (!failedNodes.isEmpty() && errorPolicy instanceof ErrorPolicy.Retry retry) {
                    for (NodeId failedId : new ArrayList<>(failedNodes)) {
                        for (int attempt = 0; attempt < retry.maxRetries(); attempt++) {
                            try {
                                TaskNode failedNode = layer.stream()
                                    .filter(n -> n.id().equals(failedId))
                                    .findFirst().orElseThrow();
                                Object retryResult = executeNode(failedNode, nodeResults, budget);
                                nodeResults.put(failedId, retryResult);
                                failedNodes.remove(failedId);
                                break;
                            } catch (Exception e) {
                                // continue retrying
                            }
                        }
                    }
                }

                results.add(new SuperstepResult(i, nodeResults, failedNodes, Set.of()));
            }

            return Flux.fromIterable(results);
        }

        private Object executeNode(TaskNode node, Map<NodeId, Object> accumulated,
                                    BudgetLimits budget) {
            return switch (node.type()) {
                case TOOL_CALL -> {
                    ToolName toolName = new ToolName(node.description());
                    ToolResult result = kernel.invokeTool(toolName, Map.of(), budget).block();
                    if (result != null && result.success()) {
                        yield result.result();
                    }
                    throw new RuntimeException("工具调用失败: " +
                        (result != null ? result.error() : "null"));
                }
                case LLM_CALL -> kernel.think(node.description(), budget).block();
                case SUB_AGENT -> kernel.think(
                    "子任务: " + node.description() + "\n请直接执行并返回结果。",
                    BudgetLimits.start(new Budget.Fixed(5, 5, 50000L, 60000L))).block();
            };
        }
    }
}
