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

import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;

import java.util.List;

/**
 * LLM 推理成功标准接口——让 LLM 根据任务描述动态生成标准。
 *
 * 实现需要调用 AgentKernel.think()，传入结构化 prompt，
 * 解析 LLM 返回的 JSON 格式标准列表。
 */
public interface LlmCriteriaSource {

    /**
     * LLM 推理生成成功标准。
     *
     * @param taskDescription 任务描述
     * @param industry        行业
     * @return LLM 推理提案列表
     */
    List<CriteriaProposal> infer(String taskDescription, Industry industry);

    /**
     * 空实现——无 LLM 调用时的 fallback。
     */
    LlmCriteriaSource NONE = (desc, ind) -> List.of();
}
