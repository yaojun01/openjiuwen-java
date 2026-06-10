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
 * 职责：只负责"写入+维护"（accumulate + maintain）。
 * 查询由 CriteriaKnowledgeStore 直接提供。
 *
 * 沉淀内容：
 * 1. 原始 criteria（维度 + 描述）
 * 2. 统计数据（使用次数、成功次数、成功率）
 * 3. 关联关系（标准 ↔ 行业 ↔ 本体实体）
 *
 * 知识膨胀控制：
 * 1. 同维度合并：不无限新增条目，而是累加统计
 * 2. 低质淘汰：成功率低于 30% 且使用超过 5 次的条目标记 deprecated
 * 3. 容量上限：默认 500 条，超出时按 compositeScore（含时效权重）淘汰末位
 * 4. 时效评分：compositeScore 内含 recency 因子（30 天内满分，365 天后归零），低分条目被容量控制淘汰
 *
 * 闭环回路：
 *   本体 → CriteriaProposer 提案 → 用户确认 → Agent执行 →
 *   CriteriaCheckEngine 验证 → KnowledgeAccumulator 沉淀 → 更新本体
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
        if (criteria.size() != results.size()) {
            throw new IllegalArgumentException(
                "criteria 和 results 大小不匹配: " + criteria.size() + " vs " + results.size());
        }
        for (int i = 0; i < criteria.size(); i++) {
            accumulate(criteria.get(i), results.get(i), industry);
        }
    }

    /**
     * 执行知识维护（淘汰过时知识、合并重复条目）。
     */
    void maintain();
}
