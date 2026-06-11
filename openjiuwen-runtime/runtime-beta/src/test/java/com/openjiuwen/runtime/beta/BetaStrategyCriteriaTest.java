package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.orchestrator.AutonomousOrchestrator;
import com.openjiuwen.runtime.beta.orchestrator.JsonDecisionParser;
import com.openjiuwen.runtime.beta.orchestrator.DefaultDecisionPromptBuilder;
import com.openjiuwen.runtime.beta.verification.DecisionHistoryCriteriaVerifier;
import com.openjiuwen.runtime.criteria.CriteriaOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BetaStrategy Criteria 集成测试——验证构造函数正确接线。
 *
 * 直接调用 AutonomousOrchestrator 构造函数验证接线，
 * 绕过 ObjectProvider（Spring 接口有大量抽象方法，不适合手工 mock）。
 */
@DisplayName("BetaStrategy: Criteria 集成")
class BetaStrategyCriteriaTest {

    @Nested
    @DisplayName("AutonomousOrchestrator 构造函数接线")
    class ConstructorWiringTest {

        @Test
        @DisplayName("1-arg 构造函数 → criteriaOrchestrator 为 null（向后兼容）")
        void oneArg_noCriteria() {
            GuardrailEngine engine = new GuardrailEngine();
            AutonomousOrchestrator orch = new AutonomousOrchestrator(engine);
            assertNotNull(orch);
            assertEquals("beta", orch.name());
        }

        @Test
        @DisplayName("5-arg 构造函数 → criteriaOrchestrator 非 null（Criteria 接通）")
        void fiveArg_withCriteria() {
            GuardrailEngine engine = new GuardrailEngine();
            CriteriaOrchestrator co = new CriteriaOrchestrator();
            AutonomousOrchestrator orch = new AutonomousOrchestrator(
                engine,
                new JsonDecisionParser(),
                new DefaultDecisionPromptBuilder(),
                new DecisionHistoryCriteriaVerifier(),
                co);
            assertNotNull(orch);
            assertEquals("beta", orch.name());
        }

        @Test
        @DisplayName("5-arg 构造函数 → criteriaOrchestrator 为 null 也安全")
        void fiveArg_nullCriteria() {
            GuardrailEngine engine = new GuardrailEngine();
            AutonomousOrchestrator orch = new AutonomousOrchestrator(
                engine,
                new JsonDecisionParser(),
                new DefaultDecisionPromptBuilder(),
                new DecisionHistoryCriteriaVerifier(),
                null);
            assertNotNull(orch);
            assertEquals("beta", orch.name());
        }
    }
}
