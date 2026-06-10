package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeEntry;
import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeStore;
import com.openjiuwen.runtime.criteria.knowledge.InMemoryCriteriaKnowledgeStore;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import java.util.Comparator;
import java.util.List;

/**
 * 默认知识沉淀引擎——基于内存存储的实现。
 *
 * 生产环境可替换为：
 * - SQLite / PostgreSQL 持久化
 * - 向量数据库（语义检索）
 * - 外部本体服务（OWL/RDF）
 */
public class DefaultKnowledgeAccumulator implements KnowledgeAccumulator {

    /** 成功率低于此阈值的条目标记 deprecated */
    private static final double DEPRECATION_SUCCESS_RATE_THRESHOLD = 0.3;

    /** 使用次数超过此阈值才考虑淘汰 */
    private static final int DEPRECATION_MIN_USAGE = 5;

    /** 知识库容量上限 */
    private static final int MAX_ENTRIES = 500;

    /** 高频成功标准的成功率阈值 */
    private static final double HIGH_SUCCESS_THRESHOLD = 0.8;

    private final CriteriaKnowledgeStore store;

    public DefaultKnowledgeAccumulator(CriteriaKnowledgeStore store) {
        this.store = store;
    }

    public DefaultKnowledgeAccumulator() {
        this.store = new InMemoryCriteriaKnowledgeStore();
    }

    @Override
    public void accumulate(VerifiedCriterion criterion, CriteriaVerificationResult result, Industry industry) {
        CriteriaKnowledgeEntry newEntry = CriteriaKnowledgeEntry.fromVerification(criterion, result, industry);

        // 尝试合并已有条目（同维度 + 同行业）
        CriteriaKnowledgeEntry existing = store.findByDimensionAndIndustry(
            criterion.dimension(), industry);

        if (existing != null) {
            CriteriaKnowledgeEntry merged = existing.merge(newEntry);
            store.save(merged);
        } else {
            store.save(newEntry);
        }
    }

    @Override
    public void maintain() {
        List<CriteriaKnowledgeEntry> all = store.loadAll();

        // 1. 标记低质条目为 deprecated
        for (CriteriaKnowledgeEntry entry : all) {
            if (!entry.deprecated()
                && entry.totalUsage() >= DEPRECATION_MIN_USAGE
                && entry.successRate() < DEPRECATION_SUCCESS_RATE_THRESHOLD) {
                CriteriaKnowledgeEntry deprecated = new CriteriaKnowledgeEntry(
                    entry.dimension(), entry.description(), entry.sourceType(),
                    entry.industry(), entry.ontologyUri(),
                    entry.totalUsage(), entry.successCount(), entry.failureCount(),
                    entry.firstUsedAt(), entry.lastUsedAt(),
                    true  // 标记 deprecated
                );
                store.save(deprecated);
            }
        }

        // 2. 容量控制：超出上限时淘汰末位
        List<CriteriaKnowledgeEntry> active = store.loadAll().stream()
            .filter(e -> !e.deprecated())
            .sorted(Comparator.comparingDouble(CriteriaKnowledgeEntry::compositeScore).reversed())
            .toList();

        if (active.size() > MAX_ENTRIES) {
            // 淘汰末位条目
            active.subList(MAX_ENTRIES, active.size())
                .forEach(e -> {
                    CriteriaKnowledgeEntry deprecated = new CriteriaKnowledgeEntry(
                        e.dimension(), e.description(), e.sourceType(),
                        e.industry(), e.ontologyUri(),
                        e.totalUsage(), e.successCount(), e.failureCount(),
                        e.firstUsedAt(), e.lastUsedAt(),
                        true
                    );
                    store.save(deprecated);
                });
        }
    }

    @Override
    public List<CriteriaKnowledgeEntry> queryByIndustry(Industry industry) {
        return store.loadAll().stream()
            .filter(e -> e.industry() == industry)
            .filter(e -> !e.deprecated())
            .sorted(Comparator.comparingDouble(CriteriaKnowledgeEntry::compositeScore).reversed())
            .toList();
    }

    @Override
    public List<CriteriaKnowledgeEntry> queryHighSuccess(Industry industry, int topN) {
        return queryByIndustry(industry).stream()
            .filter(e -> e.totalUsage() >= 3)
            .filter(e -> e.successRate() >= HIGH_SUCCESS_THRESHOLD)
            .limit(topN)
            .toList();
    }
}
