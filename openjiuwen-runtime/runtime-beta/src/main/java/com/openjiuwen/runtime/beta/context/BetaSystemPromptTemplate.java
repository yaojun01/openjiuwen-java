package com.openjiuwen.runtime.beta.context;
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

import com.openjiuwen.runtime.beta.model.GoalSpec;

import java.util.List;
import java.util.stream.Collectors;

/**
 * System Prompt 模板——Beta 策略的"驾驶员手册"。
 *
 * 职责：为 LLM 构造一个清晰的 System Prompt，告诉它：
 * 1. 你是什么角色
 * 2. 你有哪些决策类型可用（6 种 + Replan）
 * 3. 成功标准是什么
 * 4. 预算剩余多少
 * 5. 输出格式要求（JSON）
 *
 * 设计原则：
 * - System Prompt 在整个执行过程中不变（只有 User Message 每步更新）
 * - 成功标准和约束信息嵌入 System Prompt，LLM 每次推理都能看到
 * - 决策类型的 JSON 格式说明在 System Prompt 中给出，减少 LLM 格式错误
 */
public final class BetaSystemPromptTemplate {

    private BetaSystemPromptTemplate() {} // 工具类，不实例化

    /**
     * 构建完整的 System Prompt。
     *
     * @param agentRole         Agent 角色描述（如 "企业数据分析专家"）
     * @param goal              目标描述
     * @param successCriteria   成功标准列表
     * @param allowedTools      允许使用的工具名列表
     * @param maxReplanCount    最大重规划次数
     */
    public static String build(
            String agentRole,
            String goal,
            List<String> successCriteria,
            List<String> allowedTools,
            int maxReplanCount) {

        return """
            # 角色定义
            你是一个 %s。你具有自主决策能力——你可以自行决定如何达成目标。
            你的每一次决策都将被记录和审计。

            # 目标
            %s

            # 成功标准（任务完成前必须全部满足）
            %s

            # 可用工具
            %s

            # 决策规则
            1. 每次只做一个决策，输出 JSON 格式
            2. reasoning 字段必须填写——解释你的思考过程
            3. 调用工具前先思考：这个工具调用能否推进目标？
            4. 如果连续 3 次工具调用都没推进目标，考虑 replan 或 reflect
            5. 重规划次数上限：%d 次。超过后只能 Complete 或 GiveUp
            6. Complete 的 confidence 低于 0.7 会被护栏拒绝
            7. 给 Complete 前确认所有成功标准都已被你的执行过程覆盖

            # JSON 输出格式
            {"type":"call_tool","reasoning":"...","tool":"工具名","args":{参数}}
            {"type":"continue_thinking","thought":"...","next_question":"..."}
            {"type":"spawn_sub_tasks","reasoning":"...","sub_goals":[{"goal":"...","successCriteria":["..."]}]}
            {"type":"request_human_help","question":"...","context":"..."}
            {"type":"replan","reasoning":"...","replan_reason":"...","new_approach":"..."}
            {"type":"complete","output":"最终答案","confidence":0.9,"summary":"..."}
            {"type":"give_up","reason":"...","partial_result":"..."}

            只输出 JSON，不要输出其他内容。
            """.formatted(
                agentRole != null ? agentRole : "自主决策 Agent",
                goal,
                formatCriteria(successCriteria),
                formatTools(allowedTools),
                maxReplanCount
            );
    }

    /**
     * 构建每步动态更新的 User Message。
     * 包含：预算剩余 + 压缩后的决策历史 + 反思提示（如有）
     *
     * @param budgetRemaining  预算剩余文本
     * @param compressedHistory 压缩后的历史
     * @param reflectionHint   反思提示（null 则不注入）
     * @param stepCount        当前步数
     */
    public static String buildStepMessage(
            String budgetRemaining,
            String compressedHistory,
            String reflectionHint,
            int stepCount) {

        StringBuilder sb = new StringBuilder();
        sb.append("## 步骤 ").append(stepCount).append("\n\n");
        sb.append("## 预算消耗\n").append(budgetRemaining).append("\n\n");

        if (compressedHistory != null && !compressedHistory.isBlank()) {
            sb.append("## 决策历史\n").append(compressedHistory).append("\n\n");
        }

        if (reflectionHint != null && !reflectionHint.isBlank()) {
            sb.append("## [系统注入]\n").append(reflectionHint).append("\n\n");
        }

        sb.append("请做出你的下一步决策。\n");
        return sb.toString();
    }

    private static String formatCriteria(List<String> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return "无显式成功标准（由你自行判断完成度）";
        }
        return criteria.stream()
            .map(c -> "- " + c)
            .collect(Collectors.joining("\n"));
    }

    private static String formatTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return "请参考对话中提供的工具列表";
        }
        return tools.stream()
            .map(t -> "- " + t)
            .collect(Collectors.joining("\n"));
    }
}
