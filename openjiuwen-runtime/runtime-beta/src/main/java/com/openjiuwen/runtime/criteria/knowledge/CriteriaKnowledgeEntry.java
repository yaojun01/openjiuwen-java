package com.openjiuwen.runtime.criteria.knowledge;

import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import java.time.Instant;

/**
 * 知识条目——单条成功标准的沉淀记录。
 *
 * 记录内容：
 * - 标准 itself（维度 + 描述）
 * - 来源追溯（原始提案类型）
 * - 使用统计（总使用次数、成功次数、失败次数）
 * - 关联的行业和本体实体
 * - 时间信息（首次使用、最后使用）
 *
 * 知识淘汰：
 * - 最后使用时间超过 TTL 的条目降权
 * - 成功率低于阈值的条目标记为 deprecated
 * - 总条数超过上限时，按综合评分淘汰
 */
public record CriteriaKnowledgeEntry(
    String dimension,
    String description,
    SourceType sourceType,
    Industry industry,
    String ontologyUri,
    int totalUsage,
    int successCount,
    int failureCount,
    Instant firstUsedAt,
    Instant lastUsedAt,
    boolean deprecated
) {

    /** 来源类型 */
    public enum SourceType {
        TEMPLATE,       // 从模板沉淀
        LLM_INFERRED,   // 从 LLM 推理沉淀
        ONTOLOGY,       // 从本体继承
        USER_CUSTOM     // 用户自定义
    }

    /**
     * 成功率。
     */
    public double successRate() {
        return totalUsage == 0 ? 0.0 : (double) successCount / totalUsage;
    }

    /**
     * 综合评分（用于排序和淘汰）。
     * 考虑：成功率、使用频次、时效性。
     */
    public double compositeScore() {
        double rateScore = successRate();
        double frequencyScore = Math.min(1.0, totalUsage / 20.0);
        double recencyScore = calculateRecencyScore();
        return 0.5 * rateScore + 0.3 * frequencyScore + 0.2 * recencyScore;
    }

    private double calculateRecencyScore() {
        if (lastUsedAt == null) return 0.0;
        long daysSinceLastUse = java.time.Duration.between(lastUsedAt, Instant.now()).toDays();
        // 30天内满分，之后线性衰减，365天后为0
        if (daysSinceLastUse <= 30) return 1.0;
        if (daysSinceLastUse >= 365) return 0.0;
        return 1.0 - (daysSinceLastUse - 30) / 335.0;
    }

    /**
     * 从验证结果创建或更新知识条目。
     */
    public static CriteriaKnowledgeEntry fromVerification(
        VerifiedCriterion criterion,
        CriteriaVerificationResult result,
        Industry industry
    ) {
        SourceType sourceType = switch (criterion.originalProposal()) {
            case com.openjiuwen.runtime.criteria.model.CriteriaProposal.TemplateProposal tp
                -> SourceType.TEMPLATE;
            case com.openjiuwen.runtime.criteria.model.CriteriaProposal.LlmInferredProposal lip
                -> SourceType.LLM_INFERRED;
            case com.openjiuwen.runtime.criteria.model.CriteriaProposal.OntologyProposal op
                -> SourceType.ONTOLOGY;
        };

        boolean success = result.isSatisfied();
        Instant now = Instant.now();

        return new CriteriaKnowledgeEntry(
            criterion.dimension(),
            criterion.finalDescription(),
            sourceType,
            industry,
            criterion.ontologyEntity(),
            1,
            success ? 1 : 0,
            success ? 0 : 1,
            now,
            now,
            false
        );
    }

    /**
     * 合并使用统计（同维度条目累加）。
     */
    public CriteriaKnowledgeEntry merge(CriteriaKnowledgeEntry other) {
        return new CriteriaKnowledgeEntry(
            dimension,
            description,
            sourceType,
            industry,
            ontologyUri,
            totalUsage + other.totalUsage,
            successCount + other.successCount,
            failureCount + other.failureCount,
            minInstant(firstUsedAt, other.firstUsedAt),
            maxInstant(lastUsedAt, other.lastUsedAt),
            deprecated
        );
    }

    private static Instant minInstant(Instant a, Instant b) {
        return a == null ? b : (b == null ? a : a.isBefore(b) ? a : b);
    }

    private static Instant maxInstant(Instant a, Instant b) {
        return a == null ? b : (b == null ? a : a.isAfter(b) ? a : b);
    }
}
