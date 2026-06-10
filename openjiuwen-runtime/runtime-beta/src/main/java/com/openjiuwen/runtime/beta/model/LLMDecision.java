package com.openjiuwen.runtime.beta.model;
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

import com.openjiuwen.core.kernel.model.ToolName;

import java.util.List;
import java.util.Map;

/**
 * LLM 决策——Beta 策略中 LLM 自主做出的决策类型。
 *
 * sealed interface 确保所有决策类型都被显式处理。
 * Beta 策略的 GuardrailEngine 会在每个决策执行前进行检查。
 *
 * v2: 新增 Replan 决策类型，支持 LLM 在执行过程中推翻旧路径、
 * 切换到新策略。与 ContinueThinking 的区别：
 * - ContinueThinking = "我还在想，需要更多信息"
 * - Replan = "之前的路径走不通，我要换一条"
 */
public sealed interface LLMDecision
    permits LLMDecision.CallTool,
            LLMDecision.ContinueThinking,
            LLMDecision.SpawnSubTasks,
            LLMDecision.RequestHumanHelp,
            LLMDecision.Replan,
            LLMDecision.Complete,
            LLMDecision.GiveUp {

    /**
     * 调用工具：LLM 决定调用一个工具。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @param reasoning LLM 的推理过程（为什么选择这个工具）
     */
    record CallTool(
        ToolName toolName,
        Map<String, Object> arguments,
        String reasoning
    ) implements LLMDecision {}

    /**
     * 继续思考：LLM 需要更多信息才能做决策。
     *
     * @param thought     当前思考内容
     * @param nextQuestion 需要回答的问题（可能需要再次调用工具获取信息）
     */
    record ContinueThinking(
        String thought,
        String nextQuestion
    ) implements LLMDecision {}

    /**
     * 生成子任务：LLM 判断当前目标太复杂，需要分解。
     *
     * @param subGoals    子目标列表
     * @param reasoning   为什么要分解
     */
    record SpawnSubTasks(
        List<GoalSpec> subGoals,
        String reasoning
    ) implements LLMDecision {}

    /**
     * 请求人类帮助：LLM 遇到不确定的情况，需要人类介入。
     *
     * @param question  问人类的问题
     * @param context   当前执行上下文摘要
     */
    record RequestHumanHelp(
        String question,
        String context
    ) implements LLMDecision {}

    /**
     * 重规划：LLM 判断当前执行路径不可行，需要换一条路。
     *
     * 与 ContinueThinking 的区别：
     * - ContinueThinking 是"补充信息继续当前路径"
     * - Replan 是"放弃当前路径，换一条"
     *
     * @param replanReason  为什么重规划（外部可审计）
     * @param newApproach   新的执行策略描述
     * @param reasoning     LLM 的推理过程
     */
    record Replan(
        String replanReason,
        String newApproach,
        String reasoning
    ) implements LLMDecision {}

    /**
     * 完成任务：LLM 认为目标已经达成。
     *
     * @param output      最终输出
     * @param confidence  置信度（0.0-1.0）
     * @param summary     执行摘要
     */
    record Complete(
        String output,
        double confidence,
        String summary
    ) implements LLMDecision {}

    /**
     * 放弃任务：LLM 判断无法完成目标。
     *
     * @param reason      放弃原因
     * @param partialResult 部分结果（如果有）
     */
    record GiveUp(
        String reason,
        String partialResult
    ) implements LLMDecision {}
}
