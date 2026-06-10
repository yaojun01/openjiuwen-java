package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VerifiedCriterion 测试——创建、校验、from 工厂方法。
 */
class VerifiedCriterionTest {

    @Test
    void from_templateProposal_noOverride() {
        CriteriaProposal.TemplateProposal proposal =
            new CriteriaProposal.TemplateProposal("数据准确性", "数值准确", true);

        VerifiedCriterion vc = VerifiedCriterion.from(proposal, null);

        assertEquals("数据准确性", vc.dimension());
        assertEquals("数值准确", vc.finalDescription());
        assertFalse(vc.userModified());
        assertEquals("ontology://criteria/verified/数据准确性", vc.ontologyEntity());
    }

    @Test
    void from_templateProposal_withOverride() {
        CriteriaProposal.TemplateProposal proposal =
            new CriteriaProposal.TemplateProposal("合规性", "符合监管", true);

        VerifiedCriterion vc = VerifiedCriterion.from(proposal, "自定义的合规描述");

        assertEquals("自定义的合规描述", vc.finalDescription());
        assertTrue(vc.userModified());
    }

    @Test
    void from_ontologyProposal_usesOntologyUri() {
        CriteriaProposal.OntologyProposal proposal =
            new CriteriaProposal.OntologyProposal("维度", "描述", "ontology://custom/uri", 5);

        VerifiedCriterion vc = VerifiedCriterion.from(proposal, null);
        assertEquals("ontology://custom/uri", vc.ontologyEntity());
    }

    @Test
    void from_llmProposal_usesInferredUri() {
        CriteriaProposal.LlmInferredProposal proposal =
            new CriteriaProposal.LlmInferredProposal("自定义维度", "推理描述", "推理过程", 0.8f);

        VerifiedCriterion vc = VerifiedCriterion.from(proposal, null);
        assertEquals("ontology://criteria/inferred/自定义维度", vc.ontologyEntity());
    }

    @Test
    void constructor_nullDimension_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new VerifiedCriterion(null, "描述",
                new CriteriaProposal.TemplateProposal("dim", "desc", true), "uri", false));
    }

    @Test
    void constructor_blankDescription_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new VerifiedCriterion("维度", "   ",
                new CriteriaProposal.TemplateProposal("dim", "desc", true), "uri", false));
    }

    @Test
    void toCriteriaString_format() {
        CriteriaProposal.TemplateProposal proposal =
            new CriteriaProposal.TemplateProposal("数据准确性", "数值必须准确", true);
        VerifiedCriterion vc = VerifiedCriterion.from(proposal, null);

        assertEquals("[数据准确性] 数值必须准确", vc.toCriteriaString());
    }
}
