package com.openjiuwen.runtime.beta.guardrail;

import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.*;

import java.util.List;
import java.util.Map;

/**
 * 护栏——Beta 策略的安全检查点。
 *
 * 与 SafetyBoundary 的区别：
 * - SafetyBoundary 是通用的安全检查（预算、权限、数据泄漏）
 * - Guardrail 是 Beta 策略专属的"行为约束"
 *
 * 在 LLM 做出决策后、执行前进行检查。
 * 如果决策不合规，Guardrail 可以修改决策或拒绝执行。
 */
public interface Guardrail {

    /** 护栏名称 */
    String name();

    /** 护栏描述 */
    String description();

    /**
     * 检查 LLM 决策是否合规。
     *
     * @param decision LLM 的决策
     * @param context  当前执行上下文
     * @return 检查结果：通过 / 拒绝 / 修改后的决策
     */
    GuardrailResult check(LLMDecision decision, GuardrailContext context);

    /**
     * 护栏检查结果。
     */
    record GuardrailResult(
        boolean passed,
        String reason,
        LLMDecision modifiedDecision
    ) {
        /** 通过 */
        public static GuardrailResult pass() {
            return new GuardrailResult(true, null, null);
        }

        /** 拒绝 */
        public static GuardrailResult reject(String reason) {
            return new GuardrailResult(false, reason, null);
        }

        /** 修改决策后通过 */
        public static GuardrailResult modify(LLMDecision modified, String reason) {
            return new GuardrailResult(true, reason, modified);
        }
    }

    /**
     * 护栏上下文——当前执行状态。
     */
    record GuardrailContext(
        TaskId taskId,
        BudgetLimits budgetLimits,
        List<LLMDecision> decisionHistory,
        Map<String, Object> extraContext
    ) {}
}
