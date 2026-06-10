package com.openjiuwen.runtime.criteria.knowledge;

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
