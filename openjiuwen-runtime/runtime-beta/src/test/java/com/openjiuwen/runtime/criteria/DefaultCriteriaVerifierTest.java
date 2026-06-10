package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.*;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult.*;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultCriteriaVerifier 测试——规则检查、LLM 降级、补救策略。
 */
class DefaultCriteriaVerifierTest {

    private final DefaultCriteriaVerifier verifier = new DefaultCriteriaVerifier();

    private VerifiedCriterion makeCriterion(String dimension, String description) {
        return VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal(dimension, description, true), null);
    }

    // ==================== 规则检查 ====================

    @Test
    void verify_outputContainsDimension_satisfied() {
        VerifiedCriterion criterion = makeCriterion("数据准确性", "数值计算必须准确");
        String output = "数据准确性验证通过，误差率 < 0.01%";

        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(criterion), output, "");

        assertEquals(1, results.size());
        assertTrue(results.get(0) instanceof Satisfied);
    }

    @Test
    void verify_outputMissingDimension_notSatisfied() {
        VerifiedCriterion criterion = makeCriterion("合规性", "必须符合监管要求");
        String output = "分析完成，结果如下：...";

        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(criterion), output, "");

        assertEquals(1, results.size());
        assertTrue(results.get(0) instanceof NotSatisfied);
    }

    @Test
    void verify_nullOutput_notSatisfied() {
        VerifiedCriterion criterion = makeCriterion("数据准确性", "必须准确");
        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(criterion), null, "");

        assertEquals(1, results.size());
        assertTrue(results.get(0) instanceof NotSatisfied);
    }

    @Test
    void verify_blankOutput_notSatisfied() {
        VerifiedCriterion criterion = makeCriterion("数据准确性", "必须准确");
        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(criterion), "   ", "");

        assertEquals(1, results.size());
        assertTrue(results.get(0) instanceof NotSatisfied);
    }

    @Test
    void verify_multipleCriteria_partialPass() {
        VerifiedCriterion c1 = makeCriterion("数据准确性", "必须准确");
        VerifiedCriterion c2 = makeCriterion("时效性", "时效性分析");

        String output = "数据准确性验证通过，误差率低";
        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(c1, c2), output, "");

        assertEquals(2, results.size());
        assertTrue(results.get(0) instanceof Satisfied);   // 数据准确性
        assertTrue(results.get(1) instanceof NotSatisfied); // 时效性不在输出中
    }

    @Test
    void verify_emptyCriteria_emptyResults() {
        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(), "output", "");
        assertTrue(results.isEmpty());
    }

    // ==================== 非规则关键词 → Inconclusive ====================

    @Test
    void verify_nonRuleKeyword_returnsInconclusive() {
        // "覆盖率" 在 RULE_KEYWORDS 中，但 "完整性" 不在 → 描述不含规则关键词时走 Inconclusive
        // 实际上 "必须" 在 RULE_KEYWORDS 中，所以这里用一个不含任何规则关键词的描述
        VerifiedCriterion criterion = VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal("用户体验", "界面友好", true), null);

        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(criterion), "界面体验良好", "");

        assertEquals(1, results.size());
        assertTrue(results.get(0) instanceof Inconclusive,
            "非规则关键词的标准应返回 Inconclusive");
    }

    // ==================== 补救策略 ====================

    @Test
    void verify_defaultSelectedTemplate_replanAction() {
        VerifiedCriterion criterion = makeCriterion("合规性", "必须符合监管");
        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(criterion), "无相关内容", "");

        NotSatisfied ns = (NotSatisfied) results.get(0);
        assertEquals(RemediationAction.REPLAN, ns.remediation(),
            "defaultSelected=true 的模板标准失败应触发 REPLAN");
    }

    @Test
    void verify_nonDefaultSelectedTemplate_warnContinue() {
        VerifiedCriterion criterion = VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal("时效性", "时效性分析", false), null);

        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(criterion), "无相关内容", "");

        NotSatisfied ns = (NotSatisfied) results.get(0);
        assertEquals(RemediationAction.WARN_CONTINUE, ns.remediation(),
            "defaultSelected=false 的模板标准失败应 WARN_CONTINUE");
    }

    // ==================== 汇总方法 ====================

    @Test
    void verify_userOverrideMissesKeywords_stillRuleChecks() {
        // 用户覆盖后的描述不含规则关键词，但原始模板有"必须"
        CriteriaProposal.TemplateProposal original =
            new CriteriaProposal.TemplateProposal("合规性", "必须符合监管要求", true);
        VerifiedCriterion criterion = VerifiedCriterion.from(original, "自定义合规检查");

        String output = "合规性检查已完成";
        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(criterion), output, "");

        // 即使 finalDescription 不含规则关键词，originalProposal.description 含"必须"也应走规则检查
        assertEquals(1, results.size());
        // 应该是 Satisfied 而非 Inconclusive
        assertTrue(results.get(0) instanceof Satisfied,
            "原始描述含规则关键词时，即使用户覆盖丢失了关键词，也应走规则检查");
    }

    @Test
    void allSatisfied_allPass_returnsTrue() {
        VerifiedCriterion c1 = makeCriterion("数据准确性", "必须准确");
        String output = "数据准确性验证通过";
        List<CriteriaVerificationResult> results = verifier.verify(List.of(c1), output, "");
        assertTrue(verifier.allSatisfied(results));
    }

    @Test
    void allSatisfied_someFail_returnsFalse() {
        VerifiedCriterion c1 = makeCriterion("数据准确性", "必须准确");
        VerifiedCriterion c2 = makeCriterion("合规性", "必须合规");
        List<CriteriaVerificationResult> results = verifier.verify(
            List.of(c1, c2), "只有数据准确性", "");

        assertFalse(verifier.allSatisfied(results));
    }

    @Test
    void allSatisfied_emptyResults_returnsFalse() {
        assertFalse(verifier.allSatisfied(List.of()),
            "空验证结果应返回 false（vacuous truth 防护）");
    }
}
