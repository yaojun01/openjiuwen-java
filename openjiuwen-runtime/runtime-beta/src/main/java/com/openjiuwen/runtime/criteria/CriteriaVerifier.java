package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import java.util.List;

/**
 * 成功标准验证引擎——Agent 执行完成后的逐条检查。
 *
 * 验证流程：
 * 1. Agent 执行完成，产出最终结果
 * 2. CriteriaVerifier 逐条检查 successCriteria
 * 3. 每条标准通过规则判断或 LLM 判断
 * 4. 汇总验证结果，决定是否通过
 *
 * 不满足时的处理策略：
 * - 关键标准（defaultSelected=true）不满足 → 强制 replan
 * - 非关键标准不满足 → 警告继续
 * - 无法判定 → 上报人类
 *
 * 验证结果反馈给 KnowledgeAccumulator，完成知识沉淀闭环。
 */
public interface CriteriaVerifier {

    /**
     * 逐条验证成功标准。
     *
     * @param criteria      用户确认的成功标准列表
     * @param agentOutput   Agent 的最终执行输出
     * @param executionLog  Agent 的执行过程日志（工具调用记录等）
     * @return 逐条验证结果
     */
    List<CriteriaVerificationResult> verify(
        List<VerifiedCriterion> criteria,
        String agentOutput,
        String executionLog
    );

    /**
     * 汇总判断：所有标准是否都满足。
     */
    default boolean allSatisfied(List<CriteriaVerificationResult> results) {
        return results.stream().allMatch(CriteriaVerificationResult::isSatisfied);
    }

    /**
     * 汇总判断：是否存在必须 replan 的失败标准。
     */
    default boolean requiresReplan(List<CriteriaVerificationResult> results) {
        return results.stream()
            .filter(r -> r instanceof CriteriaVerificationResult.NotSatisfied)
            .map(r -> (CriteriaVerificationResult.NotSatisfied) r)
            .anyMatch(r -> r.remediation() == CriteriaVerificationResult.RemediationAction.REPLAN);
    }

    /**
     * 获取所有不满足的标准（用于反馈给 LLM 重新规划）。
     */
    default List<CriteriaVerificationResult.NotSatisfied> getFailures(
        List<CriteriaVerificationResult> results
    ) {
        return results.stream()
            .filter(r -> r instanceof CriteriaVerificationResult.NotSatisfied)
            .map(r -> (CriteriaVerificationResult.NotSatisfied) r)
            .toList();
    }
}
