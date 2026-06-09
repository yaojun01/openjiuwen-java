package com.openjiuwen.runtime.criteria.model;

/**
 * 结构化成功标准模板——按行业/场景的通用检查清单。
 *
 * 每个模板是行业经验的结晶：
 * - dimension:       维度名称（如"数据完整性"）
 * - description:     详细描述，面向用户的选择题选项文本
 * - defaultSelected: 是否默认勾选（行业必选项默认勾选）
 * - ontologyEntity:  关联的本体实体 URI（用于知识沉淀回路）
 * - industry:        所属行业/场景
 *
 * 使用方式：
 * 1. CriteriaProposer 根据行业筛选匹配的模板
 * 2. 转换为 TemplateProposal 提交给用户
 * 3. 用户确认后，关联的 ontologyEntity 参与知识沉淀
 */
public record StructuredCriteria(
    String dimension,
    String description,
    boolean defaultSelected,
    String ontologyEntity,
    Industry industry
) {

    public StructuredCriteria {
        if (dimension == null || dimension.isBlank())
            throw new IllegalArgumentException("dimension 不能为空");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("description 不能为空");
        if (industry == null)
            throw new IllegalArgumentException("industry 不能为空");
        if (ontologyEntity == null)
            ontologyEntity = "ontology://criteria/" + industry.name().toLowerCase() + "/" + dimension;
    }

    /** 转换为模板提案 */
    public CriteriaProposal.TemplateProposal toProposal() {
        return new CriteriaProposal.TemplateProposal(dimension, description, defaultSelected);
    }

    /**
     * 行业/场景枚举。
     */
    public enum Industry {
        FINANCE("金融"),
        POWER("电力"),
        MANUFACTURING("制造"),
        GENERAL("通用分析");

        private final String label;

        Industry(String label) { this.label = label; }

        public String label() { return label; }
    }
}
