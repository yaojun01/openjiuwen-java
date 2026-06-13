package com.openjiuwen.examples.claims;

/**
 * 保险理赔案件合成数据 fixture（核赔全案复审示例 #7）。
 *
 * <p>对齐点金 DianJin-SKILLS 的 insurance-claim-adjudication-review：
 * 全案数据 6 类——案件基础信息 / 材料完整性 / 理算书 / 医审核定 / 欺诈评分 / 定责结论。
 *
 * <p>单位约定：金额=分（long），日期=ISO8601（String），字段=snake_case。
 * 数据为合成 fixture（无真实 PII）；3 个工厂用例分别驱动 准赔 / 减赔 / 挂起 决策。
 */
public record ClaimCase(
        String caseNo,
        String reportDate,
        String accidentDate,
        String insuranceType,
        String policyNo,
        long claimAmountFen,
        long approvedAmountFen,
        long calculatedAmountFen,
        long medicalReductionFen,
        String medicalReductionReason,
        String calculationNote,
        int fraudScore,
        String fraudLevel,
        String fraudIndicators,
        String requiredMaterials,
        String providedMaterials,
        String missingMaterials,
        boolean materialsComplete,
        String liabilityConclusion
) {

    /** 按案号查找合成用例。 */
    public static ClaimCase of(String caseNo) {
        return switch (caseNo) {
            case "CLM-2026-APPROVE" -> standardApprove();
            case "CLM-2026-REDUCE" -> underpaymentCorrection();
            case "CLM-2026-HOLD" -> fraudHold();
            default -> throw new IllegalArgumentException("未知案号: " + caseNo);
        };
    }

    /** 用例①：标准准赔——材料齐全、理算正确、欺诈低，单车意外全责（意外险 2 万，未达 3 万上级复核阈值）。 */
    public static ClaimCase standardApprove() {
        return new ClaimCase(
                "CLM-2026-APPROVE",
                "2026-05-11", "2026-05-10", "意外", "POL-2026-0001",
                2_000_000L, 2_000_000L, 2_000_000L,   // 申请=核定=理算，20000 元一致
                0L, "无核减",
                "意外医疗全额赔付，免赔额已扣除",
                15, "LOW", "无明显异常",
                "理赔申请书,身份证,诊断证明,医疗发票,病历,银行账户",
                "理赔申请书,身份证,诊断证明,医疗发票,病历,银行账户",
                "", true,
                "单车事故，被保人全责"
        );
    }

    /** 用例②：减赔——理算比例 85% 误用 100%，计算额偏高，需减赔；医疗 5 万命中上级复核阈值。 */
    public static ClaimCase underpaymentCorrection() {
        return new ClaimCase(
                "CLM-2026-REDUCE",
                "2026-04-20", "2026-04-15", "医疗", "POL-2026-0002",
                5_000_000L, 4_250_000L, 5_000_000L,   // 申请 50000；正确核定 42500(85%)；理算误算 50000(100%)
                0L, "无核减",
                "误用 100% 全额，应按 85% 共担比例，正确核定 42500 元（原误算 50000 元）",
                20, "LOW", "无明显异常",
                "理赔申请书,身份证,诊断证明,医疗发票,病历,银行账户",
                "理赔申请书,身份证,诊断证明,医疗发票,病历,银行账户",
                "", true,
                "医疗费用理赔，被保人部分责任"
        );
    }

    /** 用例③：挂起待查——欺诈 85 分 HIGH + 报案早于出险（日期异常），覆盖医审结论。 */
    public static ClaimCase fraudHold() {
        return new ClaimCase(
                "CLM-2026-HOLD",
                "2026-03-10", "2026-03-15", "重疾", "POL-2026-0003",   // 报案日 03-10 早于出险日 03-15 = 异常
                8_000_000L, 8_000_000L, 8_000_000L,   // 医审/理算本身无误，但欺诈覆盖
                0L, "无核减",
                "重疾确诊赔付，金额无误",
                85, "HIGH", "报案日期早于出险日期；高保额投保后短期出险；就诊医院非网络内",
                "理赔申请书,身份证,诊断证明,病理报告,银行账户",
                "理赔申请书,身份证,诊断证明,病理报告,银行账户",
                "", true,
                "重疾理赔，待欺诈排查"
        );
    }
}
