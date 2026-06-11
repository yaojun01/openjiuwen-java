package com.openjiuwen.runtime.beta.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlanGenerator: JSON 解析")
class PlanGeneratorTest {

    private final PlanGenerator generator = new PlanGenerator();

    @Nested
    @DisplayName("parseResponse()")
    class ParseTest {

        @Test
        @DisplayName("解析合法 JSON → 3 个 PENDING 步骤")
        void validJson() {
            String json = """
                ```json
                {"steps": ["查询订单", "计算金额", "执行退款"]}
                ```
                """;
            BetaPlan plan = generator.parseResponse(json);

            assertEquals(3, plan.steps().size());
            assertEquals(0, plan.revisionCount());
            assertEquals("查询订单", plan.steps().get(0).description());
            assertEquals(PlanStep.StepStatus.PENDING, plan.steps().get(0).status());
            assertEquals(0, plan.currentStepIndex());
        }

        @Test
        @DisplayName("畸形 JSON → empty plan")
        void malformedJson() {
            BetaPlan plan = generator.parseResponse("这不是JSON");
            assertTrue(plan.steps().isEmpty());
        }

        @Test
        @DisplayName("空 steps 数组 → empty plan")
        void emptySteps() {
            BetaPlan plan = generator.parseResponse("{\"steps\": []}");
            assertTrue(plan.steps().isEmpty());
        }

        @Test
        @DisplayName("缺少 steps 字段 → empty plan")
        void missingStepsField() {
            BetaPlan plan = generator.parseResponse("{\"plan\": \"something\"}");
            assertTrue(plan.steps().isEmpty());
        }

        @Test
        @DisplayName("null 输入 → empty plan")
        void nullInput() {
            BetaPlan plan = generator.parseResponse(null);
            assertTrue(plan.steps().isEmpty());
        }
    }
}
