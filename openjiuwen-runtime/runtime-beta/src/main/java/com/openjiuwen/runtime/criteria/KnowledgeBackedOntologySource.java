package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeEntry;
import com.openjiuwen.runtime.criteria.knowledge.CriteriaKnowledgeStore;
import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;

import java.util.List;

/**
 * 基于知识库的本体查询源——连接 CriteriaKnowledgeStore 和 CriteriaProposer。
 *
 * 从知识库的沉淀数据中检索历史成功标准，
 * 转换为 OntologyProposal 供 CriteriaProposer 使用。
 *
 * 闭环关键组件：
 *   KnowledgeAccumulator 沉淀 → CriteriaKnowledgeStore 存储 → KnowledgeBackedOntologySource 查询 → CriteriaProposer 提案
 */
public class KnowledgeBackedOntologySource implements OntologyCriteriaSource {

    private final CriteriaKnowledgeStore store;

    public KnowledgeBackedOntologySource(CriteriaKnowledgeStore store) {
        this.store = store;
    }

    @Override
    public List<CriteriaProposal> query(String taskDescription, Industry industry) {
        return store.loadAll().stream()
            .filter(e -> e.industry() == industry)
            .filter(e -> !e.deprecated())
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
