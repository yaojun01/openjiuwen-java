package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GuardrailEngine 测试——验证 5 个内置护栏的触发条件和拦截行为。
 */
@DisplayName("GuardrailEngine: 护栏检查")
class GuardrailEngineTest {

    private GuardrailEngine engine;
    private TaskId taskId;

    @BeforeEach
    void setUp() {
        engine = new GuardrailEngine();
        taskId = TaskId.generate();
    }

    private Guardrail.GuardrailContext createContext(BudgetLimits budget) {
        return new Guardrail.GuardrailContext(taskId, budget, List.of(), Map.of());
    }

    private Guardrail.GuardrailContext createContext(BudgetLimits budget, List<LLMDecision> history) {
        return new Guardrail.GuardrailContext(taskId, budget, history, Map.of());
    }

    private Guardrail.GuardrailContext createContextWithWhitelist(BudgetLimits budget, Set<String> whitelist) {
        return new Guardrail.GuardrailContext(taskId, budget, List.of(),
            Map.of("toolWhitelist", whitelist));
    }

    private BudgetLimits freshBudget() {
        return BudgetLimits.start(Budget.Fixed.productionDefault());
    }

    @Nested
    @DisplayName("正常决策通过")
    class PassThroughTest {

        @Test
        @DisplayName("正常 CallTool 决策通过所有护栏")
        void normalCallTool_passes() {
            LLMDecision.CallTool callTool = new LLMDecision.CallTool(
                new ToolName("search"), Map.of("q", "test"), "搜索");

            Guardrail.GuardrailResult result = engine.evaluate(callTool, createContext(freshBudget()));
            assertTrue(result.passed(), "正常决策应通过护栏");
        }

        @Test
        @DisplayName("正常 Complete 决策通过")
        void normalComplete_passes() {
            LLMDecision.Complete complete = new LLMDecision.Complete("完成", 0.95, "完成");

            Guardrail.GuardrailResult result = engine.evaluate(complete, createContext(freshBudget()));
            assertTrue(result.passed());
        }
    }

    @Nested
    @DisplayName("预算护栏")
    class BudgetGuardrailTest {

        @Test
        @DisplayName("预算耗尽时拦截决策")
        void budgetExhausted_blocks() {
            // 创建已耗尽的预算（token 用完）
            BudgetLimits exhausted = BudgetLimits.start(new Budget.Fixed(100, 10, 100L, 60000L));
            exhausted = exhausted.recordLLMCall(60);
            exhausted = exhausted.recordLLMCall(60); // 120/100 token → exceeded

            LLMDecision.CallTool callTool = new LLMDecision.CallTool(
                new ToolName("search"), Map.of(), "搜索");

            Guardrail.GuardrailResult result = engine.evaluate(callTool, createContext(exhausted));
            assertFalse(result.passed(), "预算耗尽应拦截决策");
            assertTrue(result.reason().contains("预算已耗尽") || result.reason().contains("exceeded"),
                "拒绝原因应提到预算耗尽");
        }
    }

    @Nested
    @DisplayName("重复护栏")
    class RepetitionGuardrailTest {

        @Test
        @DisplayName("连续 3 次相同工具调用后第 4 次被拦截")
        void repeatedToolCalls_blocked() {
            LLMDecision.CallTool repeated = new LLMDecision.CallTool(
                new ToolName("sameTool"), Map.of("q", "same"), "重复搜索");

            // 历史中已有 3 次相同调用
            var history = List.<LLMDecision>of(
                new LLMDecision.CallTool(new ToolName("sameTool"), Map.of("q", "same"), "搜索1"),
                new LLMDecision.CallTool(new ToolName("sameTool"), Map.of("q", "same"), "搜索2"),
                new LLMDecision.CallTool(new ToolName("sameTool"), Map.of("q", "same"), "搜索3")
            );

            Guardrail.GuardrailContext ctx = new Guardrail.GuardrailContext(
                taskId, freshBudget(), history, Map.of());
            Guardrail.GuardrailResult result = engine.evaluate(repeated, ctx);
            assertFalse(result.passed(), "连续 3 次相同工具调用后应拦截");
            assertTrue(result.reason().contains("sameTool"),
                "拒绝原因应包含工具名");
        }

        @Test
        @DisplayName("不同工具调用不受重复护栏影响")
        void differentTools_notBlocked() {
            var history = List.<LLMDecision>of(
                new LLMDecision.CallTool(new ToolName("toolA"), Map.of(), "调用A"),
                new LLMDecision.CallTool(new ToolName("toolA"), Map.of(), "调用A"),
                new LLMDecision.CallTool(new ToolName("toolA"), Map.of(), "调用A")
            );

            LLMDecision.CallTool newTool = new LLMDecision.CallTool(
                new ToolName("toolB"), Map.of(), "新工具");
            Guardrail.GuardrailContext ctx = new Guardrail.GuardrailContext(
                taskId, freshBudget(), history, Map.of());
            Guardrail.GuardrailResult result = engine.evaluate(newTool, ctx);
            assertTrue(result.passed(), "不同工具不应触发重复护栏");
        }
    }

    @Nested
    @DisplayName("置信度护栏")
    class ConfidenceGuardrailTest {

        @Test
        @DisplayName("低置信度 Complete 被拦截")
        void lowConfidenceComplete_blocked() {
            LLMDecision.Complete lowConf = new LLMDecision.Complete(
                "不确定的结果", 0.3, "低置信度完成");

            Guardrail.GuardrailResult result = engine.evaluate(lowConf, createContext(freshBudget()));
            assertFalse(result.passed(), "低置信度 Complete 应被拦截");
            assertTrue(result.reason().contains("0.3"),
                "拒绝原因应包含置信度值");
        }

        @Test
        @DisplayName("高置信度 Complete 通过")
        void highConfidenceComplete_passes() {
            LLMDecision.Complete highConf = new LLMDecision.Complete(
                "确定的结果", 0.95, "高置信度完成");

            Guardrail.GuardrailResult result = engine.evaluate(highConf, createContext(freshBudget()));
            assertTrue(result.passed(), "高置信度 Complete 应通过");
        }
    }

    @Nested
    @DisplayName("工具白名单护栏")
    class ToolWhitelistGuardrailTest {

        @Test
        @DisplayName("白名单内的工具通过")
        void whitelistedTool_passes() {
            LLMDecision.CallTool callTool = new LLMDecision.CallTool(
                new ToolName("search"), Map.of(), "搜索");

            Guardrail.GuardrailResult result = engine.evaluate(
                callTool, createContextWithWhitelist(freshBudget(), Set.of("search", "calc")));
            assertTrue(result.passed(), "白名单内的工具应通过");
        }

        @Test
        @DisplayName("白名单外的工具被拦截")
        void nonWhitelistedTool_blocked() {
            LLMDecision.CallTool callTool = new LLMDecision.CallTool(
                new ToolName("dangerous_tool"), Map.of(), "危险操作");

            Guardrail.GuardrailResult result = engine.evaluate(
                callTool, createContextWithWhitelist(freshBudget(), Set.of("search", "calc")));
            assertFalse(result.passed(), "白名单外的工具应被拦截");
            assertTrue(result.reason().contains("dangerous_tool"),
                "拒绝原因应包含工具名");
        }

        @Test
        @DisplayName("空白名单时所有工具通过")
        void emptyWhitelist_allPass() {
            LLMDecision.CallTool callTool = new LLMDecision.CallTool(
                new ToolName("any_tool"), Map.of(), "任意操作");

            Guardrail.GuardrailResult result = engine.evaluate(
                callTool, createContextWithWhitelist(freshBudget(), Set.of()));
            assertTrue(result.passed(), "空白名单应放行所有工具");
        }
    }
}
