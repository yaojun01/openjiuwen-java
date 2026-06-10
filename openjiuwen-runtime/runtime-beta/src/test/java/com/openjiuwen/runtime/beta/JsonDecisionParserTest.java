package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.beta.orchestrator.JsonDecisionParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonDecisionParser 测试——验证 7 种 LLMDecision 类型的 JSON 解析。
 *
 * 注意：解析器使用 snake_case 类型名和字段名：
 * call_tool, complete, give_up, continue_thinking, replan, request_human_help
 */
@DisplayName("JsonDecisionParser: 7 种决策解析")
class JsonDecisionParserTest {

    private JsonDecisionParser parser;

    @BeforeEach
    void setUp() {
        parser = new JsonDecisionParser();
    }

    @Nested
    @DisplayName("CallTool 解析")
    class CallToolTest {

        @Test
        @DisplayName("标准 JSON 解析为 CallTool")
        void parseCallTool() {
            String json = """
                {"type":"call_tool","tool":"search","args":{"query":"test"},"reasoning":"需要搜索"}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.CallTool.class, decision);
            var ct = (LLMDecision.CallTool) decision;
            assertEquals("search", ct.toolName().value());
            assertEquals("需要搜索", ct.reasoning());
        }

        @Test
        @DisplayName("markdown 包裹的 JSON 也能解析")
        void parseCallTool_markdownWrapped() {
            String json = """
                ```json
                {"type":"call_tool","tool":"calc","args":{"expr":"1+1"},"reasoning":"计算"}
                ```
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.CallTool.class, decision);
        }
    }

    @Nested
    @DisplayName("Complete 解析")
    class CompleteTest {

        @Test
        @DisplayName("标准 JSON 解析为 Complete")
        void parseComplete() {
            String json = """
                {"type":"complete","output":"任务完成","confidence":0.95,"summary":"已搜索并分析"}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.Complete.class, decision);
            var c = (LLMDecision.Complete) decision;
            assertEquals("任务完成", c.output());
            assertEquals(0.95, c.confidence());
        }
    }

    @Nested
    @DisplayName("GiveUp 解析")
    class GiveUpTest {

        @Test
        @DisplayName("标准 JSON 解析为 GiveUp")
        void parseGiveUp() {
            String json = """
                {"type":"give_up","reason":"预算不足","partial_result":"部分结果"}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.GiveUp.class, decision);
            var gu = (LLMDecision.GiveUp) decision;
            assertEquals("预算不足", gu.reason());
        }
    }

    @Nested
    @DisplayName("ContinueThinking 解析")
    class ContinueThinkingTest {

        @Test
        @DisplayName("标准 JSON 解析为 ContinueThinking")
        void parseContinueThinking() {
            String json = """
                {"type":"continue_thinking","thought":"需要进一步分析数据","next_question":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
            var ct = (LLMDecision.ContinueThinking) decision;
            assertEquals("需要进一步分析数据", ct.thought());
        }
    }

    @Nested
    @DisplayName("Replan 解析")
    class ReplanTest {

        @Test
        @DisplayName("标准 JSON 解析为 Replan")
        void parseReplan() {
            String json = """
                {"type":"replan","replan_reason":"工具调用失败","new_approach":"换用另一个工具","reasoning":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.Replan.class, decision);
            var r = (LLMDecision.Replan) decision;
            assertEquals("工具调用失败", r.replanReason());
            assertEquals("换用另一个工具", r.newApproach());
        }
    }

    @Nested
    @DisplayName("RequestHumanHelp 解析")
    class RequestHumanHelpTest {

        @Test
        @DisplayName("标准 JSON 解析为 RequestHumanHelp")
        void parseRequestHumanHelp() {
            String json = """
                {"type":"request_human_help","question":"是否继续执行？","context":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.RequestHumanHelp.class, decision);
            var h = (LLMDecision.RequestHumanHelp) decision;
            assertEquals("是否继续执行？", h.question());
        }
    }

    @Nested
    @DisplayName("SpawnSubTasks 解析")
    class SpawnSubTasksTest {

        @Test
        @DisplayName("标准 JSON 解析为 SpawnSubTasks")
        void parseSpawnSubTasks() {
            String json = """
                {"type":"spawn_sub_tasks","reasoning":"任务太复杂，需要拆分","sub_goals":[{"goal":"查询订单状态","successCriteria":["订单存在"]},{"goal":"计算退款金额"}]}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.SpawnSubTasks.class, decision);
            var s = (LLMDecision.SpawnSubTasks) decision;
            assertEquals(2, s.subGoals().size());
            assertEquals("查询订单状态", s.subGoals().get(0).goal());
            assertEquals("计算退款金额", s.subGoals().get(1).goal());
            assertEquals("任务太复杂，需要拆分", s.reasoning());
        }

        @Test
        @DisplayName("带 successCriteria 嵌套数组的子目标")
        void parseSpawnSubTasks_withCriteria() {
            String json = """
                {"type":"spawn_sub_tasks","reasoning":"拆分","sub_goals":[{"goal":"子目标1","successCriteria":["标准A","标准B"]}]}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.SpawnSubTasks.class, decision);
            var s = (LLMDecision.SpawnSubTasks) decision;
            assertEquals(List.of("标准A", "标准B"), s.subGoals().get(0).successCriteria());
        }

        @Test
        @DisplayName("空 sub_goals 数组返回 fallback")
        void emptySubGoals_returnsFallback() {
            String json = """
                {"type":"spawn_sub_tasks","reasoning":"无子目标","sub_goals":[]}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("sub_goals 中 goal 为空字符串被跳过，全部为空则 fallback")
        void blankGoalsSkipped() {
            String json = """
                {"type":"spawn_sub_tasks","reasoning":"全空","sub_goals":[{"goal":"","successCriteria":[]},{"goal":"  "}]}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");

            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }
    }

    @Nested
    @DisplayName("Fallback 行为")
    class FallbackTest {

        @Test
        @DisplayName("畸形 JSON 返回 ContinueThinking fallback")
        void malformedJson_returnsFallback() {
            String bad = "这不是JSON at all";
            LLMDecision decision = parser.parseOrFallback(bad, "解析失败");

            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("空字符串返回 fallback")
        void emptyString_returnsFallback() {
            LLMDecision decision = parser.parseOrFallback("", "空输入");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("未知 type 字段返回 fallback")
        void unknownType_returnsFallback() {
            String json = """
                {"type":"UnknownType","data":"something"}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "未知类型");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }
    }

    @Nested
    @DisplayName("必填字段校验")
    class RequiredFieldValidationTest {

        @Test
        @DisplayName("call_tool 缺少 tool 字段返回 fallback")
        void callToolMissingTool_returnsFallback() {
            String json = """
                {"type":"call_tool","args":{},"reasoning":"缺少工具名"}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("continue_thinking 空 thought 返回 fallback")
        void continueThinkingBlankThought_returnsFallback() {
            String json = """
                {"type":"continue_thinking","thought":"","next_question":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("replan 缺少 replan_reason 返回 fallback")
        void replanMissingReason_returnsFallback() {
            String json = """
                {"type":"replan","replan_reason":"","new_approach":"新策略","reasoning":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("replan 缺少 new_approach 返回 fallback")
        void replanMissingApproach_returnsFallback() {
            String json = """
                {"type":"replan","replan_reason":"原因","new_approach":"","reasoning":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("complete 空 output 返回 fallback")
        void completeEmptyOutput_returnsFallback() {
            String json = """
                {"type":"complete","output":"","confidence":0.9,"summary":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("give_up 空 reason 返回 fallback")
        void giveUpEmptyReason_returnsFallback() {
            String json = """
                {"type":"give_up","reason":"","partial_result":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("request_human_help 空 question 返回 fallback")
        void humanHelpEmptyQuestion_returnsFallback() {
            String json = """
                {"type":"request_human_help","question":"","context":""}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("call_tool 非法工具名（含特殊字符）返回 fallback")
        void callToolInvalidToolName_returnsFallback() {
            String json = """
                {"type":"call_tool","tool":"rm -rf /","args":{},"reasoning":"注入"}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.ContinueThinking.class, decision);
        }

        @Test
        @DisplayName("call_tool 工具名前后空格被 trim 后合法")
        void callToolTrimmedName_parsed() {
            String json = """
                {"type":"call_tool","tool":" search ","args":{},"reasoning":"带空格"}
                """;
            LLMDecision decision = parser.parseOrFallback(json, "fallback");
            assertInstanceOf(LLMDecision.CallTool.class, decision);
            assertEquals("search", ((LLMDecision.CallTool) decision).toolName().value());
        }
    }
}
