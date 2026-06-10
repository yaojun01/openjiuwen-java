package com.openjiuwen.runtime.beta.orchestrator;

/**
 * 决策 Prompt 构建器——为 LLM 的每一步决策构造 System Prompt。
 *
 * 职责：
 * 1. 将 GoalSpec、成功标准、预算剩余、决策历史等组织为 LLM 可理解的上下文
 * 2. 告诉 LLM 有哪些决策类型可用、JSON 输出格式是什么
 * 3. 注入反思触发信息（连续工具调用过多等）
 *
 * 设计原则：
 * - System Prompt 中的"决策类型说明"是 LLM 自主编排的"方向盘"
 * - 预算和约束信息必须实时更新（每次循环重新构造）
 * - 历史决策通过 ContextWindowManager 压缩后注入
 */
public interface DecisionPromptBuilder {

    /**
     * 构建完整的决策 Prompt。
     *
     * @param ctx 决策上下文
     * @return 给 LLM 的完整 prompt
     */
    String build(DecisionContext ctx);

    /**
     * 决策上下文——构建 prompt 所需的全部信息。
     *
     * @param goal              目标描述
     * @param successCriteria   成功标准（格式化后的文本）
     * @param budgetRemaining   预算剩余信息
     * @param compressedHistory 压缩后的决策历史（由 ContextWindowManager 提供）
     * @param availableTools    可用工具列表
     * @param reflectionHint    反思触发提示（null 表示不触发）
     * @param replanCount       已 replan 次数
     * @param maxReplanCount    最大 replan 次数
     * @param stepCount         当前步数
     */
    record DecisionContext(
        String goal,
        String successCriteria,
        String budgetRemaining,
        String compressedHistory,
        String availableTools,
        String reflectionHint,
        int replanCount,
        int maxReplanCount,
        int stepCount
    ) {}
}
