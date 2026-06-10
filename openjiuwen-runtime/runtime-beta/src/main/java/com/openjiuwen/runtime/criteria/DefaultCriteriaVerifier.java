package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult.*;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 默认验证引擎——规则判断 + LLM 判断混合。
 *
 * 验证策略：
 * - 关键词/模式匹配（快速、确定性）：部分标准可用正则或关键词检查
 * - LLM 判断（灵活、概率性）：无法用规则判断的标准交给 LLM
 *
 * 标准类型到验证方法的映射：
 * - 包含"必须"/"不允许"/"禁止" → 先尝试规则判断
 * - 包含"覆盖率"/"准确率"/数值指标 → 规则判断（解析数值）
 * - 其他 → LLM 判断
 *
 * 不满足时的策略选择：
 * - 原始提案是 TemplateProposal 且 defaultSelected=true → REPLAN
 * - 原始提案是 TemplateProposal 且 defaultSelected=false → WARN_CONTINUE
 * - 其他来源 → ESCALATE_HUMAN
 */
public class DefaultCriteriaVerifier implements CriteriaCheckEngine {

    /** 可用规则判断的关键词模式 */
    private static final Set<String> RULE_KEYWORDS = Set.of(
        "必须", "不允许", "禁止", "不得", "误差", "覆盖率", "准确率",
        "完成", "包含", "标注", "列出"
    );

    @Override
    public List<CriteriaVerificationResult> verify(
        List<VerifiedCriterion> criteria,
        String agentOutput,
        String executionLog
    ) {
        List<CriteriaVerificationResult> results = new ArrayList<>();

        for (VerifiedCriterion criterion : criteria) {
            CriteriaVerificationResult result = verifyOne(criterion, agentOutput, executionLog);
            results.add(result);
        }

        return results;
    }

    /**
     * 验证单条标准。
     */
    private CriteriaVerificationResult verifyOne(
        VerifiedCriterion criterion,
        String agentOutput,
        String executionLog
    ) {
        // 1. 尝试规则判断（同时检查最终描述和原始提案描述，用户覆盖不应丢失规则关键词）
        if (canRuleCheck(criterion.finalDescription())
            || canRuleCheck(criterion.originalProposal().description())) {
            return ruleCheck(criterion, agentOutput, executionLog);
        }

        // 2. 交给 LLM 判断（此处简化实现，返回 Inconclusive）
        //    实际实现会调用 AgentKernel.think() 进行结构化判断
        return new Inconclusive(
            criterion,
            VerificationMethod.LLM_JUDGED,
            "需要 LLM 判断（当前为占位实现）",
            "LLM 判断能力尚未接入"
        );
    }

    /**
     * 判断是否可用规则检查。
     */
    private boolean canRuleCheck(String description) {
        return RULE_KEYWORDS.stream().anyMatch(description::contains);
    }

    /**
     * 规则检查（简化实现）。
     *
     * 实际生产中：
     - 对"必须"类标准：检查 agentOutput 是否包含关键信息
     * - 对数值类标准：从 agentOutput 中提取数值并比较
     */
    private CriteriaVerificationResult ruleCheck(
        VerifiedCriterion criterion,
        String agentOutput,
        String executionLog
    ) {
        // 简化规则：如果 agentOutput 包含标准中的关键维度词，认为通过
        String dimension = criterion.dimension();
        boolean satisfied = agentOutput != null
            && !agentOutput.isBlank()
            && containsRelevantContent(agentOutput, dimension);

        if (satisfied) {
            return new Satisfied(
                criterion,
                VerificationMethod.RULE_BASED,
                "输出中包含与「" + dimension + "」相关的分析内容"
            );
        } else {
            RemediationAction action = determineRemediation(criterion);
            return new NotSatisfied(
                criterion,
                VerificationMethod.RULE_BASED,
                "输出中未检测到与「" + dimension + "」相关的分析内容",
                action
            );
        }
    }

    /**
     * 简化的相关性检查。
     */
    private boolean containsRelevantContent(String output, String dimension) {
        // 生产实现：使用更精确的 NLP 匹配或 LLM 辅助
        return output.contains(dimension);
    }

    /**
     * 根据标准的来源和默认选中状态决定补救动作。
     */
    private RemediationAction determineRemediation(VerifiedCriterion criterion) {
        return switch (criterion.originalProposal()) {
            case CriteriaProposal.TemplateProposal tp when tp.defaultSelected()
                -> RemediationAction.REPLAN;
            case CriteriaProposal.TemplateProposal tp
                -> RemediationAction.WARN_CONTINUE;
            case CriteriaProposal.OntologyProposal op when op.historicalSuccessCount() >= 5
                -> RemediationAction.REPLAN;
            case CriteriaProposal.OntologyProposal op
                -> RemediationAction.ESCALATE_HUMAN;
            case CriteriaProposal.LlmInferredProposal lip when lip.confidence() >= 0.8f
                -> RemediationAction.ESCALATE_HUMAN;
            case CriteriaProposal.LlmInferredProposal lip
                -> RemediationAction.WARN_CONTINUE;
        };
    }
}
