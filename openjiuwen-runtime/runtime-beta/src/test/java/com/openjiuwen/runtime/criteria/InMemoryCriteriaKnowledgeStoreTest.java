package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeEntry;
import com.openjiuwen.runtime.criteria.knowledge.InMemoryCriteriaKnowledgeStore;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryCriteriaKnowledgeStore 测试——CRUD、key 碰撞、线程安全。
 */
class InMemoryCriteriaKnowledgeStoreTest {

    private InMemoryCriteriaKnowledgeStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCriteriaKnowledgeStore();
    }

    private CriteriaKnowledgeEntry makeEntry(String dimension, Industry industry) {
        return new CriteriaKnowledgeEntry(
            dimension, "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            industry, "ontology://test/" + dimension, 1, 1, 0,
            Instant.now(), Instant.now(), false);
    }

    @Test
    void save_andFindByDimensionAndIndustry() {
        store.save(makeEntry("合规性", Industry.FINANCE));
        CriteriaKnowledgeEntry found = store.findByDimensionAndIndustry("合规性", Industry.FINANCE);
        assertNotNull(found);
        assertEquals("合规性", found.dimension());
    }

    @Test
    void find_notFound_returnsNull() {
        CriteriaKnowledgeEntry found = store.findByDimensionAndIndustry("不存在", Industry.FINANCE);
        assertNull(found);
    }

    @Test
    void save_overwritesExisting() {
        store.save(makeEntry("合规性", Industry.FINANCE));
        // 更新：usage=10
        CriteriaKnowledgeEntry updated = new CriteriaKnowledgeEntry(
            "合规性", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "ontology://test/合规性", 10, 8, 2,
            Instant.now(), Instant.now(), false);
        store.save(updated);

        CriteriaKnowledgeEntry found = store.findByDimensionAndIndustry("合规性", Industry.FINANCE);
        assertEquals(10, found.totalUsage());
    }

    @Test
    void loadAll_returnsAllEntries() {
        store.save(makeEntry("A", Industry.FINANCE));
        store.save(makeEntry("B", Industry.POWER));
        store.save(makeEntry("C", Industry.MANUFACTURING));

        List<CriteriaKnowledgeEntry> all = store.loadAll();
        assertEquals(3, all.size());
    }

    @Test
    void clear_removesAll() {
        store.save(makeEntry("A", Industry.FINANCE));
        store.save(makeEntry("B", Industry.POWER));
        store.clear();
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void sameDimensionDifferentIndustry_separateEntries() {
        store.save(makeEntry("数据准确性", Industry.FINANCE));
        store.save(makeEntry("数据准确性", Industry.POWER));

        assertNotNull(store.findByDimensionAndIndustry("数据准确性", Industry.FINANCE));
        assertNotNull(store.findByDimensionAndIndustry("数据准确性", Industry.POWER));
        assertEquals(2, store.loadAll().size());
    }
}
