package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.beta.orchestrator.AutonomousOrchestrator;
import com.openjiuwen.runtime.beta.orchestrator.JsonDecisionParser;
import com.openjiuwen.runtime.beta.verification.DecisionHistoryCriteriaVerifier;
import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.core.kernel.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AutonomousOrchestrator 组件测试——验证决策循环的各个核心环节。
 *
 * 不做完整 Flux 管道测试（需要完整 Spring AI kernel 模拟），
 * 而是测试循环中的关键逻辑：解析、护栏、标准验证。
 */
@DisplayName("AutonomousOrchestrator: 组件集成")
class AutonomousOrchestratorTest {

    private GuardrailEngine guardrailEngine;

    @BeforeEach
    void setUp() {
        guardrailEngine = new GuardrailEngine();
    }

    @Nested
    @DisplayName("决策解析→执行类型映射")
    class DecisionParsingTest {

        @Test
        @DisplayName("call_tool → CallTool → 工具名正确")
        void callToolParsedCorrectly() {
            JsonDecisionParser parser = new JsonDecisionParser();
            LLMDecision decision = parser.parseOrFallback(
                """
                {"type":"call_tool","tool":"search","args":{"q":"订单"},"reasoning":"搜索订单"}
                """, "fallback");

            assertInstanceOf(LLMDecision.CallTool.class, decision);
            assertEquals("search", ((LLMDecision.CallTool) decision).toolName().value());
        }

        @Test
        @DisplayName("complete → Complete → 输出内容正确")
        void completeParsedCorrectly() {
            JsonDecisionParser parser = new JsonDecisionParser();
            LLMDecision decision = parser.parseOrFallback(
                """
                {"type":"complete","output":"订单已发货","confidence":0.95,"summary":"完成"}
                """, "fallback");

            assertInstanceOf(LLMDecision.Complete.class, decision);
            assertEquals("订单已发货", ((LLMDecision.Complete) decision).output());
        }

        @Test
        @DisplayName("give_up → GiveUp")
        void giveUpParsedCorrectly() {
            JsonDecisionParser parser = new JsonDecisionParser();
            LLMDecision decision = parser.parseOrFallback(
                """
                {"type":"give_up","reason":"无法完成","partial_result":"部分结果"}
                """, "fallback");

            assertInstanceOf(LLMDecision.GiveUp.class, decision);
        }
    }

    @Nested
    @DisplayName("标准验证集成")
    class CriteriaVerificationTest {

        @Test
        @DisplayName("标准验证器阻止不满足标准的 Complete")
        void incompleteCriteria_blocked() {
            DecisionHistoryCriteriaVerifier verifier = new DecisionHistoryCriteriaVerifier();
            GoalSpec goal = GoalSpec.of("查询订单", List.of("订单状态", "退款金额"));
            LLMDecision.Complete complete = new LLMDecision.Complete(
                "查询完成", 0.9, "完成");

            List<Violation> violations = verifier.verify(goal, List.of(), complete);
            assertFalse(violations.isEmpty(), "应检测到未覆盖的标准");
        }

        @Test
        @DisplayName("标准验证器通过满足标准的 Complete")
        void completeCriteria_passed() {
            DecisionHistoryCriteriaVerifier verifier = new DecisionHistoryCriteriaVerifier();
            GoalSpec goal = GoalSpec.of("查询订单", List.of("订单状态", "退款金额"));
            LLMDecision.Complete complete = new LLMDecision.Complete(
                "订单状态为已发货，退款金额为100元", 0.9, "完成");

            List<Violation> violations = verifier.verify(goal, List.of(), complete);
            assertTrue(violations.isEmpty(), "所有标准已覆盖，应通过");
        }
    }

    @Nested
    @DisplayName("护栏集成")
    class GuardrailIntegrationTest {

        @Test
        @DisplayName("正常决策通过护栏")
        void normalDecision_passesGuardrail() {
            LLMDecision.CallTool callTool = new LLMDecision.CallTool(
                new ToolName("search"), Map.of("q", "test"), "搜索");

            Guardrail.GuardrailContext ctx = new Guardrail.GuardrailContext(
                TaskId.generate(),
                BudgetLimits.start(Budget.Fixed.productionDefault()),
                List.of(), Map.of());

            Guardrail.GuardrailResult result = guardrailEngine.evaluate(callTool, ctx);
            assertTrue(result.passed(), "正常决策应通过护栏");
        }
    }

    @Nested
    @DisplayName("GoalSpec replan 超限")
    class ReplanLimitTest {

        @Test
        @DisplayName("超过 maxReplanCount 后 canReplan 返回 false")
        void replanLimitExceeded() {
            GoalSpec goal = GoalSpec.of("任务", List.of("标准1"), 2); // maxReplanCount=2
            assertTrue(goal.canReplan());

            goal = goal.withReplan(new GoalSpec.ReplanRecord(0, "原因1", "策略1"));
            assertTrue(goal.canReplan(), "1次 replan 后仍可继续");

            goal = goal.withReplan(new GoalSpec.ReplanRecord(1, "原因2", "策略2"));
            assertFalse(goal.canReplan(), "2次 replan 后应超限");
        }
    }
}
