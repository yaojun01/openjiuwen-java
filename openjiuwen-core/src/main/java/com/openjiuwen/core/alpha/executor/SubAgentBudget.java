package com.openjiuwen.core.alpha.executor;

import com.openjiuwen.core.kernel.model.Budget;
import com.openjiuwen.core.kernel.model.NodeId;

/**
 * 子 Agent 预算分配策略。
 *
 * 当 Executor 遇到 SUB_AGENT 类型的节点时，需要为子 Agent 分配预算。
 * 预算不能超过父 Agent 剩余预算。
 *
 * 三种分配策略：
 * - PROPORTIONAL：按剩余预算的比例分配
 * - FIXED：固定预算
 * - INHERIT：继承父 Agent 的预算（减去已消耗部分）
 */
public sealed interface SubAgentBudget
    permits SubAgentBudget.Proportional,
            SubAgentBudget.Fixed,
            SubAgentBudget.Inherit {

    /**
     * 按比例分配。
     * @param ratio 分配比例（0.0 ~ 1.0），如 0.3 表示分配 30% 的剩余预算
     */
    record Proportional(double ratio) implements SubAgentBudget {
        public Proportional {
            if (ratio <= 0 || ratio > 1) {
                throw new IllegalArgumentException("ratio 必须在 (0, 1] 范围内");
            }
        }
    }

    /**
     * 固定预算。
     * 如果剩余预算不足，则用剩余预算。
     */
    record Fixed(Budget budget) implements SubAgentBudget {}

    /**
     * 继承父 Agent 预算。
     * 从父 Agent 的总预算中减去已消耗部分，作为子 Agent 的预算。
     */
    record Inherit() implements SubAgentBudget {}
}
