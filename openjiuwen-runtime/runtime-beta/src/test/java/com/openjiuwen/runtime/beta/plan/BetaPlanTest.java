package com.openjiuwen.runtime.beta.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BetaPlan: 可变步骤计划")
class BetaPlanTest {

    private List<PlanStep> makeSteps(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> new PlanStep(i, "步骤" + (i + 1), PlanStep.StepStatus.PENDING))
            .toList();
    }

    @Nested
    @DisplayName("empty()")
    class EmptyTest {
        @Test
        @DisplayName("empty plan 无步骤，revisionCount=0")
        void emptyPlan() {
            BetaPlan plan = BetaPlan.empty();
            assertTrue(plan.steps().isEmpty());
            assertEquals(0, plan.revisionCount());
            assertEquals(-1, plan.currentStepIndex());
            assertFalse(plan.hasPending());
        }

        @Test
        @DisplayName("empty plan formatForPrompt 返回空字符串")
        void emptyFormat() {
            assertEquals("", BetaPlan.empty().formatForPrompt());
        }
    }

    @Nested
    @DisplayName("formatForPrompt()")
    class FormatTest {
        @Test
        @DisplayName("正确渲染进度视图：第一个 PENDING 标记为 >>>")
        void formatWithPendingSteps() {
            BetaPlan plan = new BetaPlan(makeSteps(3), 0);
            String formatted = plan.formatForPrompt();

            assertTrue(formatted.contains("[1/3] >>>      - 步骤1"), "第一个 PENDING 应标记 >>>");
            assertTrue(formatted.contains("[2/3] PENDING  - 步骤2"));
            assertTrue(formatted.contains("[3/3] PENDING  - 步骤3"));
        }

        @Test
        @DisplayName("完成的步骤标记 DONE，当前步骤标记 >>>")
        void formatWithDoneSteps() {
            BetaPlan plan = new BetaPlan(makeSteps(3), 0).markCurrentDone();
            String formatted = plan.formatForPrompt();

            assertTrue(formatted.contains("[1/3] DONE     - 步骤1"), "第一个应 DONE");
            assertTrue(formatted.contains("[2/3] >>>      - 步骤2"), "第二个应 >>>");
            assertTrue(formatted.contains("[3/3] PENDING  - 步骤3"));
        }
    }

    @Nested
    @DisplayName("markCurrentDone()")
    class MarkDoneTest {
        @Test
        @DisplayName("依次推进步骤")
        void advanceSteps() {
            BetaPlan plan = new BetaPlan(makeSteps(3), 0);

            assertEquals(0, plan.currentStepIndex());
            plan = plan.markCurrentDone();
            assertEquals(1, plan.currentStepIndex());

            plan = plan.markCurrentDone();
            assertEquals(2, plan.currentStepIndex());

            plan = plan.markCurrentDone();
            assertEquals(-1, plan.currentStepIndex(), "全部完成后应返回 -1");
            assertFalse(plan.hasPending());
        }

        @Test
        @DisplayName("全部完成后 markCurrentDone 不变")
        void allDone_noChange() {
            BetaPlan plan = new BetaPlan(makeSteps(1), 0).markCurrentDone();
            assertFalse(plan.hasPending());
            BetaPlan again = plan.markCurrentDone();
            assertSame(plan, again, "全部完成时应返回同一实例");
        }
    }

    @Nested
    @DisplayName("withRevisedSteps()")
    class ReviseTest {
        @Test
        @DisplayName("替换步骤列表并递增 revisionCount")
        void reviseSteps() {
            BetaPlan plan = new BetaPlan(makeSteps(3), 0);
            assertEquals(0, plan.revisionCount());

            List<PlanStep> newSteps = List.of(
                new PlanStep(0, "新步骤A", PlanStep.StepStatus.PENDING),
                new PlanStep(1, "新步骤B", PlanStep.StepStatus.PENDING)
            );
            BetaPlan revised = plan.withRevisedSteps(newSteps);

            assertEquals(1, revised.revisionCount());
            assertEquals(2, revised.steps().size());
            assertEquals("新步骤A", revised.steps().get(0).description());
            assertEquals(0, revised.steps().get(0).index(), "应重新编号");
            assertEquals(1, revised.steps().get(1).index());
        }
    }

    @Nested
    @DisplayName("currentStepIndex()")
    class CurrentStepTest {
        @Test
        @DisplayName("空计划返回 -1")
        void emptyPlan_returnsNegativeOne() {
            assertEquals(-1, BetaPlan.empty().currentStepIndex());
        }

        @Test
        @DisplayName("全部 DONE 返回 -1")
        void allDone_returnsNegativeOne() {
            BetaPlan plan = new BetaPlan(makeSteps(2), 0)
                .markCurrentDone()
                .markCurrentDone();
            assertEquals(-1, plan.currentStepIndex());
        }

        @Test
        @DisplayName("中间有 SKIPPED 时跳过找到第一个 PENDING")
        void skippedSteps() {
            List<PlanStep> steps = List.of(
                new PlanStep(0, "步骤1", PlanStep.StepStatus.DONE),
                new PlanStep(1, "步骤2", PlanStep.StepStatus.SKIPPED),
                new PlanStep(2, "步骤3", PlanStep.StepStatus.PENDING)
            );
            BetaPlan plan = new BetaPlan(steps, 0);
            assertEquals(2, plan.currentStepIndex());
            assertTrue(plan.hasPending());
        }
    }
}
