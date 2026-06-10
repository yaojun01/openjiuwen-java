package com.openjiuwen.runtime.beta.reflection;
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
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.BudgetLimits;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * 重规划可行性检查——在 LLM 提出 Replan 后评估新路径是否值得尝试。
 *
 * 核心问题：防止"假 replan"（新路径和旧路径一样）和"无效 replan"（新路径明显不可行）。
 *
 * 检查维度：
 * 1. 差异分析：新 Plan 和上一次 Plan 是否有实质区别
 * 2. 资源可行性：剩余预算是否足以执行新 Plan
 * 3. 历史相似度：新 Plan 是否和之前失败的某个 Plan 雷同
 * 4. 工具可达性：新 Plan 需要的工具是否在白名单中
 *
 * 超限后处理（当 replanCount >= maxReplanCount）：
 * - ESCALATE：升级到人工决策
 * - GIVE_UP：放弃任务
 * - DOWNGRADE_ALPHA：降级到 Alpha 策略（结构化执行）
 */
public interface ReplanFeasibilityCheck {

    /**
     * 评估一个 replan 提议的可行性。
     *
     * @param replan         LLM 的 Replan 决策
     * @param goal           当前 GoalSpec（含 replanHistory）
     * @param decisionHistory 决策历史
     * @param budgetLimits   当前预算
     * @return 可行性评估结果
     */
    FeasibilityResult assess(
        LLMDecision.Replan replan,
        GoalSpec goal,
        List<LLMDecision> decisionHistory,
        BudgetLimits budgetLimits
    );

    /**
     * 可行性评估结果。
     *
     * @param feasible    是否可行
     * @param score       可行性得分 0.0-1.0
     * @param reason      评估原因
     * @param suggestion  建议的替代操作（当不可行时）
     * @param exceededAction 超限后的处理策略
     * @param timestamp   评估时间
     */
    record FeasibilityResult(
        boolean feasible,
        double score,
        String reason,
        String suggestion,
        ExceededAction exceededAction,
        Instant timestamp
    ) {}

    /**
     * 超限后的处理策略。
     */
    enum ExceededAction {
        /** 升级到人工决策 */
        ESCALATE,
        /** 放弃任务 */
        GIVE_UP,
        /** 降级到 Alpha 策略（结构化执行） */
        DOWNGRADE_ALPHA
    }
}
