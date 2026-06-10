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

import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.BudgetLimits;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * 目标漂移检测——检查子 Agent 是否偏离了父 Agent 的原始目标。
 *
 * 两种检查时机：
 * 1. 每步检查（lightweight）：基于决策类型和工具调用模式的快速规则判断
 * 2. FinalAnswer 前检查（deep）：基于 LLM 判断的深度对齐度评估
 *
 * 适用场景：
 * - 子 Agent（通过 parentGoal 判断是否偏离）
 * - 主 Agent 步数超过 15 时（防止长链路漂移）
 *
 * 检测到漂移后的处理：
 * - alignment < 0.3：注入强纠正提示
 * - alignment 0.3-0.6：注入警告提示
 * - alignment > 0.6：不做干预
 */
public interface GoalAlignmentCheck {

    /**
     * 检查当前决策历史与目标的对齐度。
     *
     * @param parentGoal      父 Agent 的目标（主 Agent 传 null）
     * @param currentGoal     当前 Agent 的目标
     * @param decisionHistory 决策历史
     * @param currentStep     当前步数
     * @return 对齐度检查结果
     */
    AlignmentResult checkAlignment(
        String parentGoal,
        String currentGoal,
        List<LLMDecision> decisionHistory,
        int currentStep
    );

    /**
     * 对齐度检查结果。
     *
     * @param score     对齐度 0.0-1.0（0=完全偏离，1=完全对齐）
     * @param reason    判断原因
     * @param injection 需要注入的纠正提示（null 表示不需要纠正）
     * @param timestamp 检查时间
     */
    record AlignmentResult(
        double score,
        String reason,
        String injection,
        Instant timestamp
    ) {
        /** 是否需要干预 */
        public boolean needsIntervention() {
            return score < 0.6;
        }

        /** 是否严重偏离（需要强纠正） */
        public boolean isSevereDrift() {
            return score < 0.3;
        }
    }
}
