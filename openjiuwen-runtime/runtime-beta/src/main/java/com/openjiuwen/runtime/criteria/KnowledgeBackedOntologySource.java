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

import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeEntry;
import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;

import java.util.List;

/**
 * 基于知识库的本体查询源——连接 KnowledgeAccumulator 和 CriteriaProposer。
 *
 * 从 KnowledgeAccumulator 的沉淀数据中检索历史成功标准，
 * 转换为 OntologyProposal 供 CriteriaProposer 使用。
 *
 * 闭环关键组件：
 *   KnowledgeAccumulator 沉淀 → KnowledgeBackedOntologySource 查询 → CriteriaProposer 提案
 */
public class KnowledgeBackedOntologySource implements OntologyCriteriaSource {

    private final KnowledgeAccumulator accumulator;

    public KnowledgeBackedOntologySource(KnowledgeAccumulator accumulator) {
        this.accumulator = accumulator;
    }

    @Override
    public List<CriteriaProposal> infer(String taskDescription, Industry industry) {
        return accumulator.queryByIndustry(industry).stream()
            .filter(e -> e.totalUsage() >= 2)           // 至少使用过2次才推荐
            .filter(e -> e.successRate() >= 0.5)         // 成功率至少50%
            .map(e -> (CriteriaProposal) toOntologyProposal(e))
            .toList();
    }

    private CriteriaProposal.OntologyProposal toOntologyProposal(CriteriaKnowledgeEntry entry) {
        return new CriteriaProposal.OntologyProposal(
            entry.dimension(),
            entry.description(),
            entry.ontologyUri(),
            entry.successCount()
        );
    }
}
