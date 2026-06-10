package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultCriteriaProposer 测试——提案去重、排序、来源优先级。
 */
class DefaultCriteriaProposerTest {

    private final DefaultCriteriaProposer proposer = new DefaultCriteriaProposer();

    @Test
    void propose_financeIndustry_returnsTemplateProposals() {
        List<CriteriaProposal> proposals = proposer.propose("分析客户风险", Industry.FINANCE);
        assertFalse(proposals.isEmpty(), "金融行业应有模板提案");
        assertTrue(proposals.stream().allMatch(p -> p.source() == CriteriaProposal.Source.TEMPLATE),
            "无 LLM/本体源时，所有提案应来自模板");
    }

    @Test
    void propose_allIndustries_returnNonNull() {
        for (Industry industry : Industry.values()) {
            List<CriteriaProposal> proposals = proposer.propose("测试任务", industry);
            assertNotNull(proposals, industry.name() + " 不应返回 null");
        }
    }

    @Test
    void propose_maxProposals_respected() {
        List<CriteriaProposal> proposals = proposer.propose("分析任务", Industry.FINANCE, 3);
        assertTrue(proposals.size() <= 3, "提案数量不应超过 maxProposals");
    }

    @Test
    void propose_finance_containsKeyDimensions() {
        List<CriteriaProposal> proposals = proposer.propose("金融分析", Industry.FINANCE);
        List<String> dimensions = proposals.stream().map(CriteriaProposal::dimension).toList();
        assertTrue(dimensions.contains("数据准确性"), "金融模板应包含「数据准确性」");
        assertTrue(dimensions.contains("合规性"), "金融模板应包含「合规性」");
    }

    @Test
    void propose_noDuplicateDimensions() {
        List<CriteriaProposal> proposals = proposer.propose("制造分析", Industry.MANUFACTURING);
        long distinctCount = proposals.stream().map(CriteriaProposal::dimension).distinct().count();
        assertEquals(proposals.size(), distinctCount, "不应有重复维度");
    }

    @Test
    void propose_defaultSelectedTemplatesScoreHigher() {
        List<CriteriaProposal> proposals = proposer.propose("分析", Industry.GENERAL);
        // 第一个提案应该是 defaultSelected=true 的
        if (!proposals.isEmpty()) {
            CriteriaProposal first = proposals.get(0);
            if (first instanceof CriteriaProposal.TemplateProposal tp) {
                // defaultSelected=true 的应排在前面（分数更高）
                assertTrue(tp.defaultSelected(), "首选提案应是默认选中的");
            }
        }
    }

    @Test
    void propose_withOntologySource_ontologyWinsDedup() {
        // 创建一个返回与模板同维度提案的本体源
        OntologyCriteriaSource ontologySource = (desc, ind) -> List.of(
            new CriteriaProposal.OntologyProposal(
                "数据准确性", "本体版本的数据准确性", "ontology://test/data-acc", 10)
        );

        DefaultCriteriaProposer withOntology = new DefaultCriteriaProposer(ontologySource, LlmCriteriaSource.NONE);
        List<CriteriaProposal> proposals = withOntology.propose("金融分析", Industry.FINANCE);

        // "数据准确性"维度应只出现一次
        long count = proposals.stream().filter(p -> p.dimension().equals("数据准确性")).count();
        assertEquals(1, count, "同维度去重后应只保留一个");

        // 且应保留 ONTOLOGY 来源（优先级最高）
        CriteriaProposal dataAccProposal = proposals.stream()
            .filter(p -> p.dimension().equals("数据准确性"))
            .findFirst().orElseThrow();
        assertEquals(CriteriaProposal.Source.ONTOLOGY, dataAccProposal.source(),
            "同维度去重应保留 ONTOLOGY 来源");
    }

    @Test
    void propose_powerIndustry_containsEquipmentSafety() {
        List<CriteriaProposal> proposals = proposer.propose("电力分析", Industry.POWER);
        assertTrue(proposals.stream().anyMatch(p -> p.dimension().equals("设备安全")),
            "电力模板应包含「设备安全」");
    }

    @Test
    void propose_manufacturing_containsTraceability() {
        List<CriteriaProposal> proposals = proposer.propose("质量分析", Industry.MANUFACTURING);
        assertTrue(proposals.stream().anyMatch(p -> p.dimension().equals("追溯性")),
            "制造模板应包含「追溯性」");
    }
}
