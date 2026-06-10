package com.openjiuwen.runtime.beta.model;

import java.util.List;
import java.util.Map;

/**
 * 目标规格——Beta 策略的输入。
 *
 * Beta 策略不使用 TaskGraph，而是给 LLM 一个目标，
 * 让它自主决定如何完成。GoalSpec 是目标的结构化描述。
 *
 * v2 扩展：
 * - maxReplanCount: 最大重规划次数（防止无限 replan）
 * - replanHistory: replan 历史（用于差异分析，检测"假 replan"）
 *
 * @param goal            目标描述（如 "处理退款申请 #12345"）
 * @param successCriteria 成功标准列表（判断任务是否完成的依据）
 * @param context         上下文信息（业务数据摘要、历史操作等）
 * @param priority        优先级（1-5，1=最高）
 * @param maxReplanCount  最大重规划次数（默认 5）
 * @param replanHistory   重规划历史（只读，每次 replan 追加）
 */
public record GoalSpec(
    String goal,
    List<String> successCriteria,
    Map<String, String> context,
    int priority,
    int maxReplanCount,
    List<ReplanRecord> replanHistory
) {

    public GoalSpec {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("目标不能为空");
        successCriteria = successCriteria == null ? List.of() : List.copyOf(successCriteria);
        context = context == null ? Map.of() : Map.copyOf(context);
        if (priority < 1 || priority > 5) priority = 3;
        if (maxReplanCount <= 0) maxReplanCount = 5;
        replanHistory = replanHistory == null ? List.of() : List.copyOf(replanHistory);
    }

    /** 向后兼容的紧凑构造函数 */
    public GoalSpec(String goal, List<String> successCriteria, Map<String, String> context, int priority) {
        this(goal, successCriteria, context, priority, 5, List.of());
    }

    /** 创建简单目标 */
    public static GoalSpec of(String goal) {
        return new GoalSpec(goal, List.of(), Map.of(), 3);
    }

    /** 创建带成功标准的目标 */
    public static GoalSpec of(String goal, List<String> successCriteria) {
        return new GoalSpec(goal, successCriteria, Map.of(), 3);
    }

    /** 创建带成功标准和 replan 上限的目标 */
    public static GoalSpec of(String goal, List<String> successCriteria, int maxReplanCount) {
        return new GoalSpec(goal, successCriteria, Map.of(), 3, maxReplanCount, List.of());
    }

    /** 当前已 replan 次数 */
    public int replanCount() {
        return replanHistory.size();
    }

    /** 是否还可以 replan */
    public boolean canReplan() {
        return replanCount() < maxReplanCount;
    }

    /** 追加一条 replan 记录，返回新的 GoalSpec（不可变） */
    public GoalSpec withReplan(ReplanRecord record) {
        var newHistory = new java.util.ArrayList<>(replanHistory);
        newHistory.add(record);
        return new GoalSpec(goal, successCriteria, context, priority, maxReplanCount, newHistory);
    }

    /**
     * 单条 replan 记录。
     *
     * @param stepIndex    replan 发生时的步数
     * @param reason       replan 原因
     * @param newApproach  新的执行策略描述
     */
    public record ReplanRecord(
        int stepIndex,
        String reason,
        String newApproach
    ) {}
}
