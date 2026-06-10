package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.orchestrator.DefaultDecisionPromptBuilder;
import com.openjiuwen.runtime.beta.orchestrator.DecisionPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultDecisionPromptBuilder 测试——验证 prompt 构建的各个区域。
 */
@DisplayName("DefaultDecisionPromptBuilder: prompt 构建")
class DefaultDecisionPromptBuilderTest {

    private DefaultDecisionPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DefaultDecisionPromptBuilder();
    }

    @Nested
    @DisplayName("完整 DecisionContext")
    class FullContextTest {

        @Test
        @DisplayName("所有字段非空时 prompt 包含所有区域")
        void allFieldsPresent() {
            DecisionPromptBuilder.DecisionContext ctx = new DecisionPromptBuilder.DecisionContext(
                "处理退款申请",
                "1. 退款金额正确\n2. 退款已到账",
                "Token: 1000/10000 | LLM: 2/10",
                "[早期摘要] 查询了订单\n[近期] 调用 calc",
                "search, calc, refund",
                "预算消耗已过半",
                1,
                5,
                8
            );

            String prompt = builder.build(ctx);

            assertTrue(prompt.contains("处理退款申请"), "应包含目标");
            assertTrue(prompt.contains("退款金额正确"), "应包含成功标准");
            assertTrue(prompt.contains("Token: 1000/10000"), "应包含预算");
            assertTrue(prompt.contains("search, calc, refund"), "应包含可用工具");
            assertTrue(prompt.contains("预算消耗已过半"), "应包含反思提示");
            assertTrue(prompt.contains("重规划次数: 1 / 5"), "应包含 replan 计数");
            assertTrue(prompt.contains("当前步数: 8"), "应包含步数");
            assertTrue(prompt.contains("call_tool"), "应包含决策类型参考");
            assertTrue(prompt.contains("请做出你的下一个决策"), "应包含尾部指示");
        }
    }

    @Nested
    @DisplayName("可选字段为空时")
    class OptionalFieldsTest {

        @Test
        @DisplayName("successCriteria 为空时 prompt 不包含成功标准标题区域")
        void noSuccessCriteria() {
            DecisionPromptBuilder.DecisionContext ctx = new DecisionPromptBuilder.DecisionContext(
                "简单任务", "", "Token: 100/10000 | LLM: 1/10",
                "", "", null, 0, 5, 1
            );

            String prompt = builder.build(ctx);

            assertFalse(prompt.contains("# 成功标准"),
                "空 successCriteria 不应出现 # 成功标准 标题");
            assertFalse(prompt.contains("[系统警告]"),
                "null reflectionHint 不应出现反思区域");
            assertFalse(prompt.contains("# 可用工具"),
                "空 availableTools 不应出现工具区域");
        }

        @Test
        @DisplayName("reflectionHint 为空字符串时不出现反思区域")
        void emptyReflectionHint() {
            DecisionPromptBuilder.DecisionContext ctx = new DecisionPromptBuilder.DecisionContext(
                "任务", null, "预算", "", "", "", 0, 5, 1
            );

            String prompt = builder.build(ctx);
            assertFalse(prompt.contains("系统警告"),
                "空字符串 reflectionHint 不应出现反思区域");
        }

        @Test
        @DisplayName("compressedHistory 非空时包含决策历史区域")
        void withHistory() {
            DecisionPromptBuilder.DecisionContext ctx = new DecisionPromptBuilder.DecisionContext(
                "任务", null, "预算",
                "步骤1: 调用工具A\n步骤2: 思考", "", null, 0, 5, 2
            );

            String prompt = builder.build(ctx);
            assertTrue(prompt.contains("你已做的决策历史"),
                "有历史时应包含决策历史区域");
            assertTrue(prompt.contains("步骤1: 调用工具A"),
                "应包含历史内容");
        }
    }

    @Nested
    @DisplayName("决策类型参考")
    class DecisionTypeReferenceTest {

        @Test
        @DisplayName("prompt 包含所有 7 种决策类型的 JSON 格式说明")
        void allDecisionTypesPresent() {
            DecisionPromptBuilder.DecisionContext ctx = new DecisionPromptBuilder.DecisionContext(
                "任务", null, "预算", "", "", null, 0, 5, 1
            );

            String prompt = builder.build(ctx);

            assertTrue(prompt.contains("call_tool"), "应包含 call_tool");
            assertTrue(prompt.contains("continue_thinking"), "应包含 continue_thinking");
            assertTrue(prompt.contains("spawn_sub_tasks"), "应包含 spawn_sub_tasks");
            assertTrue(prompt.contains("request_human_help"), "应包含 request_human_help");
            assertTrue(prompt.contains("replan"), "应包含 replan");
            assertTrue(prompt.contains("complete"), "应包含 complete");
            assertTrue(prompt.contains("give_up"), "应包含 give_up");
        }
    }
}
