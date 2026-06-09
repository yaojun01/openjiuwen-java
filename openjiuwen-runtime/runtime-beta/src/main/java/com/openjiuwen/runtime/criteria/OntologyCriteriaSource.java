package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;

import java.util.List;

/**
 * 领域本体查询接口——从本体存储中检索历史成功标准。
 *
 * 实现可对接：
 * - 内置的 KnowledgeAccumulator（JSON/SQLite 存储）
 * - 外部本体服务（OWL/RDF 端点）
 * - 向量数据库（语义检索）
 */
public interface OntologyCriteriaSource {

    /**
     * 从本体中查询与任务相关的历史成功标准。
     *
     * @param taskDescription 任务描述
     * @param industry        行业
     * @return 本体提案列表
     */
    List<CriteriaProposal> infer(String taskDescription, Industry industry);

    /**
     * 默认的查询接口别名。
     */
    default List<CriteriaProposal> query(String taskDescription, Industry industry) {
        return infer(taskDescription, industry);
    }

    /**
     * 空实现——无本体数据时的 fallback。
     */
    OntologyCriteriaSource NONE = (desc, ind) -> List.of();
}
