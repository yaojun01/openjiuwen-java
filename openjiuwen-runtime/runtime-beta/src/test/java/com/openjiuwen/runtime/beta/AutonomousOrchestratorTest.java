package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.beta.orchestrator.AutonomousOrchestrator;
import com.openjiuwen.runtime.beta.orchestrator.JsonDecisionParser;
import com.openjiuwen.runtime.beta.verification.DecisionHistoryCriteriaVerifier;
import com.openjiuwen.runtime.criteria.CriteriaOrchestrator;
import com.openjiuwen.runtime.criteria.CriteriaGuardrail;
import com.openjiuwen.runtime.beta.plan.BetaPlan;
import com.openjiuwen.runtime.beta.plan.PlanStep;
import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;
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

    @Nested
    @DisplayName("Criteria 生命周期集成")
    class CriteriaLifecycleTest {

        @Test
        @DisplayName("无 CriteriaOrchestrator 时 GoalSpec 无 successCriteria（向后兼容）")
        void noOrchestrator_backwardCompatible() {
            AutonomousOrchestrator orch = new AutonomousOrchestrator(guardrailEngine);
            assertNotNull(orch);
            // DecisionHistoryCriteriaVerifier with empty criteria returns empty
            DecisionHistoryCriteriaVerifier verifier = new DecisionHistoryCriteriaVerifier();
            GoalSpec goal = GoalSpec.of("测试任务");
            LLMDecision.Complete complete = new LLMDecision.Complete("完成", 0.9, "总结");
            List<Violation> violations = verifier.verify(goal, List.of(), complete);
            assertTrue(violations.isEmpty(), "无标准时应直接通过");
        }

        @Test
        @DisplayName("CriteriaOrchestrator propose→confirm→buildGoalSpec 生成带标准的 GoalSpec")
        void proposeAndConfirm_enrichesGoalSpec() {
            CriteriaOrchestrator co = new CriteriaOrchestrator();
            List<CriteriaProposal> proposals = co.propose(
                "分析客户退款原因", StructuredCriteria.Industry.GENERAL);
            assertFalse(proposals.isEmpty(), "应有模板提案");

            List<VerifiedCriterion> verified = co.confirm(
                proposals.stream().limit(3).toList());
            GoalSpec goal = co.buildGoalSpec("分析客户退款原因", verified);

            assertFalse(goal.successCriteria().isEmpty(), "GoalSpec 应包含 successCriteria");
            // 验证 criteria 格式为 [dimension] description
            for (String c : goal.successCriteria()) {
                assertTrue(c.startsWith("[") && c.contains("]"),
                    "标准格式应为 [dimension] description: " + c);
            }
        }

        @Test
        @DisplayName("enriched GoalSpec 被 DecisionHistoryCriteriaVerifier 正确验证")
        void enrichedGoalSpec_detectedByDHCV() {
            CriteriaOrchestrator co = new CriteriaOrchestrator();
            List<CriteriaProposal> proposals = co.propose(
                "产品质量分析", StructuredCriteria.Industry.GENERAL);
            List<VerifiedCriterion> verified = co.confirm(
                proposals.stream().limit(3).toList());
            GoalSpec goal = co.buildGoalSpec("产品质量分析", verified);

            DecisionHistoryCriteriaVerifier verifier = new DecisionHistoryCriteriaVerifier();
            // 极简输出，不可能覆盖真实标准
            LLMDecision.Complete complete = new LLMDecision.Complete("分析完成", 0.9, "总结");
            List<Violation> violations = verifier.verify(goal, List.of(), complete);

            assertFalse(violations.isEmpty(), "应有未覆盖的标准");
        }

        @Test
        @DisplayName("guardrailEngine.withExtra(CriteriaGuardrail) 正常工作")
        void criteriaGuardrail_functional() {
            CriteriaOrchestrator co = new CriteriaOrchestrator();
            List<CriteriaProposal> proposals = co.propose(
                "任务", StructuredCriteria.Industry.GENERAL);
            List<VerifiedCriterion> verified = co.confirm(
                proposals.stream().limit(2).toList());

            GuardrailEngine engine = guardrailEngine.withExtra(new CriteriaGuardrail(verified));
            LLMDecision.Complete incomplete = new LLMDecision.Complete("简单输出", 0.95, "总结");
            Guardrail.GuardrailContext ctx = new Guardrail.GuardrailContext(
                TaskId.generate(),
                BudgetLimits.start(Budget.Fixed.productionDefault()),
                List.of(), Map.of());

            Guardrail.GuardrailResult result = engine.evaluate(incomplete, ctx);
            assertNotNull(result, "evaluate 应正常返回");
        }

        @Test
        @DisplayName("Post-execution verify → accumulate → maintain 完整闭环")
        void postExecution_fullCycle() {
            CriteriaOrchestrator co = new CriteriaOrchestrator();
            List<CriteriaProposal> proposals = co.propose(
                "分析任务", StructuredCriteria.Industry.GENERAL);
            List<VerifiedCriterion> verified = co.confirm(
                proposals.stream().limit(2).toList());

            String agentOutput = "分析完成，涵盖所有维度";
            String execLog = "工具调用: analyze()";

            List<CriteriaVerificationResult> results = co.verify(
                verified, agentOutput, execLog);
            assertNotNull(results, "verify 应返回结果");
            assertEquals(verified.size(), results.size(), "每条标准应有一个结果");

            // accumulate 不应抛异常
            assertDoesNotThrow(() -> co.accumulate(
                verified, results, StructuredCriteria.Industry.GENERAL));

            // maintain 不应抛异常
            assertDoesNotThrow(() -> co.maintain());
        }

        @Test
        @DisplayName("GiveUp 决策也能从 CriteriaGuardrail 转为 RequestHumanHelp")
        void giveUp_convertedToRequestHumanHelp() {
            CriteriaOrchestrator co = new CriteriaOrchestrator();
            List<CriteriaProposal> proposals = co.propose(
                "任务", StructuredCriteria.Industry.GENERAL);
            List<VerifiedCriterion> verified = co.confirm(
                proposals.stream().limit(2).toList());

            GuardrailEngine engine = guardrailEngine.withExtra(new CriteriaGuardrail(verified));
            LLMDecision.GiveUp giveUp = new LLMDecision.GiveUp("无法完成", "部分结果");
            Guardrail.GuardrailContext ctx = new Guardrail.GuardrailContext(
                TaskId.generate(),
                BudgetLimits.start(Budget.Fixed.productionDefault()),
                List.of(), Map.of());

            Guardrail.GuardrailResult result = engine.evaluate(giveUp, ctx);
            assertTrue(result.passed(), "GiveUp 应被通过（转为 modify）");
            assertNotNull(result.modifiedDecision(), "GiveUp 应被转为 RequestHumanHelp");
            assertInstanceOf(LLMDecision.RequestHumanHelp.class, result.modifiedDecision(),
                "转换后的决策应为 RequestHumanHelp");
        }
    }

    @Nested
    @DisplayName("计划集成")
    class PlanIntegrationTest {

        @Test
        @DisplayName("BetaPlan.empty() 时不影响 orchestrator 创建（向后兼容）")
        void emptyPlan_backwardCompatible() {
            BetaPlan empty = BetaPlan.empty();
            assertTrue(empty.steps().isEmpty());
            assertEquals("", empty.formatForPrompt());
            assertEquals(-1, empty.currentStepIndex());
            assertFalse(empty.hasPending());
        }

        @Test
        @DisplayName("BetaPlan 正确跟踪进度")
        void planProgress() {
            List<PlanStep> steps = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> new PlanStep(i, "步骤" + (i + 1), PlanStep.StepStatus.PENDING))
                .toList();
            BetaPlan plan = new BetaPlan(steps, 0);

            assertEquals(0, plan.currentStepIndex());
            assertTrue(plan.hasPending());

            plan = plan.markCurrentDone();
            assertEquals(1, plan.currentStepIndex());

            plan = plan.markCurrentDone();
            assertEquals(2, plan.currentStepIndex());

            plan = plan.markCurrentDone();
            assertFalse(plan.hasPending(), "全部完成");
        }
    }
}
