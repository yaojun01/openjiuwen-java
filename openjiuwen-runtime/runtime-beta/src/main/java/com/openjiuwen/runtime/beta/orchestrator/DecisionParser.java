package com.openjiuwen.runtime.beta.orchestrator;

import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.ToolName;

import java.util.Map;
import java.util.Optional;

/**
 * LLM 输出解析器——将 LLM 的文本输出解析为结构化的 LLMDecision。
 *
 * 设计要点：
 * 1. LLM 的输出不保证是合法 JSON，需要容错
 * 2. 解析失败时降级为 ContinueThinking（让 LLM 再试一次）
 * 3. 解析策略：先尝试 JSON 解析，失败则用正则提取
 *
 * 契约：
 * - 返回 Optional.empty() 表示完全无法理解 LLM 的输出
 * - 调用方应将 empty() 转为 ContinueThinking 反馈给 LLM
 */
public interface DecisionParser {

    /**
     * 解析 LLM 的文本输出为结构化决策。
     *
     * @param llmOutput LLM 的原始文本输出
     * @return 解析成功的决策，或 empty 表示无法解析
     */
    Optional<LLMDecision> parse(String llmOutput);

    /**
     * 解析 LLM 的输出，失败时返回默认决策。
     *
     * @param llmOutput      LLM 的原始文本输出
     * @param fallbackReason 降级原因的前缀
     * @return 解析成功的决策，或降级的 ContinueThinking
     */
    default LLMDecision parseOrFallback(String llmOutput, String fallbackReason) {
        return parse(llmOutput).orElseGet(() ->
            new LLMDecision.ContinueThinking(
                "解析 LLM 输出失败，原始输出: "
                    + (llmOutput.length() > 200 ? llmOutput.substring(0, 200) + "..." : llmOutput),
                fallbackReason + "。请重新以 JSON 格式输出你的决策。"
            )
        );
    }
}
