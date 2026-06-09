package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeEntry;

import java.util.List;

/**
 * 知识沉淀引擎——验证过的 successCriteria 沉淀为延续性知识。
 *
 * 核心原则：验证过的输入 = 延续性知识。
 *
 * 沉淀内容：
 * 1. 原始 criteria（维度 + 描述）
 * 2. 统计数据（使用次数、成功次数、成功率）
 * 3. 关联关系（标准 ↔ 行业 ↔ 本体实体）
 *
 * 沉淀位置：
 * - 主体：独立知识库（CriteriaKnowledgeStore），支持高效查询
 * - 联动：更新领域本体的关联实体（ontologyUri）
 * - 联动：更新元Agent的默认 successCriteria（高频成功标准自动提升）
 *
 * 知识膨胀控制：
 * 1. 同维度合并：不无限新增条目，而是累加统计
 * 2. 时效衰减：超过 365 天未使用的条目自动降权
 * 3. 低质淘汰：成功率低于 30% 且使用超过 5 次的条目标记 deprecated
 * 4. 容量上限：默认 500 条，超出时按 compositeScore 淘汰末位
 *
 * 闭环回路：
 *   本体 → CriteriaProposer 提案 → 用户确认 → Agent执行 →
 *   CriteriaVerifier 验证 → KnowledgeAccumulator 沉淀 → 更新本体
 */
public interface KnowledgeAccumulator {

    /**
     * 沉淀一次验证结果。
     *
     * @param criterion 被验证的标准
     * @param result    验证结果
     * @param industry  行业
     */
    void accumulate(VerifiedCriterion criterion, CriteriaVerificationResult result, Industry industry);

    /**
     * 批量沉淀一次任务的所有验证结果。
     *
     * @param criteria 验证过的标准列表
     * @param results  对应的验证结果列表
     * @param industry 行业
     */
    default void accumulateAll(
        List<VerifiedCriterion> criteria,
        List<CriteriaVerificationResult> results,
        Industry industry
    ) {
        for (int i = 0; i < criteria.size() && i < results.size(); i++) {
            accumulate(criteria.get(i), results.get(i), industry);
        }
    }

    /**
     * 执行知识维护（淘汰过时知识、合并重复条目）。
     */
    void maintain();

    /**
     * 查询指定行业的知识条目（用于 CriteriaProposer 的本体来源）。
     */
    List<CriteriaKnowledgeEntry> queryByIndustry(Industry industry);

    /**
     * 查询高频成功的标准（用于元Agent默认标准推荐）。
     */
    List<CriteriaKnowledgeEntry> queryHighSuccess(Industry industry, int topN);
}
