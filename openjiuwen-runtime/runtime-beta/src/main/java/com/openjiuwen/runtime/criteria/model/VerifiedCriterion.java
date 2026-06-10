package com.openjiuwen.runtime.criteria.model;
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

/**
 * 已验证的成功标准——用户确认后的成果。
 *
 * 一条 CriteriaProposal 经过用户确认后，变为 VerifiedCriterion。
 * 这是"验证过的输入 = 延续性知识"的核心数据结构。
 *
 * 包含：
 * - 原始提案（保留来源追溯）
 * - 用户的最终决定（可能修改了描述）
 * - 关联的本体实体（用于知识沉淀）
 */
public record VerifiedCriterion(
    String dimension,
    String finalDescription,
    CriteriaProposal originalProposal,
    String ontologyEntity,
    boolean userModified
) {

    public VerifiedCriterion {
        if (dimension == null || dimension.isBlank())
            throw new IllegalArgumentException("dimension 不能为空");
        if (finalDescription == null || finalDescription.isBlank())
            throw new IllegalArgumentException("finalDescription 不能为空");
    }

    /**
     * 从用户确认的提案创建 VerifiedCriterion。
     *
     * @param proposal     用户确认的原始提案
     * @param userOverride 用户修改后的描述（null 表示未修改）
     * @return VerifiedCriterion
     */
    public static VerifiedCriterion from(CriteriaProposal proposal, String userOverride) {
        String finalDesc = (userOverride != null && !userOverride.isBlank())
            ? userOverride
            : proposal.description();

        String entity = switch (proposal) {
            case CriteriaProposal.OntologyProposal op -> op.ontologyUri();
            case CriteriaProposal.TemplateProposal tp -> "ontology://criteria/verified/" + tp.dimension();
            case CriteriaProposal.LlmInferredProposal lip -> "ontology://criteria/inferred/" + lip.dimension();
        };

        boolean modified = (userOverride != null && !userOverride.isBlank()
            && !userOverride.equals(proposal.description()));

        return new VerifiedCriterion(proposal.dimension(), finalDesc, proposal, entity, modified);
    }

    /** 转换为 GoalSpec 可用的 String 格式 */
    public String toCriteriaString() {
        return "[" + dimension + "] " + finalDescription;
    }
}
