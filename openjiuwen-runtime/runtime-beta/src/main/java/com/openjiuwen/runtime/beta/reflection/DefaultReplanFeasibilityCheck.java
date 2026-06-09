package com.openjiuwen.runtime.beta.reflection;

import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.BudgetLimits;

import java.time.Instant;
import java.util.List;

/**
 * 默认重规划可行性检查实现。
 *
 * 三个核心检查（全部基于规则，不调用 LLM）：
 *
 * 1. 差异分析（detectDuplicatePlan）
 *    - 新 Plan 的文本和最近一次 replan 的 newApproach 做文本相似度
 *    - 相似度 > 0.8 → "假 replan"，不可行
 *    - 相似度 0.5-0.8 → 有差异但不大，降低可行性得分
 *
 * 2. 资源可行性（checkResourceFeasibility）
 *    - 剩余 Token < 20% → 不可行
 *    - 剩余工具调用次数 < 3 → 不可行
 *    - 剩余 LLM 调用次数 < 5 → 不可行
 *
 * 3. 历史重复检测（detectHistoricalDuplicate）
 *    - 新 Plan 是否和之前任何一次失败的 replan 路径雷同
 *    - 重复失败路径 → 不可行
 *
 * 超限处理策略选择逻辑：
 * - 有部分结果 → GIVE_UP（保留部分结果）
 * - 剩余预算 > 30% 且目标可结构化 → DOWNGRADE_ALPHA
 * - 其他 → ESCALATE
 */
public class DefaultReplanFeasibilityCheck implements ReplanFeasibilityCheck {

    /** 文本相似度阈值：超过此值视为"假 replan" */
    private static final double DUPLICATE_THRESHOLD = 0.8;
    /** 资源枯竭阈值 */
    private static final double RESOURCE_DEPLETION_THRESHOLD = 0.2;

    /** 超限后的默认处理策略 */
    private final ExceededAction defaultExceededAction;

    public DefaultReplanFeasibilityCheck() {
        this(ExceededAction.ESCALATE);
    }

    public DefaultReplanFeasibilityCheck(ExceededAction defaultExceededAction) {
        this.defaultExceededAction = defaultExceededAction;
    }

    @Override
    public FeasibilityResult assess(
            LLMDecision.Replan replan,
            GoalSpec goal,
            List<LLMDecision> decisionHistory,
            BudgetLimits budgetLimits) {

        // ===== 预检：是否已超限 =====
        if (!goal.canReplan()) {
            return buildExceededResult(replan, goal, decisionHistory, budgetLimits);
        }

        double score = 1.0;
        StringBuilder reasons = new StringBuilder();

        // ===== 检查 1: 差异分析 =====
        double similarity = checkSimilarity(replan.newApproach(), goal.replanHistory());
        if (similarity > DUPLICATE_THRESHOLD) {
            String reason = "新策略与上一次 replan 高度相似(相似度="
                + String.format("%.2f", similarity) + ")，视为无效 replan";
            return new FeasibilityResult(
                false, 0.1, reason,
                "请提出一个实质不同的策略，或者考虑 GiveUp",
                null, Instant.now());
        }
        if (similarity > 0.5) {
            score -= 0.3;
            reasons.append("新策略与上次 replan 有一定相似度(")
                .append(String.format("%.2f", similarity)).append("); ");
        }

        // ===== 检查 2: 资源可行性 =====
        double resourceScore = checkResourceFeasibility(budgetLimits);
        if (resourceScore < 0.2) {
            String reason = "资源严重不足: " + formatBudget(budgetLimits);
            return new FeasibilityResult(
                false, 0.1, reason,
                "剩余资源不足以执行新策略。建议 GiveUp 保存部分结果。",
                null, Instant.now());
        }
        score *= resourceScore;

        // ===== 检查 3: 历史重复检测 =====
        boolean isHistoricalDuplicate = detectHistoricalDuplicate(
            replan.newApproach(), goal.replanHistory());
        if (isHistoricalDuplicate) {
            score -= 0.4;
            reasons.append("新策略与之前失败的路径雷同; ");
        }

        // ===== 综合评估 =====
        boolean feasible = score >= 0.4;
        if (!feasible) {
            reasons.append("综合可行性得分 ").append(String.format("%.2f", score)).append(" 过低");
        }

        return new FeasibilityResult(
            feasible,
            score,
            reasons.length() > 0 ? reasons.toString() : "replan 可行",
            feasible ? null : "建议重新思考策略，或考虑 RequestHumanHelp",
            null,
            Instant.now()
        );
    }

    // ==================== 私有方法 ====================

    /**
     * 构建超限后的处理结果。
     */
    private FeasibilityResult buildExceededResult(
            LLMDecision.Replan replan,
            GoalSpec goal,
            List<LLMDecision> decisionHistory,
            BudgetLimits budgetLimits) {

        // 选择超限策略
        ExceededAction action = determineExceededAction(
            goal, decisionHistory, budgetLimits);

        String reason = "重规划次数已达上限 (" + goal.replanCount()
            + "/" + goal.maxReplanCount() + ")";
        String suggestion = switch (action) {
            case ESCALATE -> "将升级到人工决策。Agent 会暂停并等待人工输入。";
            case GIVE_UP -> "Agent 将放弃任务并返回已有的部分结果。";
            case DOWNGRADE_ALPHA -> "将降级到 Alpha 策略，尝试结构化执行剩余目标。";
        };

        return new FeasibilityResult(
            false, 0.0, reason, suggestion, action, Instant.now());
    }

    /**
     * 确定超限后的处理策略。
     */
    private ExceededAction determineExceededAction(
            GoalSpec goal,
            List<LLMDecision> decisionHistory,
            BudgetLimits budgetLimits) {

        // 策略 1: 如果有部分结果，GiveUp 保留它
        boolean hasPartialResult = decisionHistory.stream()
            .anyMatch(d -> d instanceof LLMDecision.CallTool
                        || d instanceof LLMDecision.Complete);
        if (hasPartialResult && budgetRatio(budgetLimits) < 0.3) {
            return ExceededAction.GIVE_UP;
        }

        // 策略 2: 如果剩余预算 > 30%，尝试降级到 Alpha
        if (budgetRatio(budgetLimits) > 0.3) {
            return ExceededAction.DOWNGRADE_ALPHA;
        }

        // 策略 3: 默认升级到人工
        return defaultExceededAction;
    }

    /**
     * 文本相似度检查：新 Plan 和最近一次 replan 的相似度。
     * 使用简单的词袋模型 Jaccard 相似度。
     */
    private double checkSimilarity(String newApproach, List<GoalSpec.ReplanRecord> replanHistory) {
        if (replanHistory.isEmpty()) return 0.0;

        // 只和最近一次比较
        String lastApproach = replanHistory.get(replanHistory.size() - 1).newApproach();
        return jaccardSimilarity(newApproach, lastApproach);
    }

    /**
     * 历史重复检测：新 Plan 是否和之前任何一次 replan 路径雷同。
     */
    private boolean detectHistoricalDuplicate(
            String newApproach, List<GoalSpec.ReplanRecord> replanHistory) {
        if (replanHistory.size() <= 1) return false;

        // 和最近 3 次 replan 比较（排除最近一次，已在 checkSimilarity 中处理）
        int start = Math.max(0, replanHistory.size() - 4);
        for (int i = start; i < replanHistory.size() - 1; i++) {
            double sim = jaccardSimilarity(newApproach, replanHistory.get(i).newApproach());
            if (sim > 0.7) return true;
        }
        return false;
    }

    /**
     * Jaccard 相似度。
     */
    private double jaccardSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        var setA = java.util.Set.of(a.toLowerCase().split("\\s+"));
        var setB = java.util.Set.of(b.toLowerCase().split("\\s+"));

        long intersection = setA.stream().filter(setB::contains).count();
        long union = setA.size() + setB.size() - intersection;

        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * 资源可行性检查。
     * @return 资源得分 0.0-1.0
     */
    private double checkResourceFeasibility(BudgetLimits bl) {
        double tokenRatio = 1.0 - (double) bl.usedTokens() / bl.budget().maxTokens();
        double llmRatio = 1.0 - (double) bl.usedLLMCalls() / bl.budget().maxLLMCalls();
        double toolRatio = 1.0 - (double) bl.usedToolCalls() / bl.budget().maxToolCalls();

        // 取最短的那块木板
        return Math.min(tokenRatio, Math.min(llmRatio, toolRatio));
    }

    private double budgetRatio(BudgetLimits bl) {
        return 1.0 - (double) bl.usedTokens() / bl.budget().maxTokens();
    }

    private String formatBudget(BudgetLimits bl) {
        return String.format("Token剩余=%.0f%%, LLM调用剩余=%.0f%%, 工具调用剩余=%.0f%%",
            (1.0 - (double) bl.usedTokens() / bl.budget().maxTokens()) * 100,
            (1.0 - (double) bl.usedLLMCalls() / bl.budget().maxLLMCalls()) * 100,
            (1.0 - (double) bl.usedToolCalls() / bl.budget().maxToolCalls()) * 100);
    }
}
