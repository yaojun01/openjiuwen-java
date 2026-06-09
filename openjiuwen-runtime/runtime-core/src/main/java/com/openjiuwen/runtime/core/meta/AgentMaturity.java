package com.openjiuwen.runtime.core.meta;

import com.openjiuwen.core.dispatch.AutonomyLevel;

import java.time.Instant;

/**
 * Agent 成熟度——衡量一个 Agent 在生产环境中的可靠性。
 *
 * 成熟度决定了 AutonomyLevel 的晋升资格：
 * - 只有经过充分验证的 Agent 才能从 GUIDED 晋升到 AUTONOMOUS
 * - 晋升需要：足够的执行次数 + 高成功率 + 低异常率
 *
 * sealed interface 确保成熟度评估的每个阶段都被显式处理。
 */
public sealed interface AgentMaturity
    permits AgentMaturity.Novel,
            AgentMaturity.Stable,
            AgentMaturity.Proven,
            AgentMaturity.Veteran {

    /** Agent 名称 */
    String agentName();

    /** 当前自主度 */
    AutonomyLevel currentLevel();

    /** 总执行次数 */
    long totalExecutions();

    /** 成功次数 */
    long successCount();

    /** 成功率（0.0-1.0） */
    default double successRate() {
        return totalExecutions() > 0 ? (double) successCount() / totalExecutions() : 0.0;
    }

    /** 是否满足晋升条件 */
    boolean isEligibleForPromotion();

    /** 下一个目标自主度 */
    AutonomyLevel targetLevel();

    /**
     * 新手级：刚创建的 Agent，无执行记录。
     * 只能在 GUIDED 模式运行。
     */
    record Novel(String agentName) implements AgentMaturity {
        @Override public AutonomyLevel currentLevel() { return AutonomyLevel.GUIDED; }
        @Override public long totalExecutions() { return 0; }
        @Override public long successCount() { return 0; }
        @Override public boolean isEligibleForPromotion() { return false; }
        @Override public AutonomyLevel targetLevel() { return AutonomyLevel.ASSISTED; }
    }

    /**
     * 稳定级：经过一定验证，成功率高。
     * 可以晋升到 ASSISTED 模式。
     * 条件：执行 >= 100 次，成功率 >= 95%
     */
    record Stable(String agentName, long totalExecutions, long successCount,
                  Instant firstExecutionAt) implements AgentMaturity {
        @Override public AutonomyLevel currentLevel() { return AutonomyLevel.ASSISTED; }

        @Override public boolean isEligibleForPromotion() {
            return totalExecutions >= 100 && successRate() >= 0.95;
        }
        @Override public AutonomyLevel targetLevel() { return AutonomyLevel.META; }
    }

    /**
     * 已验证级：长期运行稳定，可以生成子 Agent。
     * 可以晋升到 META 模式。
     * 条件：执行 >= 1000 次，成功率 >= 98%
     */
    record Proven(String agentName, long totalExecutions, long successCount,
                  Instant firstExecutionAt, int subAgentsSpawned) implements AgentMaturity {
        @Override public AutonomyLevel currentLevel() { return AutonomyLevel.META; }

        @Override public boolean isEligibleForPromotion() {
            return totalExecutions >= 1000 && successRate() >= 0.98;
        }
        @Override public AutonomyLevel targetLevel() { return AutonomyLevel.AUTONOMOUS; }
    }

    /**
     * 老兵级：完全自主，经过大量验证。
     * 可以在 AUTONOMOUS 模式运行。
     * 条件：执行 >= 5000 次，成功率 >= 99%
     */
    record Veteran(String agentName, long totalExecutions, long successCount,
                   Instant firstExecutionAt, int subAgentsSpawned) implements AgentMaturity {
        @Override public AutonomyLevel currentLevel() { return AutonomyLevel.AUTONOMOUS; }

        @Override public boolean isEligibleForPromotion() { return false; } // 已到顶
        @Override public AutonomyLevel targetLevel() { return AutonomyLevel.AUTONOMOUS; }
    }
}
