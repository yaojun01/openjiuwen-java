package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.context.SelfReflectionTrigger;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.ToolName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SelfReflectionTrigger 测试——反思触发的各种条件。
 */
@DisplayName("SelfReflectionTrigger: 反思触发")
class SelfReflectionTriggerTest {

    private SelfReflectionTrigger trigger;

    @BeforeEach
    void setUp() {
        trigger = new SelfReflectionTrigger();
    }

    // ==================== 预算触发 ====================

    @Nested
    @DisplayName("预算触发")
    class BudgetTriggerTest {

        @Test
        @DisplayName("使用量未过半 → 不触发")
        void underHalf_notTriggered() {
            assertFalse(trigger.checkBudgetTrigger(100, 1000));
        }

        @Test
        @DisplayName("使用量过半 → 触发")
        void overHalf_triggered() {
            assertTrue(trigger.checkBudgetTrigger(600, 1000));
        }

        @Test
        @DisplayName("触发后 shouldTrigger 为 true 且有原因")
        void triggered_setsState() {
            trigger.checkBudgetTrigger(600, 1000);
            assertTrue(trigger.shouldTrigger());
            assertTrue(trigger.triggerReason().contains("预算消耗已过半"));
        }
    }

    // ==================== 连续工具调用（recordToolCall 向后兼容 API） ====================

    @Nested
    @DisplayName("连续工具调用（recordToolCall）")
    class ConsecutiveToolCallTest {

        @Test
        @DisplayName("4 次工具调用不触发（阈值=5）")
        void fourCalls_notTriggered() {
            for (int i = 0; i < 4; i++) {
                trigger.recordToolCall();
            }
            assertFalse(trigger.shouldTrigger(), "4 次不应触发");
        }

        @Test
        @DisplayName("5 次工具调用触发反思")
        void fiveCalls_triggered() {
            for (int i = 0; i < 5; i++) {
                trigger.recordToolCall();
            }
            assertTrue(trigger.shouldTrigger(), "5 次应触发");
            assertTrue(trigger.triggerReason().contains("5"),
                "原因应包含次数");
        }

        @Test
        @DisplayName("recordNonToolDecision 重置连续计数")
        void nonToolResetsCount() {
            trigger.recordToolCall();
            trigger.recordToolCall();
            trigger.recordToolCall();
            trigger.recordNonToolDecision(); // 重置
            trigger.recordToolCall();
            assertFalse(trigger.shouldTrigger(),
                "重置后只调了 1 次，不应触发");
        }
    }

    // ==================== recordDecision（主 API） ====================

    @Nested
    @DisplayName("recordDecision: 连续工具调用检测")
    class RecordDecisionConsecutiveTest {

        @Test
        @DisplayName("5 次连续 CallTool 触发反思")
        void fiveConsecutiveCallTools_triggered() {
            for (int i = 0; i < 5; i++) {
                trigger.recordDecision(new LLMDecision.CallTool(
                    new ToolName("tool" + i), Map.of(), "reasoning"));
            }
            assertTrue(trigger.shouldTrigger(), "5 次连续 CallTool 应触发");
            assertTrue(trigger.triggerReason().contains("连续调用"),
                "原因应为连续工具调用");
        }

        @Test
        @DisplayName("中间穿插 ContinueThinking 重置计数")
        void thinkingResetsCount() {
            trigger.recordDecision(new LLMDecision.CallTool(
                new ToolName("tool1"), Map.of(), "r1"));
            trigger.recordDecision(new LLMDecision.CallTool(
                new ToolName("tool2"), Map.of(), "r2"));
            trigger.recordDecision(new LLMDecision.CallTool(
                new ToolName("tool3"), Map.of(), "r3"));
            // 穿插思考
            trigger.recordDecision(new LLMDecision.ContinueThinking("思考", ""));
            // 继续调用，从 0 开始
            trigger.recordDecision(new LLMDecision.CallTool(
                new ToolName("tool4"), Map.of(), "r4"));
            assertFalse(trigger.shouldTrigger(),
                "穿插思考后计数重置，不应触发");
        }
    }

    @Nested
    @DisplayName("recordDecision: 同一工具重复检测")
    class RecordDecisionSameToolTest {

        @Test
        @DisplayName("3 次调用同一工具触发反思（阈值=3）")
        void threeSameTool_triggered() {
            ToolName tool = new ToolName("search");
            for (int i = 0; i < 3; i++) {
                trigger.recordDecision(new LLMDecision.CallTool(
                    tool, Map.of("q", "query" + i), "搜索"));
            }
            assertTrue(trigger.shouldTrigger(), "3 次同一工具应触发");
            assertTrue(trigger.triggerReason().contains("search"),
                "原因应包含工具名");
        }

        @Test
        @DisplayName("交替调用不同工具不触发重复检测")
        void alternatingTools_notTriggered() {
            for (int i = 0; i < 6; i++) {
                ToolName tool = i % 2 == 0
                    ? new ToolName("search")
                    : new ToolName("calc");
                trigger.recordDecision(new LLMDecision.CallTool(
                    tool, Map.of(), "reasoning"));
            }
            // 6 次交替，同一工具最多连续 1 次，不触发重复
            // 但连续工具调用数 = 6 >= 5，会触发连续调用阈值
            assertTrue(trigger.shouldTrigger(), "6 次连续调用应触发（非重复）");
            assertTrue(trigger.triggerReason().contains("连续调用"),
                "应为连续调用触发，非重复触发");
        }
    }

    @Nested
    @DisplayName("recordDecision: Replan 处理")
    class RecordDecisionReplanTest {

        @Test
        @DisplayName("Replan 后连续计数重置")
        void replanResetsCount() {
            trigger.recordDecision(new LLMDecision.CallTool(
                new ToolName("tool1"), Map.of(), "r1"));
            trigger.recordDecision(new LLMDecision.CallTool(
                new ToolName("tool2"), Map.of(), "r2"));
            trigger.recordDecision(new LLMDecision.Replan(
                "原因", "新策略", ""));
            trigger.recordDecision(new LLMDecision.CallTool(
                new ToolName("tool3"), Map.of(), "r3"));
            assertFalse(trigger.shouldTrigger(),
                "Replan 后计数重置，不应触发");
        }
    }

    // ==================== Prompt 构建 ====================

    @Nested
    @DisplayName("反思 prompt 构建")
    class ReflectionPromptTest {

        @Test
        @DisplayName("buildReflectionPrompt 包含目标")
        void promptContainsGoal() {
            String prompt = trigger.buildReflectionPrompt("查询订单状态");
            assertTrue(prompt.contains("查询订单状态"));
        }

        @Test
        @DisplayName("buildReflectionPrompt 包含触发原因")
        void promptContainsReason() {
            trigger.checkBudgetTrigger(600, 1000);
            String prompt = trigger.buildReflectionPrompt("任务");
            assertTrue(prompt.contains("预算消耗已过半"),
                "prompt 应包含触发原因");
        }
    }

    // ==================== 重置 ====================

    @Nested
    @DisplayName("重置")
    class ResetTest {

        @Test
        @DisplayName("reset 后 can re-trigger")
        void reset_allowsReTrigger() {
            assertTrue(trigger.checkBudgetTrigger(600, 1000));
            trigger.reset();
            assertFalse(trigger.shouldTrigger(), "reset 后不应触发");
            assertNull(trigger.triggerReason(), "reset 后原因应清空");
        }

        @Test
        @DisplayName("reset 后可再次通过工具调用触发")
        void reset_thenToolCallTrigger() {
            for (int i = 0; i < 5; i++) {
                trigger.recordToolCall();
            }
            assertTrue(trigger.shouldTrigger());
            trigger.reset();

            // 再次触发
            for (int i = 0; i < 5; i++) {
                trigger.recordToolCall();
            }
            assertTrue(trigger.shouldTrigger(), "reset 后可再次触发");
        }
    }
}
