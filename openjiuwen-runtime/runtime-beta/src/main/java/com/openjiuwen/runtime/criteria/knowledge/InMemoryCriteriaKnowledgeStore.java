package com.openjiuwen.runtime.criteria.knowledge;

import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存知识存储——开发/测试用实现。
 *
 * 线程安全：ConcurrentHashMap 保证并发写入安全。
 * 生产环境应替换为持久化实现。
 */
public final class InMemoryCriteriaKnowledgeStore implements CriteriaKnowledgeStore {

    /** key = dimension + "|" + industry.name() */
    private final ConcurrentHashMap<String, CriteriaKnowledgeEntry> store = new ConcurrentHashMap<>();

    private static String key(String dimension, Industry industry) {
        return dimension + "|" + industry.name();
    }

    @Override
    public void save(CriteriaKnowledgeEntry entry) {
        String key = key(entry.dimension(), entry.industry());
        store.put(key, entry);
    }

    @Override
    public CriteriaKnowledgeEntry findByDimensionAndIndustry(String dimension, Industry industry) {
        return store.get(key(dimension, industry));
    }

    @Override
    public List<CriteriaKnowledgeEntry> loadAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void clear() {
        store.clear();
    }
}
