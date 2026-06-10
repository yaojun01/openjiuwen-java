package com.openjiuwen.runtime.beta.guardrail;

import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.BudgetLimits;
import com.openjiuwen.core.kernel.model.TaskId;
import com.openjiuwen.core.kernel.model.ToolName;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 护栏引擎——Beta 策略的决策检查器。
 *
 * 聚合所有 Guardrail 实现，在 LLM 决策执行前逐一检查。
 * 任何一个 Guardrail 拒绝都会阻止执行。
 *
 * 内置 5 个护栏：
 * 1. BudgetGuardrail —— 预算检查
 * 2. ToolWhitelistGuardrail —— 工具白名单
 * 3. RepetitionGuardrail —— 重复决策检测
 * 4. ConfidenceGuardrail —— 置信度检查
 * 5. SafetyGuardrail —— 安全边界映射
 */
@Component
public class GuardrailEngine {

    private final List<Guardrail> guardrails;

    public GuardrailEngine() {
        this.guardrails = List.of(
            new BudgetGuardrail(),
            new ToolWhitelistGuardrail(),
            new RepetitionGuardrail(),
            new ConfidenceGuardrail(),
            new SafetyGuardrail()
        );
    }

    public GuardrailEngine(List<Guardrail> guardrails) {
        this.guardrails = List.copyOf(guardrails);
    }

    /**
     * COR-P2-001: 创建包含所有内置护栏 + 额外护栏的新引擎。
     */
    public GuardrailEngine withExtra(Guardrail extra) {
        List<Guardrail> combined = new ArrayList<>(this.guardrails);
        combined.add(extra);
        return new GuardrailEngine(combined);
    }

    /**
     * 检查 LLM 决策是否合规。
     * 逐一执行所有护栏，返回第一个拒绝的结果。
     *
     * 如果某个护栏通过 modify() 修改了决策（如 GiveUp → RequestHumanHelp），
     * 使用修改后的版本继续检查后续护栏，并在最终结果中携带修改后的决策。
     *
     * @param decision LLM 的决策
     * @param context  护栏上下文
     * @return 全部通过返回 pass（可能携带 modifiedDecision），否则返回第一个拒绝原因
     */
    public Guardrail.GuardrailResult evaluate(LLMDecision decision, Guardrail.GuardrailContext context) {
        LLMDecision currentDecision = decision;
        for (Guardrail guardrail : guardrails) {
            Guardrail.GuardrailResult result = guardrail.check(currentDecision, context);
            if (!result.passed()) {
                return result;
            }
            // 如果护栏修改了决策，使用修改后的版本继续检查
            if (result.modifiedDecision() != null) {
                currentDecision = result.modifiedDecision();
            }
        }
        // 如果决策被某个护栏修改过（如 GiveUp → RequestHumanHelp），返回带修改决策的结果
        if (currentDecision != decision) {
            return new Guardrail.GuardrailResult(true, null, currentDecision);
        }
        return Guardrail.GuardrailResult.pass();
    }

    // ==================== 内置护栏实现 ====================

    /**
     * 预算护栏：检查是否超出预算。
     */
    static class BudgetGuardrail implements Guardrail {
        @Override public String name() { return "budget"; }
        @Override public String description() { return "检查预算是否充足"; }

        @Override
        public Guardrail.GuardrailResult check(LLMDecision decision, Guardrail.GuardrailContext ctx) {
            if (ctx.budgetLimits().isExceeded()) {
                return Guardrail.GuardrailResult.reject("预算已耗尽: " + ctx.budgetLimits());
            }
            return Guardrail.GuardrailResult.pass();
        }
    }

    /**
     * 工具白名单护栏：检查 LLM 是否尝试调用未授权的工具。
     */
    static class ToolWhitelistGuardrail implements Guardrail {
        @Override public String name() { return "tool-whitelist"; }
        @Override public String description() { return "检查工具是否在白名单中"; }

        @Override
        public Guardrail.GuardrailResult check(LLMDecision decision, Guardrail.GuardrailContext ctx) {
            if (decision instanceof LLMDecision.CallTool ct) {
                // 可以从 extraContext 获取白名单
                @SuppressWarnings("unchecked")
                Set<String> whitelist = (Set<String>) ctx.extraContext().getOrDefault("toolWhitelist", Set.of());
                if (!whitelist.isEmpty() && !whitelist.contains(ct.toolName().value())) {
                    return Guardrail.GuardrailResult.reject(
                        "工具 " + ct.toolName() + " 不在白名单中: " + whitelist);
                }
            }
            return Guardrail.GuardrailResult.pass();
        }
    }

    /**
     * 重复护栏：检测 LLM 是否陷入重复调用同一个工具的循环。
     */
    static class RepetitionGuardrail implements Guardrail {
        private static final int MAX_REPEAT = 3;

        @Override public String name() { return "repetition"; }
        @Override public String description() { return "检测重复决策循环"; }

        @Override
        public Guardrail.GuardrailResult check(LLMDecision decision, Guardrail.GuardrailContext ctx) {
            if (decision instanceof LLMDecision.CallTool ct) {
                long count = ctx.decisionHistory().stream()
                    .filter(d -> d instanceof LLMDecision.CallTool call && call.toolName().equals(ct.toolName()))
                    .count();
                if (count >= MAX_REPEAT) {
                    return Guardrail.GuardrailResult.reject(
                        "工具 " + ct.toolName() + " 已被调用 " + count + " 次，可能陷入循环");
                }
            }
            return Guardrail.GuardrailResult.pass();
        }
    }

    /**
     * 置信度护栏：低置信度的完成决策需要人类审核。
     */
    static class ConfidenceGuardrail implements Guardrail {
        private static final double MIN_CONFIDENCE = 0.7;

        @Override public String name() { return "confidence"; }
        @Override public String description() { return "检查完成决策的置信度"; }

        @Override
        public Guardrail.GuardrailResult check(LLMDecision decision, Guardrail.GuardrailContext ctx) {
            if (decision instanceof LLMDecision.Complete c && c.confidence() < MIN_CONFIDENCE) {
                return Guardrail.GuardrailResult.reject(
                    "完成置信度过低: " + c.confidence() + " < " + MIN_CONFIDENCE + "，建议继续执行或请求人类帮助");
            }
            return Guardrail.GuardrailResult.pass();
        }
    }

    /**
     * 安全护栏：映射 SafetyBoundary 的检查结果。
     */
    static class SafetyGuardrail implements Guardrail {
        @Override public String name() { return "safety"; }
        @Override public String description() { return "安全边界检查"; }

        @Override
        public Guardrail.GuardrailResult check(LLMDecision decision, Guardrail.GuardrailContext ctx) {
            // 实际实现中会调用 SafetyBoundary.checkToolCall / checkLLMOutput
            return Guardrail.GuardrailResult.pass();
        }
    }
}
