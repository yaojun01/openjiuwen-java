package com.openjiuwen.core.alpha.model;

/**
 * 执行策略配置——Alpha 策略的核心参数。
 *
 * 控制 Plan-Execute-Verify 的行为：
 * - planningMode: 规划的自主程度
 * - verifyMode: 验证的严格程度
 * - maxRetries: 验证失败后的最大重试次数
 * - parallelism: 同层节点的最大并行数
 */
public record ExecutionPolicy(
    PlanningMode planningMode,
    VerifyMode verifyMode,
    int maxRetries,
    int maxParallelism,
    boolean enableAdaptiveReplanning
) {

    /** 默认生产配置 */
    public static ExecutionPolicy productionDefault() {
        return new ExecutionPolicy(
            PlanningMode.SEMI_AUTO,
            VerifyMode.STRICT,
            3,
            4,
            true
        );
    }

    /** 开发调试配置：宽松 */
    public static ExecutionPolicy developmentDefault() {
        return new ExecutionPolicy(
            PlanningMode.AUTO,
            VerifyMode.LIGHT,
            5,
            8,
            true
        );
    }
}
