package com.openjiuwen.runtime.beta.context;
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

import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.ToolName;

import java.util.List;

/**
 * 自我反思触发器 v2——Beta 策略的"元认知"组件。
 *
 * 触发条件（4 个）：
 * 1. 连续 N 次工具调用（默认 5）——防止无目的地调工具
 * 2. 预算消耗过半——是否需要调整优先级
 * 3. 连续重复调用同一工具（默认 3）——可能陷入循环
 * 4. Replan 后的第一次决策——确认新路径是否合理
 *
 * 使用方式：
 * - 每次决策执行后调用 recordDecision()
 * - 每次循环开始时调用 shouldTrigger()
 * - 触发后调用 buildReflectionPrompt() 获取反思提示
 * - 反思完成后调用 reset()
 */
public class SelfReflectionTrigger {

    /** 连续工具调用次数阈值 */
    private final int consecutiveToolCallThreshold;
    /** 连续同一工具调用阈值 */
    private final int sameToolRepeatThreshold;

    /** 状态 */
    private boolean triggered;
    private int consecutiveToolCalls;
    private ToolName lastToolName;
    private int sameToolCount;
    private String triggerReason;

    public SelfReflectionTrigger() {
        this(5, 3);
    }

    public SelfReflectionTrigger(int consecutiveToolCallThreshold, int sameToolRepeatThreshold) {
        this.consecutiveToolCallThreshold = consecutiveToolCallThreshold;
        this.sameToolRepeatThreshold = sameToolRepeatThreshold;
        this.triggered = false;
        this.consecutiveToolCalls = 0;
        this.lastToolName = null;
        this.sameToolCount = 0;
        this.triggerReason = null;
    }

    /**
     * 记录一次决策。自动追踪连续工具调用。
     */
    public void recordDecision(LLMDecision decision) {
        switch (decision) {
            case LLMDecision.CallTool ct -> {
                consecutiveToolCalls++;
                ToolName currentTool = ct.toolName();
                if (currentTool.equals(lastToolName)) {
                    sameToolCount++;
                } else {
                    sameToolCount = 1;
                    lastToolName = currentTool;
                }

                // 检查重复同一工具
                if (sameToolCount >= sameToolRepeatThreshold) {
                    triggered = true;
                    triggerReason = "连续调用同一工具 " + currentTool + " " + sameToolCount + " 次";
                }
                // 检查连续工具调用总数
                else if (consecutiveToolCalls >= consecutiveToolCallThreshold) {
                    triggered = true;
                    triggerReason = "连续调用 " + consecutiveToolCalls + " 次工具，没有穿插思考";
                }
            }
            case LLMDecision.Replan _ -> {
                consecutiveToolCalls = 0;
                sameToolCount = 0;
                lastToolName = null;
                // Replan 后不强触发反思，但在 prompt 中注入确认提示
            }
            default -> {
                consecutiveToolCalls = 0;
                sameToolCount = 0;
                lastToolName = null;
            }
        }
    }

    /** 向后兼容 */
    public void recordToolCall() {
        consecutiveToolCalls++;
        if (consecutiveToolCalls >= consecutiveToolCallThreshold) {
            triggered = true;
            triggerReason = "连续调用 " + consecutiveToolCalls + " 次工具";
        }
    }

    /** 向后兼容 */
    public void recordNonToolDecision() {
        consecutiveToolCalls = 0;
    }

    /**
     * 是否应该触发反思。
     */
    public boolean shouldTrigger() {
        return triggered;
    }

    /**
     * 检查预算消耗是否触发反思。
     * @param usedTokens 已消耗 Token
     * @param maxTokens  总预算 Token
     * @return 是否应该触发反思
     */
    public boolean checkBudgetTrigger(long usedTokens, long maxTokens) {
        if (usedTokens > maxTokens * 0.5 && !triggered) {
            triggered = true;
            triggerReason = "预算消耗已过半 (" + usedTokens + "/" + maxTokens + ")";
            return true;
        }
        return false;
    }

    /**
     * 获取触发原因（用于审计日志）。
     */
    public String triggerReason() {
        return triggerReason;
    }

    /**
     * 重置反思状态（反思完成后调用）。
     */
    public void reset() {
        triggered = false;
        triggerReason = null;
    }

    /**
     * 生成反思提示词。
     * @param goal 当前目标
     * @return 给 LLM 的反思提示
     */
    public String buildReflectionPrompt(String goal) {
        String reason = triggerReason != null ? triggerReason : "定期反思";
        return """
            [系统注入] 请停下来反思。

            触发原因：%s
            原始目标：%s
            已连续调用 %d 次工具。

            请回答：
            1. 到目前为止的进展如何？
            2. 是否偏离了原始目标？
            3. 当前策略是否正确？是否需要调整？
            4. 如果连续调用同一工具无新结果，考虑 replan 换策略
            5. 下一步应该做什么？

            请在 reasoning 中体现你的反思，然后做出下一个决策。
            """.formatted(reason, goal, consecutiveToolCalls);
    }
}
