package com.openjiuwen.runtime.criteria.model;

/**
 * 单条成功标准的验证结果。
 *
 * Agent 执行完成后，CriteriaVerifier 逐条检查 successCriteria，
 * 每条产生一个 CriteriaVerificationResult。
 *
 * sealed interface 确保所有验证状态被枚举：
 * - Satisfied:   满足（通过）
 * - NotSatisfied: 不满足（需处理）
 * - Inconclusive: 无法判定（信息不足）
 */
public sealed interface CriteriaVerificationResult
    permits CriteriaVerificationResult.Satisfied,
            CriteriaVerificationResult.NotSatisfied,
            CriteriaVerificationResult.Inconclusive {

    /** 被验证的标准 */
    VerifiedCriterion criterion();

    /** 验证方法 */
    VerificationMethod method();

    /** LLM 或规则判断的证据 */
    String evidence();

    /** 验证方法枚举 */
    enum VerificationMethod {
        RULE_BASED,     // 规则判断（确定性）
        LLM_JUDGED,     // LLM 判断（概率性）
        HUMAN_CONFIRMED // 人工确认
    }

    /** 是否满足 */
    boolean isSatisfied();

    /**
     * 满足——标准已达成。
     */
    record Satisfied(
        VerifiedCriterion criterion,
        VerificationMethod method,
        String evidence
    ) implements CriteriaVerificationResult {
        @Override public boolean isSatisfied() { return true; }
    }

    /**
     * 不满足——标准未达成，需处理。
     */
    record NotSatisfied(
        VerifiedCriterion criterion,
        VerificationMethod method,
        String evidence,
        RemediationAction remediation
    ) implements CriteriaVerificationResult {
        @Override public boolean isSatisfied() { return false; }
    }

    /**
     * 无法判定——信息不足，需要更多上下文。
     */
    record Inconclusive(
        VerifiedCriterion criterion,
        VerificationMethod method,
        String evidence,
        String missingInfo
    ) implements CriteriaVerificationResult {
        @Override public boolean isSatisfied() { return false; }
    }

    /**
     * 不满足时的补救动作。
     */
    enum RemediationAction {
        REPLAN,          // 强制 replan，重新执行
        WARN_CONTINUE,   // 警告但继续（非关键标准）
        ESCALATE_HUMAN   // 上报人类处理
    }
}
