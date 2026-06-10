package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeEntry;
import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeStore;
import com.openjiuwen.runtime.criteria.knowledge.InMemoryCriteriaKnowledgeStore;
import com.openjiuwen.runtime.criteria.model.*;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultKnowledgeAccumulator 测试——沉淀、合并、淘汰、容量控制。
 */
class DefaultKnowledgeAccumulatorTest {

    private InMemoryCriteriaKnowledgeStore store;
    private DefaultKnowledgeAccumulator accumulator;

    @BeforeEach
    void setUp() {
        store = new InMemoryCriteriaKnowledgeStore();
        accumulator = new DefaultKnowledgeAccumulator(store);
    }

    private VerifiedCriterion makeCriterion(String dimension) {
        return VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal(dimension, dimension + "描述", true), null);
    }

    private CriteriaVerificationResult satisfied() {
        return new CriteriaVerificationResult.Satisfied(
            makeCriterion("test"), CriteriaVerificationResult.VerificationMethod.RULE_BASED, "pass");
    }

    private CriteriaVerificationResult notSatisfied() {
        return new CriteriaVerificationResult.NotSatisfied(
            makeCriterion("test"), CriteriaVerificationResult.VerificationMethod.RULE_BASED, "fail",
            CriteriaVerificationResult.RemediationAction.WARN_CONTINUE);
    }

    // ==================== 沉淀 ====================

    @Test
    void accumulate_newEntry_createsInStore() {
        VerifiedCriterion criterion = makeCriterion("数据准确性");
        accumulator.accumulate(criterion, satisfied(), Industry.FINANCE);

        CriteriaKnowledgeEntry entry = store.findByDimensionAndIndustry("数据准确性", Industry.FINANCE);
        assertNotNull(entry);
        assertEquals(1, entry.totalUsage());
        assertEquals(1, entry.successCount());
        assertEquals(0, entry.failureCount());
    }

    @Test
    void accumulate_failure_incrementsFailureCount() {
        VerifiedCriterion criterion = makeCriterion("数据准确性");
        accumulator.accumulate(criterion, notSatisfied(), Industry.FINANCE);

        CriteriaKnowledgeEntry entry = store.findByDimensionAndIndustry("数据准确性", Industry.FINANCE);
        assertNotNull(entry);
        assertEquals(0, entry.successCount());
        assertEquals(1, entry.failureCount());
    }

    @Test
    void accumulate_sameDimension_mergesStats() {
        VerifiedCriterion criterion = makeCriterion("合规性");
        accumulator.accumulate(criterion, satisfied(), Industry.FINANCE);
        accumulator.accumulate(criterion, satisfied(), Industry.FINANCE);
        accumulator.accumulate(criterion, notSatisfied(), Industry.FINANCE);

        CriteriaKnowledgeEntry entry = store.findByDimensionAndIndustry("合规性", Industry.FINANCE);
        assertEquals(3, entry.totalUsage());
        assertEquals(2, entry.successCount());
        assertEquals(1, entry.failureCount());
    }

    @Test
    void accumulate_sameDimensionDifferentIndustry_separateEntries() {
        VerifiedCriterion c = makeCriterion("数据准确性");
        accumulator.accumulate(c, satisfied(), Industry.FINANCE);
        accumulator.accumulate(c, satisfied(), Industry.POWER);

        assertNotNull(store.findByDimensionAndIndustry("数据准确性", Industry.FINANCE));
        assertNotNull(store.findByDimensionAndIndustry("数据准确性", Industry.POWER));
    }

    // ==================== 批量沉淀 ====================

    @Test
    void accumulateAll_mismatchedSize_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            accumulator.accumulateAll(
                List.of(makeCriterion("a"), makeCriterion("b")),
                List.of(satisfied()),  // 大小不匹配
                Industry.FINANCE
            ));
    }

    @Test
    void accumulateAll_matchedSize_ok() {
        accumulator.accumulateAll(
            List.of(makeCriterion("a"), makeCriterion("b")),
            List.of(satisfied(), notSatisfied()),
            Industry.FINANCE
        );

        assertNotNull(store.findByDimensionAndIndustry("a", Industry.FINANCE));
        assertNotNull(store.findByDimensionAndIndustry("b", Industry.FINANCE));
    }

    // ==================== 维护：低质淘汰 ====================

    @Test
    void maintain_lowSuccessRate_deprecated() {
        // 模拟 6 次使用，1 次成功 → 成功率 16.7% < 30%
        CriteriaKnowledgeEntry lowQuality = new CriteriaKnowledgeEntry(
            "低质标准", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "ontology://test", 6, 1, 5,
            Instant.now().minusSeconds(100), Instant.now(), false);
        store.save(lowQuality);

        accumulator.maintain();

        CriteriaKnowledgeEntry after = store.findByDimensionAndIndustry("低质标准", Industry.FINANCE);
        assertTrue(after.deprecated(), "成功率 < 30% 且使用 >= 5 次应被标记 deprecated");
    }

    @Test
    void maintain_highSuccessRate_notDeprecated() {
        CriteriaKnowledgeEntry highQuality = new CriteriaKnowledgeEntry(
            "高质标准", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "ontology://test", 10, 9, 1,
            Instant.now().minusSeconds(100), Instant.now(), false);
        store.save(highQuality);

        accumulator.maintain();

        CriteriaKnowledgeEntry after = store.findByDimensionAndIndustry("高质标准", Industry.FINANCE);
        assertFalse(after.deprecated(), "成功率 >= 30% 不应被淘汰");
    }

    @Test
    void maintain_belowMinUsage_notDeprecatedEvenIfLowRate() {
        // 使用次数 < 5，即使成功率为 0 也不淘汰
        CriteriaKnowledgeEntry lowUsage = new CriteriaKnowledgeEntry(
            "低使用", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "ontology://test", 3, 0, 3,
            Instant.now().minusSeconds(100), Instant.now(), false);
        store.save(lowUsage);

        accumulator.maintain();

        CriteriaKnowledgeEntry after = store.findByDimensionAndIndustry("低使用", Industry.FINANCE);
        assertFalse(after.deprecated(), "使用次数 < 5 不应被淘汰");
    }

    // ==================== 维护：容量控制 ====================

    @Test
    void maintain_exceedsCapacity_evictsLowestScore() {
        // 填充 501 条（超过 MAX_ENTRIES=500）
        for (int i = 0; i <= 500; i++) {
            CriteriaKnowledgeEntry entry = new CriteriaKnowledgeEntry(
                "标准" + i, "描述" + i, CriteriaKnowledgeEntry.SourceType.TEMPLATE,
                Industry.FINANCE, "ontology://test/" + i, 1, 0, 1,
                Instant.now(), Instant.now(), false);
            store.save(entry);
        }

        accumulator.maintain();

        List<CriteriaKnowledgeEntry> all = store.loadAll();
        long activeCount = all.stream().filter(e -> !e.deprecated()).count();
        assertTrue(activeCount <= 500, "活跃条目不应超过 500");
    }
}
