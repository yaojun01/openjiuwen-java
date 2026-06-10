package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CriteriaGuardrail 测试——完成检查、放弃转换、null 防护。
 */
class CriteriaGuardrailTest {

    private VerifiedCriterion makeDefaultSelected(String dimension) {
        return VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal(dimension, dimension + "描述", true), null);
    }

    private VerifiedCriterion makeNonDefault(String dimension) {
        return VerifiedCriterion.from(
            new CriteriaProposal.TemplateProposal(dimension, dimension + "描述", false), null);
    }

    // ==================== 构造函数 ====================

    @Test
    void constructor_nullInput_throwsNPE() {
        assertThrows(NullPointerException.class,
            () -> new CriteriaGuardrail(null));
    }

    @Test
    void constructor_emptyList_ok() {
        CriteriaGuardrail guardrail = new CriteriaGuardrail(List.of());
        assertEquals("criteria-check", guardrail.name());
    }

    // ==================== 完成检查 ====================

    @Test
    void check_completeCoversAllDimensions_pass() {
        List<VerifiedCriterion> criteria = List.of(
            makeDefaultSelected("数据准确性"),
            makeDefaultSelected("合规性")
        );
        CriteriaGuardrail guardrail = new CriteriaGuardrail(criteria);

        LLMDecision.Complete complete = new LLMDecision.Complete(
            "数据准确性验证通过，合规性检查OK", 0.95, "总结");
        Guardrail.GuardrailResult result = guardrail.check(complete, null);

        assertTrue(result.passed());
    }

    @Test
    void check_completeMissingDimension_reject() {
        List<VerifiedCriterion> criteria = List.of(
            makeDefaultSelected("数据准确性"),
            makeDefaultSelected("合规性")
        );
        CriteriaGuardrail guardrail = new CriteriaGuardrail(criteria);

        LLMDecision.Complete complete = new LLMDecision.Complete(
            "只有数据准确性内容", 0.8, "总结");
        Guardrail.GuardrailResult result = guardrail.check(complete, null);

        assertFalse(result.passed());
        assertTrue(result.reason().contains("合规性"));
    }

    @Test
    void check_completeNoDefaultSelected_pass() {
        List<VerifiedCriterion> criteria = List.of(
            makeNonDefault("时效性")
        );
        CriteriaGuardrail guardrail = new CriteriaGuardrail(criteria);

        LLMDecision.Complete complete = new LLMDecision.Complete("任何输出", 0.7, "总结");
        Guardrail.GuardrailResult result = guardrail.check(complete, null);

        assertTrue(result.passed(), "无 defaultSelected 维度时应直接通过");
    }

    @Test
    void check_emptyCriteria_pass() {
        CriteriaGuardrail guardrail = new CriteriaGuardrail(List.of());
        LLMDecision.Complete complete = new LLMDecision.Complete("输出", 0.9, "总结");
        Guardrail.GuardrailResult result = guardrail.check(complete, null);
        assertTrue(result.passed());
    }

    // ==================== 放弃转换 ====================

    @Test
    void check_giveUp_convertsToRequestHumanHelp() {
        List<VerifiedCriterion> criteria = List.of(
            makeDefaultSelected("数据准确性")
        );
        CriteriaGuardrail guardrail = new CriteriaGuardrail(criteria);

        LLMDecision.GiveUp giveUp = new LLMDecision.GiveUp("做不下去了", "部分结果");
        Guardrail.GuardrailResult result = guardrail.check(giveUp, null);

        // modify() sets passed=true and provides modifiedDecision
        assertTrue(result.passed());
        assertNotNull(result.modifiedDecision());
        assertTrue(result.modifiedDecision() instanceof LLMDecision.RequestHumanHelp);
    }

    @Test
    void check_giveUp_reasonTruncated() {
        List<VerifiedCriterion> criteria = List.of(makeDefaultSelected("数据准确性"));
        CriteriaGuardrail guardrail = new CriteriaGuardrail(criteria);

        String longReason = "A".repeat(500);
        LLMDecision.GiveUp giveUp = new LLMDecision.GiveUp(longReason, "部分结果");
        Guardrail.GuardrailResult result = guardrail.check(giveUp, null);

        LLMDecision.RequestHumanHelp help = (LLMDecision.RequestHumanHelp) result.modifiedDecision();
        assertTrue(help.question().length() < longReason.length() + 200,
            "超长原因应被截断");
    }

    @Test
    void check_giveUpNullReason_handledGracefully() {
        List<VerifiedCriterion> criteria = List.of(makeDefaultSelected("数据准确性"));
        CriteriaGuardrail guardrail = new CriteriaGuardrail(criteria);

        LLMDecision.GiveUp giveUp = new LLMDecision.GiveUp(null, null);
        Guardrail.GuardrailResult result = guardrail.check(giveUp, null);
        // 不应抛 NPE，modify 语义是 passed=true
        assertTrue(result.passed());
        assertNotNull(result.modifiedDecision());
    }

    // ==================== 其他决策类型 ====================

    @Test
    void check_callTool_pass() {
        CriteriaGuardrail guardrail = new CriteriaGuardrail(
            List.of(makeDefaultSelected("数据准确性")));

        LLMDecision.CallTool callTool = new LLMDecision.CallTool(
            new com.openjiuwen.core.kernel.model.ToolName("tool"), Collections.emptyMap(), "reasoning");
        Guardrail.GuardrailResult result = guardrail.check(callTool, null);
        assertTrue(result.passed(), "非 Complete/GiveUp 决策应直接通过");
    }
}
