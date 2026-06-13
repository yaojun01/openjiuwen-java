package com.openjiuwen.examples.claims;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 示例 #7：保险理赔核赔全案复审 Agent（点金 DianJin-SKILLS 移植）。
 *
 * <p>承担两种编排变体的共享域工具：
 * <ul>
 *   <li>变体 A（LLM 自动规划）：经 {@code AgentClient.invokeStream} → AlphaStrategy 自动 Plan-Execute-Verify；</li>
 *   <li>变体 B（手工静态图）：见 {@link ClaimAdjudicationGraph}，直接交 DefaultPregelExecutor 执行。</li>
 * </ul>
 * 3 个 {@code @Tool} 背靠 {@link ClaimCase} 合成 fixture，覆盖全案数据 6 类。
 *
 * <p>注：@Tool 参数按 Java 参数名（caseNo）注入（AgentBeanPostProcessor 用 param.getName()），
 * 故静态图 TOOL_CALL 节点的 inputs key 必须用参数名（caseNo），而非 @Param 的中文描述。
 */
@Agent(
        name = "claim-adjudication-agent",
        description = "保险理赔核赔全案复审 Agent — PEV 策略驱动，自动规划复审流程并给出决策建议",
        autonomyLevel = AutonomyLevel.ASSISTED,
        systemPrompt = """
                你是保险理赔【核赔全案复审专家】，对已初核案件做全案复审与决策建议。严格按 7 步流程：
                1. 接案：读取案件基础信息与定责结论；
                2. 材料齐全性：核对必需材料是否齐全、有效；
                3. 立案合理性：核对出险事实、险种匹配、报案时效与日期一致性；
                4. 医审准确性：核对医审核减额及依据；
                5. 理算正确性：核对理算公式、共担比例、各项额度与总额；
                6. 流程合规：核对大额上级复核阈值（医疗≥5万 / 重疾≥10万 / 意外≥3万）、脱敏合规；
                7. 综合决策：输出 准赔 / 减赔 / 挂起待查 / 拒赔 之一并附依据。

                合并规则：医审核减额优先于理算原始计算；欺诈 HIGH 覆盖医审"准赔"→挂起待查。
                合规约束：只输出建议、不替代核赔决定；每条结论标注依据；敏感信息脱敏；命中大额阈值须提示上级复核。
                """
)
public class ClaimAdjudicationAgent {

    @Tool("查询案件状态、基础信息与定责结论")
    public String getCaseStatus(@Param("案号") String caseNo) {
        ClaimCase c = ClaimCase.of(caseNo);
        return String.format("""
                {
                  "case_no": "%s",
                  "report_date": "%s",
                  "accident_date": "%s",
                  "insurance_type": "%s",
                  "policy_no": "%s",
                  "claim_amount_fen": %d,
                  "liability_conclusion": "%s"
                }
                """, c.caseNo(), c.reportDate(), c.accidentDate(), c.insuranceType(),
                c.policyNo(), c.claimAmountFen(), c.liabilityConclusion());
    }

    @Tool("查询案件材料完整性、理算书与医审核定")
    public String getCaseDocuments(@Param("案号") String caseNo) {
        ClaimCase c = ClaimCase.of(caseNo);
        return String.format("""
                {
                  "materials": {
                    "required": "%s",
                    "provided": "%s",
                    "missing": "%s",
                    "complete": %b
                  },
                  "calculation": {
                    "claim_amount_fen": %d,
                    "approved_amount_fen": %d,
                    "calculated_amount_fen": %d,
                    "medical_reduction_fen": %d,
                    "note": "%s"
                  },
                  "medical_review": {
                    "reduction_fen": %d,
                    "reason": "%s"
                  }
                }
                """, c.requiredMaterials(), c.providedMaterials(), c.missingMaterials(), c.materialsComplete(),
                c.claimAmountFen(), c.approvedAmountFen(), c.calculatedAmountFen(),
                c.medicalReductionFen(), c.calculationNote(),
                c.medicalReductionFen(), c.medicalReductionReason());
    }

    @Tool("评估案件欺诈风险分与指标")
    public String scoreFraudRisk(@Param("案号") String caseNo) {
        ClaimCase c = ClaimCase.of(caseNo);
        return String.format("""
                {
                  "case_no": "%s",
                  "score": %d,
                  "level": "%s",
                  "indicators": "%s"
                }
                """, c.caseNo(), c.fraudScore(), c.fraudLevel(), c.fraudIndicators());
    }
}
