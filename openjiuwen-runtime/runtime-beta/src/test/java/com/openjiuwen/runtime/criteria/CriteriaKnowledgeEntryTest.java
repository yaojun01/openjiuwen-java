package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeEntry;
import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeStore;
import com.openjiuwen.runtime.criteria.knowledge.InMemoryCriteriaKnowledgeStore;
import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CriteriaKnowledgeEntry 测试——合并、评分、创建。
 */
class CriteriaKnowledgeEntryTest {

    // ==================== fromVerification ====================

    @Test
    void fromVerification_satisfied_createsEntryWithSuccess() {
        VerifiedCriterion criterion = VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal("数据准确性", "准确", true), null);
        CriteriaVerificationResult result = new CriteriaVerificationResult.Satisfied(
            criterion, CriteriaVerificationResult.VerificationMethod.RULE_BASED, "pass");

        CriteriaKnowledgeEntry entry = CriteriaKnowledgeEntry.fromVerification(criterion, result, Industry.FINANCE);

        assertEquals("数据准确性", entry.dimension());
        assertEquals(1, entry.totalUsage());
        assertEquals(1, entry.successCount());
        assertEquals(0, entry.failureCount());
        assertFalse(entry.deprecated());
        assertNotNull(entry.firstUsedAt());
        assertNotNull(entry.lastUsedAt());
    }

    @Test
    void fromVerification_notSatisfied_incrementsFailure() {
        VerifiedCriterion criterion = VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal("合规性", "合规", true), null);
        CriteriaVerificationResult result = new CriteriaVerificationResult.NotSatisfied(
            criterion, CriteriaVerificationResult.VerificationMethod.RULE_BASED, "fail",
            CriteriaVerificationResult.RemediationAction.REPLAN);

        CriteriaKnowledgeEntry entry = CriteriaKnowledgeEntry.fromVerification(criterion, result, Industry.FINANCE);
        assertEquals(0, entry.successCount());
        assertEquals(1, entry.failureCount());
    }

    @Test
    void fromVerification_sourceType_mapping() {
        // TemplateProposal → TEMPLATE
        VerifiedCriterion templateCriterion = VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal("维度", "描述", true), null);
        CriteriaKnowledgeEntry templateEntry = CriteriaKnowledgeEntry.fromVerification(
            templateCriterion,
            new CriteriaVerificationResult.Satisfied(templateCriterion,
                CriteriaVerificationResult.VerificationMethod.RULE_BASED, "ok"),
            Industry.FINANCE);
        assertEquals(CriteriaKnowledgeEntry.SourceType.TEMPLATE, templateEntry.sourceType());

        // OntologyProposal → ONTOLOGY
        VerifiedCriterion ontologyCriterion = VerifiedCriterion.from(
            new CriteriaProposal.OntologyProposal("维度", "描述", "ontology://x", 5), null);
        CriteriaKnowledgeEntry ontologyEntry = CriteriaKnowledgeEntry.fromVerification(
            ontologyCriterion,
            new CriteriaVerificationResult.Satisfied(ontologyCriterion,
                CriteriaVerificationResult.VerificationMethod.RULE_BASED, "ok"),
            Industry.FINANCE);
        assertEquals(CriteriaKnowledgeEntry.SourceType.ONTOLOGY, ontologyEntry.sourceType());
    }

    // ==================== successRate ====================

    @Test
    void successRate_zeroUsage_returnsZero() {
        CriteriaKnowledgeEntry entry = new CriteriaKnowledgeEntry(
            "维度", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "uri", 0, 0, 0,
            Instant.now(), Instant.now(), false);
        assertEquals(0.0, entry.successRate());
    }

    @Test
    void successRate_halfSuccess_returnsPointFive() {
        CriteriaKnowledgeEntry entry = new CriteriaKnowledgeEntry(
            "维度", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "uri", 10, 5, 5,
            Instant.now(), Instant.now(), false);
        assertEquals(0.5, entry.successRate(), 0.001);
    }

    // ==================== merge ====================

    @Test
    void merge_accumulatesStats() {
        CriteriaKnowledgeEntry e1 = new CriteriaKnowledgeEntry(
            "维度", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "uri", 5, 4, 1,
            Instant.now().minusSeconds(1000), Instant.now(), false);
        CriteriaKnowledgeEntry e2 = new CriteriaKnowledgeEntry(
            "维度", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "uri", 3, 2, 1,
            Instant.now().minusSeconds(500), Instant.now(), false);

        CriteriaKnowledgeEntry merged = e1.merge(e2);
        assertEquals(8, merged.totalUsage());
        assertEquals(6, merged.successCount());
        assertEquals(2, merged.failureCount());
    }

    @Test
    void merge_preservesDeprecatedFromExisting() {
        CriteriaKnowledgeEntry existing = new CriteriaKnowledgeEntry(
            "维度", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "uri", 5, 1, 4,
            Instant.now(), Instant.now(), true);  // deprecated
        CriteriaKnowledgeEntry incoming = new CriteriaKnowledgeEntry(
            "维度", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "uri", 1, 1, 0,
            Instant.now(), Instant.now(), false);  // not deprecated

        CriteriaKnowledgeEntry merged = existing.merge(incoming);
        assertTrue(merged.deprecated(), "merge 应保留 existing 的 deprecated 状态");
    }

    // ==================== compositeScore ====================

    @Test
    void compositeScore_highSuccessMoreRecent_scoresHigher() {
        CriteriaKnowledgeEntry good = new CriteriaKnowledgeEntry(
            "好", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "uri", 20, 19, 1,
            Instant.now(), Instant.now(), false);
        CriteriaKnowledgeEntry bad = new CriteriaKnowledgeEntry(
            "差", "描述", CriteriaKnowledgeEntry.SourceType.TEMPLATE,
            Industry.FINANCE, "uri", 20, 5, 15,
            Instant.now().minusSeconds(60L * 60 * 24 * 200), // 200 天前
            Instant.now().minusSeconds(60L * 60 * 24 * 200), false);

        assertTrue(good.compositeScore() > bad.compositeScore(),
            "高成功率+最近使用 应比 低成功率+久远使用 评分更高");
    }
}
