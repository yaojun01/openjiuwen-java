package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.StructuredCriteria;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import com.openjiuwen.runtime.criteria.template.CriteriaTemplateRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CriteriaTemplateRegistry 测试——4 行业模板覆盖、defaultSelected 统计。
 */
class CriteriaTemplateRegistryTest {

    @Test
    void getByIndustry_finance_returns10Templates() {
        List<StructuredCriteria> templates = CriteriaTemplateRegistry.getByIndustry(Industry.FINANCE);
        assertEquals(10, templates.size(), "金融模板应有 10 条");
    }

    @Test
    void getByIndustry_power_returns8Templates() {
        List<StructuredCriteria> templates = CriteriaTemplateRegistry.getByIndustry(Industry.POWER);
        assertEquals(8, templates.size(), "电力模板应有 8 条");
    }

    @Test
    void getByIndustry_manufacturing_returns8Templates() {
        List<StructuredCriteria> templates = CriteriaTemplateRegistry.getByIndustry(Industry.MANUFACTURING);
        assertEquals(8, templates.size(), "制造模板应有 8 条");
    }

    @Test
    void getByIndustry_general_returns6Templates() {
        List<StructuredCriteria> templates = CriteriaTemplateRegistry.getByIndustry(Industry.GENERAL);
        assertEquals(6, templates.size(), "通用模板应有 6 条");
    }

    @Test
    void getByIndustry_eachIndustry_hasAtLeastOneDefaultSelected() {
        for (Industry industry : Industry.values()) {
            List<StructuredCriteria> templates = CriteriaTemplateRegistry.getByIndustry(industry);
            long defaultCount = templates.stream().filter(StructuredCriteria::defaultSelected).count();
            assertTrue(defaultCount >= 1,
                industry.name() + " 应至少有 1 条 defaultSelected 模板");
        }
    }

    @Test
    void getByIndustry_allTemplates_matchIndustry() {
        for (Industry industry : Industry.values()) {
            List<StructuredCriteria> templates = CriteriaTemplateRegistry.getByIndustry(industry);
            assertTrue(templates.stream().allMatch(t -> t.industry() == industry),
                industry.name() + " 的所有模板应匹配该行业");
        }
    }

    @Test
    void getByIndustry_allTemplates_haveOntologyEntity() {
        for (Industry industry : Industry.values()) {
            List<StructuredCriteria> templates = CriteriaTemplateRegistry.getByIndustry(industry);
            assertTrue(templates.stream().allMatch(t -> t.ontologyEntity() != null && !t.ontologyEntity().isBlank()),
                industry.name() + " 的所有模板应有 ontologyEntity");
        }
    }

    @Test
    void getDefaults_onlyReturnsDefaultSelected() {
        for (Industry industry : Industry.values()) {
            List<StructuredCriteria> defaults = CriteriaTemplateRegistry.getDefaults(industry);
            assertTrue(defaults.stream().allMatch(StructuredCriteria::defaultSelected),
                "getDefaults 应只返回 defaultSelected=true 的模板");
        }
    }

    @Test
    void getAll_returnsAllIndustries() {
        List<StructuredCriteria> all = CriteriaTemplateRegistry.getAll();
        int expectedTotal = 10 + 8 + 8 + 6; // 金融+电力+制造+通用
        assertEquals(expectedTotal, all.size());
    }
}
