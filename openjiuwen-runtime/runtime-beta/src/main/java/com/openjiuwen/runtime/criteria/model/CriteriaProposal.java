package com.openjiuwen.runtime.criteria.model;

/**
 * 成功标准提案——三层来源的统一抽象。
 *
 * sealed interface 确保所有提案来源被显式枚举：
 * - TemplateProposal:  来自行业通用模板（StructuredCriteria）
 * - LlmInferredProposal: 来自 LLM 根据任务描述推理生成
 * - OntologyProposal:   来自领域本体的历史成功经验
 *
 * 设计原则：选择题 > 填空题。系统先提案，用户选择/确认。
 * 验证过的输入 = 延续性知识，沉淀为本体/元Agent知识。
 */
public sealed interface CriteriaProposal
    permits CriteriaProposal.TemplateProposal,
            CriteriaProposal.LlmInferredProposal,
            CriteriaProposal.OntologyProposal {

    /** 维度名称（如 "数据完整性"、"合规性"） */
    String dimension();

    /** 维度的详细描述 */
    String description();

    /** 提案来源标识 */
    Source source();

    /** 提案来源枚举 */
    enum Source {
        TEMPLATE,       // 通用模板
        LLM_INFERRED,   // LLM 推理
        ONTOLOGY        // 领域本体
    }

    /**
     * 模板提案——来自 StructuredCriteria 通用模板。
     *
     * 优先级最高（行业沉淀，最可靠），
     * 用户可直接勾选确认。
     */
    record TemplateProposal(
        String dimension,
        String description,
        boolean defaultSelected
    ) implements CriteriaProposal {
        @Override
        public Source source() { return Source.TEMPLATE; }

        public TemplateProposal {
            if (dimension == null || dimension.isBlank())
                throw new IllegalArgumentException("dimension 不能为空");
            if (description == null || description.isBlank())
                throw new IllegalArgumentException("description 不能为空");
        }
    }

    /**
     * LLM 推理提案——LLM 根据任务上下文动态生成。
     *
     * 需要附带推理过程和置信度，
     * 低置信度提案排在后面。
     */
    record LlmInferredProposal(
        String dimension,
        String description,
        String reasoning,
        float confidence
    ) implements CriteriaProposal {
        @Override
        public Source source() { return Source.LLM_INFERRED; }

        public LlmInferredProposal {
            if (dimension == null || dimension.isBlank())
                throw new IllegalArgumentException("dimension 不能为空");
            if (confidence < 0f || confidence > 1f)
                throw new IllegalArgumentException("confidence 必须在 [0, 1] 范围内");
        }
    }

    /**
     * 领域本体提案——来自历史成功经验的沉淀。
     *
     * 包含本体 URI 引用和历史成功次数，
     * 成功次数越高排序越靠前。
     */
    record OntologyProposal(
        String dimension,
        String description,
        String ontologyUri,
        int historicalSuccessCount
    ) implements CriteriaProposal {
        @Override
        public Source source() { return Source.ONTOLOGY; }

        public OntologyProposal {
            if (dimension == null || dimension.isBlank())
                throw new IllegalArgumentException("dimension 不能为空");
            if (ontologyUri == null || ontologyUri.isBlank())
                throw new IllegalArgumentException("ontologyUri 不能为空");
            if (historicalSuccessCount < 0)
                throw new IllegalArgumentException("historicalSuccessCount 不能为负");
        }
    }
}
