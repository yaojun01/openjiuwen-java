package com.openjiuwen.runtime.criteria.template;

import com.openjiuwen.runtime.criteria.model.StructuredCriteria;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 成功标准模板注册表——按行业组织的通用检查清单。
 *
 * 每个行业维护 5-10 条通用成功标准，覆盖该行业的核心关注维度。
 * 模板来源：行业最佳实践 + 合规要求 + 常见验收标准。
 *
 * 使用方式：
 * 1. CriteriaProposer 按行业查询匹配模板
 * 2. 模板转换为 TemplateProposal 交给用户选择
 * 3. 用户确认后沉淀为本体知识
 */
public final class CriteriaTemplateRegistry {

    private CriteriaTemplateRegistry() {}

    // ==================== 金融场景模板 ====================

    public static final List<StructuredCriteria> FINANCE_TEMPLATES = List.of(
        new StructuredCriteria(
            "数据准确性",
            "所有数值计算结果必须与源系统一致，误差率 < 0.01%",
            true,
            "ontology://finance/criteria/data-accuracy",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "合规性",
            "分析过程和结论符合监管要求（银保监/证监/央行）",
            true,
            "ontology://finance/criteria/compliance",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "审计可追溯",
            "每一步推理和结论都有数据源引用，可回溯到原始记录",
            true,
            "ontology://finance/criteria/audit-trail",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "时效性",
            "分析结果在业务要求的时间窗口内产出（T+1 / 实时）",
            false,
            "ontology://finance/criteria/timeliness",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "风险覆盖",
            "识别并覆盖所有主要风险维度（信用/市场/操作/流动性）",
            true,
            "ontology://finance/criteria/risk-coverage",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "数据隐私",
            "不输出 PII（个人身份信息），脱敏处理符合 GDPR/个保法",
            true,
            "ontology://finance/criteria/data-privacy",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "异常检测",
            "识别并标注数据中的异常值、缺失值、离群点",
            false,
            "ontology://finance/criteria/anomaly-detection",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "可解释性",
            "结论附带原因分析，非黑箱输出，业务人员可理解",
            true,
            "ontology://finance/criteria/explainability",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "版本一致性",
            "分析基于同一数据快照版本，不混用不同时间点数据",
            false,
            "ontology://finance/criteria/version-consistency",
            Industry.FINANCE
        ),
        new StructuredCriteria(
            "边界条件",
            "处理了空数据、零除、负值等边界情况",
            false,
            "ontology://finance/criteria/boundary-conditions",
            Industry.FINANCE
        )
    );

    // ==================== 电力场景模板 ====================

    public static final List<StructuredCriteria> POWER_TEMPLATES = List.of(
        new StructuredCriteria(
            "设备安全",
            "所有操作建议不违反设备安全运行参数（电压/电流/温度）",
            true,
            "ontology://power/criteria/equipment-safety",
            Industry.POWER
        ),
        new StructuredCriteria(
            "调度合规",
            "分析符合电力调度规程和并网技术标准",
            true,
            "ontology://power/criteria/dispatch-compliance",
            Industry.POWER
        ),
        new StructuredCriteria(
            "实时性",
            "分析延迟在可接受范围内（秒级/分钟级/小时级，视场景而定）",
            true,
            "ontology://power/criteria/realtime",
            Industry.POWER
        ),
        new StructuredCriteria(
            "负荷预测精度",
            "预测误差在历史基线范围内（MAPE < 设定阈值）",
            false,
            "ontology://power/criteria/load-forecast-accuracy",
            Industry.POWER
        ),
        new StructuredCriteria(
            "拓扑正确性",
            "电网拓扑分析基于最新接线方式，无过期拓扑数据",
            true,
            "ontology://power/criteria/topology-correctness",
            Industry.POWER
        ),
        new StructuredCriteria(
            "告警完整性",
            "所有超过阈值的指标都产生了告警，无遗漏",
            true,
            "ontology://power/criteria/alert-completeness",
            Industry.POWER
        ),
        new StructuredCriteria(
            "数据时标对齐",
            "多源数据的时间戳已对齐到统一时基",
            false,
            "ontology://power/criteria/timestamp-alignment",
            Industry.POWER
        ),
        new StructuredCriteria(
            "N-1 校验",
            "关键路径已完成 N-1 安全校验",
            false,
            "ontology://power/criteria/n1-verification",
            Industry.POWER
        )
    );

    // ==================== 制造场景模板 ====================

    public static final List<StructuredCriteria> MANUFACTURING_TEMPLATES = List.of(
        new StructuredCriteria(
            "质量标准",
            "分析结果符合产品质量标准（ISO/IATF16949/行业标准）",
            true,
            "ontology://manufacturing/criteria/quality-standard",
            Industry.MANUFACTURING
        ),
        new StructuredCriteria(
            "工艺参数范围",
            "所有建议的工艺参数在安全操作窗口内",
            true,
            "ontology://manufacturing/criteria/process-parameters",
            Industry.MANUFACTURING
        ),
        new StructuredCriteria(
            "良率影响评估",
            "明确了分析结论对良率的量化影响",
            false,
            "ontology://manufacturing/criteria/yield-impact",
            Industry.MANUFACTURING
        ),
        new StructuredCriteria(
            "追溯性",
            "质量问题的根因可追溯到具体批次/工序/设备",
            true,
            "ontology://manufacturing/criteria/traceability",
            Industry.MANUFACTURING
        ),
        new StructuredCriteria(
            "设备健康度",
            "分析了关键设备健康状态对结论的影响",
            false,
            "ontology://manufacturing/criteria/equipment-health",
            Industry.MANUFACTURING
        ),
        new StructuredCriteria(
            "供应链关联",
            "识别了供应链因素（来料质量/交付延迟）对分析的影响",
            false,
            "ontology://manufacturing/criteria/supply-chain-correlation",
            Industry.MANUFACTURING
        ),
        new StructuredCriteria(
            "SPC 合规",
            "统计过程控制分析符合 SPC 标准方法",
            false,
            "ontology://manufacturing/criteria/spc-compliance",
            Industry.MANUFACTURING
        ),
        new StructuredCriteria(
            "安全操作",
            "建议的操作不违反生产安全规程",
            true,
            "ontology://manufacturing/criteria/safety-operation",
            Industry.MANUFACTURING
        )
    );

    // ==================== 通用分析模板 ====================

    public static final List<StructuredCriteria> GENERAL_TEMPLATES = List.of(
        new StructuredCriteria(
            "逻辑自洽",
            "分析结论与前提数据之间无逻辑矛盾",
            true,
            "ontology://general/criteria/logical-consistency",
            Industry.GENERAL
        ),
        new StructuredCriteria(
            "数据完整性",
            "分析基于完整数据集，缺失数据已标注并处理",
            true,
            "ontology://general/criteria/data-completeness",
            Industry.GENERAL
        ),
        new StructuredCriteria(
            "结论可操作",
            "分析产出了具体的、可执行的建议，而非泛泛而谈",
            true,
            "ontology://general/criteria/actionable-conclusion",
            Industry.GENERAL
        ),
        new StructuredCriteria(
            "置信度标注",
            "关键结论标注了置信度或不确定性范围",
            false,
            "ontology://general/criteria/confidence-annotation",
            Industry.GENERAL
        ),
        new StructuredCriteria(
            "多视角覆盖",
            "从多个角度/假设分析了问题，非单一视角",
            false,
            "ontology://general/criteria/multi-perspective",
            Industry.GENERAL
        ),
        new StructuredCriteria(
            "假设显式化",
            "所有隐含假设已被显式列出",
            false,
            "ontology://general/criteria/explicit-assumptions",
            Industry.GENERAL
        )
    );

    // ==================== 注册表索引 ====================

    private static final Map<Industry, List<StructuredCriteria>> REGISTRY = Map.of(
        Industry.FINANCE, FINANCE_TEMPLATES,
        Industry.POWER, POWER_TEMPLATES,
        Industry.MANUFACTURING, MANUFACTURING_TEMPLATES,
        Industry.GENERAL, GENERAL_TEMPLATES
    );

    /**
     * 按行业查询模板。
     */
    public static List<StructuredCriteria> getByIndustry(Industry industry) {
        return REGISTRY.getOrDefault(industry, List.of());
    }

    /**
     * 查询所有行业模板。
     */
    public static List<StructuredCriteria> getAll() {
        return REGISTRY.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    /**
     * 查询默认勾选的模板（按行业）。
     */
    public static List<StructuredCriteria> getDefaults(Industry industry) {
        return getByIndustry(industry).stream()
            .filter(StructuredCriteria::defaultSelected)
            .collect(Collectors.toList());
    }
}
