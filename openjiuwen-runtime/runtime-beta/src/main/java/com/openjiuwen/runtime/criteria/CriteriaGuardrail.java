package com.openjiuwen.runtime.criteria;
/**
 * ============================================================
 *  P2 DRAFT -- NOT part of P1 default compilation.
 *
 * This file belongs to the `runtime-beta` module, which is excluded from
 * P1's default Maven profile. It is only compiled with `-P all`.
 *
 * P2 will replace this draft with the final implementation.
 * See: docs/architecture/05-beta-llm-autonomous-orchestration.md
 * ============================================================
 */

import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import java.util.List;

/**
 * 成功标准护栏——在 Beta 策略执行过程中检查决策是否偏离成功标准。
 *
 * 与 SafetyBoundary 的关系：
 * - SafetyBoundary：通用安全（预算、权限、数据泄漏）
 * - CriteriaGuardrail：业务语义层面的标准对照
 *
 * 检查时机：
 * - LLMDecision.Complete 决策前：检查是否所有关键标准都有对应的执行证据
 * - LLMDecision.SpawnSubTasks 决策前：检查子目标是否覆盖了主要标准
 *
 * 注意：这不是替代 CriteriaVerifier 的完整验证，
 * 而是 Beta 执行过程中的"快速对照"，防止 Agent 提前声称完成。
 */
public class CriteriaGuardrail implements Guardrail {

    private final List<VerifiedCriterion> successCriteria;

    public CriteriaGuardrail(List<VerifiedCriterion> successCriteria) {
        this.successCriteria = List.copyOf(successCriteria);
    }

    @Override
    public String name() {
        return "criteria-check";
    }

    @Override
    public String description() {
        return "检查 Agent 决策是否符合预设的成功标准";
    }

    @Override
    public GuardrailResult check(LLMDecision decision, GuardrailContext context) {
        if (successCriteria.isEmpty()) {
            return GuardrailResult.pass();
        }

        return switch (decision) {
            case LLMDecision.Complete complete ->
                checkCompleteDecision(complete, context);
            case LLMDecision.GiveUp giveUp ->
                checkGiveUpDecision(giveUp);
            default ->
                GuardrailResult.pass();
        };
    }

    /**
     * 检查完成决策：Agent 声称完成时，快速对照标准。
     */
    private GuardrailResult checkCompleteDecision(LLMDecision.Complete complete, GuardrailContext context) {
        // 提取关键标准（模板来源且默认选中的 = 关键标准）
        List<String> criticalDimensions = successCriteria.stream()
            .filter(c -> c.originalProposal() instanceof com.openjiuwen.runtime.criteria.model.CriteriaProposal.TemplateProposal tp
                && tp.defaultSelected())
            .map(VerifiedCriterion::dimension)
            .toList();

        if (criticalDimensions.isEmpty()) {
            return GuardrailResult.pass();
        }

        // 简化检查：Agent 的输出中是否提及了关键维度
        String output = complete.output();
        long coveredCount = criticalDimensions.stream()
            .filter(dim -> output != null && output.contains(dim))
            .count();

        if (coveredCount < criticalDimensions.size()) {
            List<String> missing = criticalDimensions.stream()
                .filter(dim -> output == null || !output.contains(dim))
                .toList();

            return GuardrailResult.reject(
                "完成决策未覆盖关键成功标准: " + missing
                + "。请继续执行，确保所有标准都被满足。");
        }

        return GuardrailResult.pass();
    }

    /**
     * 检查放弃决策：Agent 放弃时，提醒哪些标准未达成。
     */
    private GuardrailResult checkGiveUpDecision(LLMDecision.GiveUp giveUp) {
        List<String> allDimensions = successCriteria.stream()
            .map(VerifiedCriterion::dimension)
            .toList();

        if (!allDimensions.isEmpty()) {
            return GuardrailResult.modify(
                new LLMDecision.RequestHumanHelp(
                    "Agent 尝试放弃任务，但以下成功标准尚未达成: "
                        + allDimensions + "。原因: " + giveUp.reason()
                        + "。请决定是否继续或调整目标。",
                    giveUp.partialResult()
                ),
                "将 GiveUp 转为 RequestHumanHelp，避免未经确认的放弃"
            );
        }

        return GuardrailResult.pass();
    }
}
