package com.openjiuwen.runtime.core.engine;

import com.openjiuwen.runtime.core.fixtures.MockChatModel;
import com.openjiuwen.runtime.core.fixtures.MockCheckpointStore;
import com.openjiuwen.runtime.core.fixtures.MockToolProvider;
import com.openjiuwen.runtime.core.fixtures.TestKernelFactory;
import com.openjiuwen.core.kernel.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentKernel 7 个系统调用测试。
 *
 * 测试目标：验证 DefaultAgentKernel 的每个系统调用的基本契约。
 * 不依赖 Spring AI ChatModel，使用 MockChatModel 替代。
 */
@DisplayName("AgentKernel: 7 个系统调用")
class AgentKernelTest {

    private MockCheckpointStore checkpointStore;

    @BeforeEach
    void setUp() {
        checkpointStore = new MockCheckpointStore();
    }

    // ==================== think ====================

    @Nested
    @DisplayName("think: LLM 推理调用")
    class ThinkTest {

        @Test
        @DisplayName("正常调用返回 LLM 响应")
        void think_returnsLlmResponse() {
            MockChatModel llm = new MockChatModel("这是LLM的回答");
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                llm, new MockToolProvider(), checkpointStore, new DefaultSafetyBoundary());

            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.productionDefault());

            StepVerifier.create(kernel.think("你好", budget))
                .assertNext(response -> assertEquals("这是LLM的回答", response))
                .verifyComplete();
        }

        @Test
        @DisplayName("预算耗尽时抛出 SafetyViolationException")
        void think_budgetExceeded_throwsViolation() {
            MockChatModel llm = new MockChatModel("不应该到达这里");
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                llm, new MockToolProvider(), checkpointStore, new DefaultSafetyBoundary());

            Budget budget = new Budget.Fixed(2, 5, 100L, 0L);
            BudgetLimits exceeded = new BudgetLimits(budget, 2, 0, 0L, 0L);

            StepVerifier.create(kernel.think("你好", exceeded))
                .expectError(DefaultAgentKernel.SafetyViolationException.class)
                .verify(Duration.ofSeconds(5));
        }
    }

    // ==================== invokeTool ====================

    @Nested
    @DisplayName("invokeTool: 工具调用")
    class InvokeToolTest {

        @Test
        @DisplayName("调用已注册工具返回 ToolResult.ok")
        void invokeTool_registeredTool_returnsOk() {
            MockToolProvider tools = new MockToolProvider()
                .register("checkOrder", Map.of("orderId", "ORD-001", "status", "SHIPPED"));

            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), tools, checkpointStore, new DefaultSafetyBoundary());

            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.productionDefault());
            ToolName toolName = new ToolName("checkOrder");

            StepVerifier.create(kernel.invokeTool(toolName, Map.of("orderId", "ORD-001"), budget))
                .assertNext(result -> {
                    assertTrue(result.success());
                    assertEquals(toolName, result.toolName());
                    assertNotNull(result.result());
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("调用未注册工具返回 ToolResult.fail")
        void invokeTool_unregisteredTool_returnsFail() {
            MockToolProvider tools = new MockToolProvider();
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), tools, checkpointStore, new DefaultSafetyBoundary());

            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.productionDefault());
            ToolName toolName = new ToolName("nonExistentTool");

            StepVerifier.create(kernel.invokeTool(toolName, Map.of(), budget))
                .assertNext(result -> {
                    assertFalse(result.success());
                    assertTrue(result.error().contains("工具未注册"));
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("工具执行异常返回 ToolResult.fail")
        void invokeTool_executionException_returnsFail() {
            MockToolProvider tools = new MockToolProvider()
                .registerFailing("badTool", "连接超时");

            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), tools, checkpointStore, new DefaultSafetyBoundary());

            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.productionDefault());

            StepVerifier.create(kernel.invokeTool(new ToolName("badTool"), Map.of(), budget))
                .assertNext(result -> {
                    assertFalse(result.success());
                    assertTrue(result.error().contains("工具执行失败"));
                })
                .verifyComplete();
        }
    }

    // ==================== saveCheckpoint / restoreCheckpoint ====================

    @Nested
    @DisplayName("saveCheckpoint / restoreCheckpoint: 检查点")
    class CheckpointTest {

        @Test
        @DisplayName("保存检查点并恢复")
        void saveAndRestore() {
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), new MockToolProvider(),
                checkpointStore, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();
            Checkpoint checkpoint = Checkpoint.of(taskId, "EXECUTING", 2, "{\"progress\":50}");

            StepVerifier.create(kernel.saveCheckpoint(checkpoint))
                .assertNext(cpId -> {
                    assertNotNull(cpId);
                    assertFalse(cpId.value().isBlank());
                })
                .verifyComplete();

            StepVerifier.create(kernel.restoreCheckpoint(taskId))
                .assertNext(restored -> {
                    assertEquals(taskId, restored.taskId());
                    assertEquals("EXECUTING", restored.phase());
                    assertEquals(2, restored.stepIndex());
                    assertEquals("{\"progress\":50}", restored.stateJson());
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("恢复不存在的检查点返回 null")
        void restoreNonExistent_returnsEmpty() {
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), new MockToolProvider(),
                checkpointStore, new DefaultSafetyBoundary());

            StepVerifier.create(kernel.restoreCheckpoint(TaskId.generate()))
                .expectNextCount(0)
                .verifyComplete();
        }
    }

    // ==================== yield ====================

    @Nested
    @DisplayName("yield: 让出执行权")
    class YieldTest {

        @Test
        @DisplayName("yield 保存检查点并发布 TASK_PAUSED 事件")
        void yield_savesCheckpointAndEmitsEvent() {
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), new MockToolProvider(),
                checkpointStore, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();

            // Subscribe to events first
            Flux<AgentEvent> events = kernel.observeEvents(taskId);

            YieldReason reason = new YieldReason.WaitingForApproval("gate-1", "等待审批");

            StepVerifier.create(kernel.yield(taskId, reason, "{\"status\":\"waiting\"}"))
                .assertNext(cpId -> assertNotNull(cpId))
                .verifyComplete();

            // Verify checkpoint was saved
            assertEquals(1, checkpointStore.countForTask(taskId));

            // Verify event was emitted
            StepVerifier.create(events.take(Duration.ofSeconds(2)))
                .assertNext(event -> {
                    assertEquals(taskId, event.taskId());
                    assertEquals(EventType.TASK_PAUSED, event.type());
                })
                .verifyComplete();
        }
    }

    // ==================== emit / observeEvents ====================

    @Nested
    @DisplayName("emit / observeEvents: 事件发布订阅")
    class EventTest {

        @Test
        @DisplayName("emit 后 observeEvents 能收到事件")
        void emitAndObserve() {
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), new MockToolProvider(),
                checkpointStore, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();

            // Subscribe before emitting
            Flux<AgentEvent> events = kernel.observeEvents(taskId);

            AgentEvent event1 = AgentEvent.of(taskId, EventType.TASK_STARTED, "任务开始");
            AgentEvent event2 = AgentEvent.of(taskId, EventType.THINKING, "思考中");

            // Emit events
            kernel.emit(event1).block();
            kernel.emit(event2).block();

            StepVerifier.create(events.take(2))
                .assertNext(e -> assertEquals(EventType.TASK_STARTED, e.type()))
                .assertNext(e -> assertEquals(EventType.THINKING, e.type()))
                .verifyComplete();
        }

        @Test
        @DisplayName("多个事件按顺序接收")
        void multipleEventsInOrder() {
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), new MockToolProvider(),
                checkpointStore, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();
            Flux<AgentEvent> events = kernel.observeEvents(taskId);

            for (int i = 0; i < 5; i++) {
                kernel.emit(AgentEvent.of(taskId, EventType.TOOL_CALL, "tool-" + i)).block();
            }

            StepVerifier.create(events.take(5))
                .expectNextCount(5)
                .verifyComplete();
        }
    }

    // ==================== observe ====================

    @Nested
    @DisplayName("observe: 观察执行状态")
    class ObserveTest {

        @Test
        @DisplayName("observe 返回已记录的节点结果")
        void observe_returnsRecordedResults() {
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), new MockToolProvider(),
                checkpointStore, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();
            NodeId nodeA = new NodeId("A");
            NodeId nodeB = new NodeId("B");

            kernel.recordNodeResult(taskId, nodeA, "resultA");
            kernel.recordNodeResult(taskId, nodeB, "resultB");

            StepVerifier.create(kernel.observe(taskId, Set.of(nodeA)))
                .assertNext(results -> {
                    assertEquals(1, results.size());
                    assertEquals("resultA", results.get(nodeA));
                })
                .verifyComplete();
        }

        @Test
        @DisplayName("observe 空 nodeId 集合返回全部结果")
        void observe_emptySet_returnsAll() {
            DefaultAgentKernel kernel = TestKernelFactory.createKernelWithStore(
                new MockChatModel("unused"), new MockToolProvider(),
                checkpointStore, new DefaultSafetyBoundary());

            TaskId taskId = TaskId.generate();
            kernel.recordNodeResult(taskId, new NodeId("A"), "resultA");
            kernel.recordNodeResult(taskId, new NodeId("B"), "resultB");

            StepVerifier.create(kernel.observe(taskId, Set.of()))
                .assertNext(results -> assertEquals(2, results.size()))
                .verifyComplete();
        }
    }
}
