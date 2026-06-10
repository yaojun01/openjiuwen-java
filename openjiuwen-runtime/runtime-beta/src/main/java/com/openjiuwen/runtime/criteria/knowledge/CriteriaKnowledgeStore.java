package com.openjiuwen.runtime.criteria.knowledge;
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

import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;

import java.util.List;

/**
 * 知识存储接口——CriteriaKnowledgeEntry 的持久化抽象。
 *
 * 实现可替换：
 * - InMemoryCriteriaKnowledgeStore: 开发/测试
 * - SqliteCriteriaKnowledgeStore: 单机部署
 * - PostgresCriteriaKnowledgeStore: 分布式部署
 * - VectorDbCriteriaKnowledgeStore: 语义检索
 */
public interface CriteriaKnowledgeStore {

    /**
     * 保存/更新知识条目（按 dimension + industry 作为唯一键）。
     */
    void save(CriteriaKnowledgeEntry entry);

    /**
     * 按维度和行业查询。
     */
    CriteriaKnowledgeEntry findByDimensionAndIndustry(String dimension, Industry industry);

    /**
     * 加载所有条目。
     */
    List<CriteriaKnowledgeEntry> loadAll();

    /**
     * 清除所有条目（测试用）。
     */
    void clear();
}
