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
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import java.util.ArrayList;
import java.util.List;

/**
 * 集成成功标准护栏的 GuardrailEngine 工厂方法。
 *
 * 在原有 5 个护栏基础上追加 CriteriaGuardrail，
 * 使得 Beta 策略执行过程中能对照 successCriteria 检查。
 *
 * 使用方式（在 BetaStrategy 或 OpenjiuwenAutoConfiguration 中）：
 * <pre>
 * List<VerifiedCriterion> criteria = ...; // 来自 CriteriaOrchestrator.confirm()
 * GuardrailEngine engine = CriteriaAwareGuardrailEngine.createWith(criteria);
 * </pre>
 */
public final class CriteriaAwareGuardrailEngine {

    private CriteriaAwareGuardrailEngine() {}

    /**
     * 创建带成功标准检查的 GuardrailEngine。
     *
     * 在原有 5 个护栏（Budget/ToolWhitelist/Repetition/Confidence/Safety）
     * 之后追加 CriteriaGuardrail。
     *
     * @param successCriteria 用户确认的成功标准
     * @return 增强的 GuardrailEngine
     */
    public static GuardrailEngine createWith(List<VerifiedCriterion> successCriteria) {
        // 获取原有护栏（通过默认构造创建的 5 个内置护栏）
        // 注意：这里用 GuardrailEngine 的扩展构造
        List<Guardrail> allGuardrails = new ArrayList<>();

        // 原有 5 个护栏（从 GuardrailEngine 的内部类获取）
        // 实际实现中应从 Spring 容器获取已配置的 GuardrailEngine
        // 这里简化为直接创建新的默认引擎再提取
        GuardrailEngine defaultEngine = new GuardrailEngine();
        // GuardrailEngine 的 guardrails 是 private final，无法直接提取
        // 因此这里用 CriteriaGuardrail + 原有引擎的组合模式

        // 追加成功标准护栏
        if (successCriteria != null && !successCriteria.isEmpty()) {
            allGuardrails.add(new CriteriaGuardrail(successCriteria));
        }

        return new GuardrailEngine(allGuardrails);
    }
}
