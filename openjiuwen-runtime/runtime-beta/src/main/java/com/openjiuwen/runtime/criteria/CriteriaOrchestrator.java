package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import java.util.List;
import java.util.Objects;

/**
 * 成功标准编排器——完整闭环的入口。
 *
 * 闭环流程：
 *   1. propose()        — 三层提案引擎生成候选
 *   2. confirm()        — 用户确认选择（选择题交互）
 *   3. buildGoalSpec()  — 构建带成功标准的目标规格
 *   4. verify()         — Agent 执行后逐条验证
 *   5. accumulate()     — 验证结果沉淀为知识
 *   6. maintain()       — 知识维护（淘汰/合并）
 *
 * 使用示例：
 * <pre>
 * CriteriaOrchestrator orchestrator = new CriteriaOrchestrator();
 *
 * // 1. 提案
 * List<CriteriaProposal> proposals = orchestrator.propose(
 *     "分析客户退款原因，给出改进建议", Industry.FINANCE);
 *
 * // 2. 用户选择（前端展示为多选题）
 * List<CriteriaProposal> selected = proposals.stream()
 *     .filter(p -> p instanceof CriteriaProposal.TemplateProposal tp && tp.defaultSelected())
 *     .toList();
 *
 * // 3. 确认
 * List<VerifiedCriterion> verified = orchestrator.confirm(selected);
 *
 * // 4. 构建目标
 * GoalSpec goal = orchestrator.buildGoalSpec("分析客户退款原因", verified);
 *
 * // ... Agent 执行 ...
 *
 * // 5. 验证
 * List<CriteriaVerificationResult> results = orchestrator.verify(
 *     verified, agentOutput, executionLog);
 *
 * // 6. 沉淀
 * orchestrator.accumulate(verified, results, Industry.FINANCE);
 * </pre>
 */
public class CriteriaOrchestrator {

    private final CriteriaProposer proposer;
    private final CriteriaCheckEngine verifier;
    private final KnowledgeAccumulator accumulator;

    public CriteriaOrchestrator(CriteriaProposer proposer,
                                CriteriaCheckEngine verifier,
                                KnowledgeAccumulator accumulator) {
        this.proposer = proposer;
        this.verifier = verifier;
        this.accumulator = accumulator;
    }

    /**
     * 使用默认组件构建。
     */
    public CriteriaOrchestrator() {
        this.proposer = new DefaultCriteriaProposer();
        this.verifier = new DefaultCriteriaVerifier();
        this.accumulator = new DefaultKnowledgeAccumulator();
    }

    /**
     * 步骤 1：生成提案。
     */
    public List<CriteriaProposal> propose(String taskDescription, Industry industry) {
        return proposer.propose(taskDescription, industry);
    }

    /**
     * 步骤 2：用户确认——将选中的提案转为已验证标准。
     *
     * @param selectedProposals 用户选择的提案列表
     * @return 已验证的标准列表
     */
    public List<VerifiedCriterion> confirm(List<CriteriaProposal> selectedProposals) {
        Objects.requireNonNull(selectedProposals, "selectedProposals must not be null");
        return selectedProposals.stream()
            .map(proposal -> VerifiedCriterion.from(proposal, null))
            .toList();
    }

    /**
     * 步骤 2（增强版）：用户确认 + 自定义修改。
     *
     * @param selections 用户选择结果（提案 → 可选的自定义描述）
     * @return 已验证的标准列表
     */
    public List<VerifiedCriterion> confirmWithOverrides(
        List<java.util.Map.Entry<CriteriaProposal, String>> selections
    ) {
        Objects.requireNonNull(selections, "selections must not be null");
        return selections.stream()
            .map(entry -> VerifiedCriterion.from(entry.getKey(), entry.getValue()))
            .toList();
    }

    /**
     * 步骤 3：构建 GoalSpec。
     */
    public GoalSpec buildGoalSpec(String goal, List<VerifiedCriterion> criteria) {
        List<String> criteriaStrings = criteria.stream()
            .map(VerifiedCriterion::toCriteriaString)
            .toList();
        return GoalSpec.of(goal, criteriaStrings);
    }

    /**
     * 步骤 4：验证 Agent 执行结果。
     */
    public List<CriteriaVerificationResult> verify(
        List<VerifiedCriterion> criteria,
        String agentOutput,
        String executionLog
    ) {
        return verifier.verify(criteria, agentOutput, executionLog);
    }

    /**
     * 步骤 5：沉淀验证结果。
     */
    public void accumulate(
        List<VerifiedCriterion> criteria,
        List<CriteriaVerificationResult> results,
        Industry industry
    ) {
        accumulator.accumulateAll(criteria, results, industry);
    }

    /**
     * 步骤 6：知识维护。
     */
    public void maintain() {
        accumulator.maintain();
    }

    /**
     * 判断验证结果是否全部通过。
     */
    public boolean isAllSatisfied(List<CriteriaVerificationResult> results) {
        return verifier.allSatisfied(results);
    }

    /**
     * 判断是否需要 replan。
     */
    public boolean requiresReplan(List<CriteriaVerificationResult> results) {
        return verifier.requiresReplan(results);
    }

    // ==================== 便捷方法 ====================

    /**
     * 获取底层提案引擎（用于注入到 OntologyCriteriaSource）。
     */
    public CriteriaProposer proposer() { return proposer; }

    /**
     * 获取底层知识引擎（用于查询历史知识）。
     */
    public KnowledgeAccumulator accumulator() { return accumulator; }
}
