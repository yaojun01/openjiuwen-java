package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import com.openjiuwen.runtime.criteria.template.CriteriaTemplateRegistry;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认提案引擎——三层提案合并实现。
 *
 * 合并策略：
 * 1. 收集三层来源的所有提案
 * 2. 按 dimension 去重：本体 > 模板 > LLM推理（来源优先级）
 * 3. 同一 dimension 内保留最高优先级来源的提案
 * 4. 按综合分数排序：来源权重 * 置信度
 * 5. 截断到 maxProposals
 *
 * 来源优先级（去重时）：
 * - ONTOLOGY (权重 1.0): 历史验证过的知识，最高可信度
 * - TEMPLATE  (权重 0.8): 行业通用模板，次高可信度
 * - LLM_INFERRED (权重 0.6): LLM 推理，需更多验证
 */
public class DefaultCriteriaProposer implements CriteriaProposer {

    private final OntologyCriteriaSource ontologySource;
    private final LlmCriteriaSource llmSource;

    /** 来源权重 */
    private static final Map<CriteriaProposal.Source, Double> SOURCE_WEIGHTS = Map.of(
        CriteriaProposal.Source.ONTOLOGY, 1.0,
        CriteriaProposal.Source.TEMPLATE, 0.8,
        CriteriaProposal.Source.LLM_INFERRED, 0.6
    );

    /** 来源去重优先级（数值越小优先级越高） */
    private static final Map<CriteriaProposal.Source, Integer> SOURCE_PRIORITY = Map.of(
        CriteriaProposal.Source.ONTOLOGY, 1,
        CriteriaProposal.Source.TEMPLATE, 2,
        CriteriaProposal.Source.LLM_INFERRED, 3
    );

    public DefaultCriteriaProposer(OntologyCriteriaSource ontologySource,
                                   LlmCriteriaSource llmSource) {
        this.ontologySource = ontologySource;
        this.llmSource = llmSource;
    }

    /**
     * 无本体和 LLM 源的简化构造（仅使用模板）。
     */
    public DefaultCriteriaProposer() {
        this(OntologyCriteriaSource.NONE, LlmCriteriaSource.NONE);
    }

    @Override
    public List<CriteriaProposal> propose(String taskDescription, Industry industry, int maxProposals) {
        // ===== 第一层：行业模板 =====
        List<CriteriaProposal> templateProposals = CriteriaTemplateRegistry.getByIndustry(industry)
            .stream()
            .map(StructuredCriteria::toProposal)
            .collect(Collectors.toList());

        // ===== 第二层：LLM 推理 =====
        List<CriteriaProposal> llmProposals = llmSource.infer(taskDescription, industry);

        // ===== 第三层：领域本体 =====
        List<CriteriaProposal> ontologyProposals = ontologySource.query(taskDescription, industry);

        // ===== 合并去重 =====
        List<CriteriaProposal> all = new ArrayList<>();
        all.addAll(templateProposals);
        all.addAll(llmProposals);
        all.addAll(ontologyProposals);

        // 按 dimension 去重，保留优先级最高的来源
        Map<String, CriteriaProposal> deduped = new LinkedHashMap<>();
        for (CriteriaProposal proposal : all) {
            deduped.merge(proposal.dimension(), proposal, (existing, incoming) -> {
                int existingPriority = SOURCE_PRIORITY.getOrDefault(existing.source(), 99);
                int incomingPriority = SOURCE_PRIORITY.getOrDefault(incoming.source(), 99);
                // 优先级数值小的胜出；相同时保留先到的（模板优先于LLM）
                return incomingPriority < existingPriority ? incoming : existing;
            });
        }

        // ===== 排序：综合分数 = 来源权重 * 置信度 =====
        List<CriteriaProposal> sorted = deduped.values().stream()
            .sorted(Comparator.comparingDouble(this::score).reversed())
            .collect(Collectors.toList());

        // ===== 截断 =====
        return sorted.stream()
            .limit(maxProposals)
            .collect(Collectors.toList());
    }

    /**
     * 计算提案的综合分数。
     */
    private double score(CriteriaProposal proposal) {
        double weight = SOURCE_WEIGHTS.getOrDefault(proposal.source(), 0.5);
        double confidence = switch (proposal) {
            case CriteriaProposal.TemplateProposal tp -> tp.defaultSelected() ? 0.95 : 0.7;
            case CriteriaProposal.LlmInferredProposal lip -> lip.confidence();
            case CriteriaProposal.OntologyProposal op ->
                Math.min(1.0, 0.5 + (op.historicalSuccessCount() * 0.1));
        };
        return weight * confidence;
    }
}
