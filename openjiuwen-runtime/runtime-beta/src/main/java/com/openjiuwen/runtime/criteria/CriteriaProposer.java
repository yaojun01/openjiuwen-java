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
 * 成功标准提案引擎——三层提案的合并、去重、排序。
 *
 * 职责：
 * 1. 从三个来源收集提案（模板 / LLM推理 / 领域本体）
 * 2. 去重（按 dimension 合并，保留最高优先级来源）
 * 3. 排序（置信度加权，避免用户疲劳）
 * 4. 限制提案数量（默认最多 12 条）
 *
 * 闭环：
 *   本体 → 提案 → 用户确认 → Agent执行 → SafetyBoundary检查 → 成功经验回写本体
 */
public interface CriteriaProposer {

    /**
     * 为指定任务生成成功标准提案。
     *
     * @param taskDescription 任务描述
     * @param industry        行业/场景
     * @param maxProposals    最大提案数（避免用户疲劳）
     * @return 去重、排序后的提案列表
     */
    List<CriteriaProposal> propose(String taskDescription, Industry industry, int maxProposals);

    /**
     * 为指定任务生成成功标准提案（使用默认上限 12 条）。
     */
    default List<CriteriaProposal> propose(String taskDescription, Industry industry) {
        return propose(taskDescription, industry, 12);
    }
}
