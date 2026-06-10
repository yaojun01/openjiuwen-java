package com.openjiuwen.runtime.beta.orchestrator;
/**
 * ============================================================
 *  P2 DRAFT -- NOT part of P1 default compilation.
 *
 * This file belongs to the `runtime-beta` module, which is excluded from
 * P1's default Maven profile. It is only compiled with `-P all`.
 *
 * P2 will replace this draft with the final implementation.
 * See: docs/architecture/05-beta-llm-autonomous-orchestration.md
 * ============================================================
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.ToolName;

import java.util.*;

/**
 * JSON 格式的决策解析器。
 *
 * 解析策略：
 * 1. 尝试从 LLM 输出中提取 JSON 块（```json ... ``` 或纯 JSON）
 * 2. 根据 "type" 字段路由到对应的决策类型
 * 3. 解析失败时返回 empty，调用方降级为 ContinueThinking
 *
 * 支持的 JSON 格式：
 * <pre>
 * // CallTool
 * {"type":"call_tool","reasoning":"...","tool":"query_sales","args":{"company":"A"}}
 *
 * // ContinueThinking
 * {"type":"continue_thinking","thought":"...","next_question":"..."}
 *
 * // SpawnSubTasks
 * {"type":"spawn_sub_tasks","reasoning":"...","sub_goals":[{"goal":"...","successCriteria":[...]}]}
 *
 * // RequestHumanHelp
 * {"type":"request_human_help","question":"...","context":"..."}
 *
 * // Replan
 * {"type":"replan","reasoning":"...","replan_reason":"...","new_approach":"..."}
 *
 * // Complete
 * {"type":"complete","output":"...","confidence":0.9,"summary":"..."}
 *
 * // GiveUp
 * {"type":"give_up","reason":"...","partial_result":"..."}
 * </pre>
 */
public class JsonDecisionParser implements DecisionParser {

    private final ObjectMapper objectMapper;

    public JsonDecisionParser() {
        this.objectMapper = new ObjectMapper();
    }

    public JsonDecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<LLMDecision> parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return Optional.empty();
        }

        // 步骤 1: 提取 JSON 块
        String json = extractJson(llmOutput);
        if (json == null) {
            return Optional.empty();
        }

        // 步骤 2: 解析 JSON
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }

        // 步骤 3: 根据 type 路由
        String type = root.path("type").asText("");
        return switch (type) {
            case "call_tool"        -> parseCallTool(root);
            case "continue_thinking" -> parseContinueThinking(root);
            case "spawn_sub_tasks"  -> parseSpawnSubTasks(root);
            case "request_human_help" -> parseRequestHumanHelp(root);
            case "replan"           -> parseReplan(root);
            case "complete"         -> parseComplete(root);
            case "give_up"          -> parseGiveUp(root);
            default                 -> Optional.empty();
        };
    }

    // ==================== 私有解析方法 ====================

    private Optional<LLMDecision> parseCallTool(JsonNode node) {
        String tool = node.path("tool").asText(null);
        if (tool == null || tool.isBlank()) return Optional.empty();

        String reasoning = node.path("reasoning").asText("");

        // args 可选
        Map<String, Object> args = Map.of();
        if (node.has("args") && node.get("args").isObject()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.convertValue(node.get("args"), Map.class);
                args = parsed != null ? parsed : Map.of();
            } catch (Exception ignored) {
                args = Map.of();
            }
        }

        return Optional.of(new LLMDecision.CallTool(
            new ToolName(tool), args, reasoning
        ));
    }

    private Optional<LLMDecision> parseContinueThinking(JsonNode node) {
        String thought = node.path("thought").asText("");
        String nextQuestion = node.path("next_question").asText("");
        if (thought.isBlank()) return Optional.empty();
        return Optional.of(new LLMDecision.ContinueThinking(thought, nextQuestion));
    }

    private Optional<LLMDecision> parseSpawnSubTasks(JsonNode node) {
        String reasoning = node.path("reasoning").asText("");
        JsonNode subGoalsNode = node.path("sub_goals");
        if (!subGoalsNode.isArray() || subGoalsNode.isEmpty()) return Optional.empty();

        List<GoalSpec> subGoals = new ArrayList<>();
        for (JsonNode sg : subGoalsNode) {
            String goal = sg.path("goal").asText("");
            if (goal.isBlank()) continue;

            List<String> criteria = new ArrayList<>();
            JsonNode criteriaNode = sg.path("successCriteria");
            if (criteriaNode.isArray()) {
                for (JsonNode c : criteriaNode) {
                    criteria.add(c.asText(""));
                }
            }
            subGoals.add(GoalSpec.of(goal, criteria));
        }

        if (subGoals.isEmpty()) return Optional.empty();
        return Optional.of(new LLMDecision.SpawnSubTasks(subGoals, reasoning));
    }

    private Optional<LLMDecision> parseRequestHumanHelp(JsonNode node) {
        String question = node.path("question").asText("");
        String ctx = node.path("context").asText("");
        if (question.isBlank()) return Optional.empty();
        return Optional.of(new LLMDecision.RequestHumanHelp(question, ctx));
    }

    private Optional<LLMDecision> parseReplan(JsonNode node) {
        String replanReason = node.path("replan_reason").asText("");
        String newApproach = node.path("new_approach").asText("");
        String reasoning = node.path("reasoning").asText("");
        if (replanReason.isBlank() || newApproach.isBlank()) return Optional.empty();
        return Optional.of(new LLMDecision.Replan(replanReason, newApproach, reasoning));
    }

    private Optional<LLMDecision> parseComplete(JsonNode node) {
        String output = node.path("output").asText("");
        double confidence = node.path("confidence").asDouble(0.5);
        String summary = node.path("summary").asText("");
        if (output.isBlank()) return Optional.empty();
        return Optional.of(new LLMDecision.Complete(output, confidence, summary));
    }

    private Optional<LLMDecision> parseGiveUp(JsonNode node) {
        String reason = node.path("reason").asText("");
        String partialResult = node.path("partial_result").asText("");
        if (reason.isBlank()) return Optional.empty();
        return Optional.of(new LLMDecision.GiveUp(reason, partialResult));
    }

    // ==================== JSON 提取 ====================

    /**
     * 从 LLM 输出中提取 JSON。
     * 支持三种格式：
     * 1. 纯 JSON（以 { 开头）
     * 2. Markdown 代码块（```json ... ```）
     * 3. 混合文本中的第一个 JSON 对象
     */
    private String extractJson(String text) {
        // 尝试 1: Markdown 代码块
        int codeStart = text.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = codeStart + 7; // skip ```json
            int codeEnd = text.indexOf("```", jsonStart);
            if (codeEnd > jsonStart) {
                return text.substring(jsonStart, codeEnd).trim();
            }
        }

        // 尝试 2: 找第一个 { 到最后一个 }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }

        return null;
    }
}
