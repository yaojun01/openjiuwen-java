package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

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
    // COR-P2-001: 保留内置 5 个护栏，追加 CriteriaGuardrail
    public static GuardrailEngine createWith(List<VerifiedCriterion> successCriteria) {
        GuardrailEngine engine = new GuardrailEngine();
        if (successCriteria != null && !successCriteria.isEmpty()) {
            engine = engine.withExtra(new CriteriaGuardrail(successCriteria));
        }
        return engine;
    }
}
