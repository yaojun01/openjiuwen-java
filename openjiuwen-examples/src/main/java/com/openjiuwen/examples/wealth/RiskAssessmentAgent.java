package com.openjiuwen.examples.wealth;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 子 Agent 3：风险评估 Agent。
 *
 * 评估客户风险承受能力，计算组合 VaR，执行压力测试。
 * ASSISTED 级别：高风险预警需要人工确认。
 */
@Agent(
    name = "risk-assessment-agent",
    description = "风险评估 Agent — 评估风险承受力、计算 VaR、压力测试",
    autonomyLevel = AutonomyLevel.ASSISTED,
    systemPrompt = "你是风险评估助手。根据客户信息和持仓数据评估风险等级，" +
                   "计算 VaR（在险价值），执行压力测试场景分析。" +
                   "高风险预警需要提醒人工复核。"
)
public class RiskAssessmentAgent {

    @Tool("评估客户风险承受能力")
    public String assessRiskTolerance(@Param("客户ID") String clientId) {
        return String.format("""
            {
              "clientId": "%s",
              "riskTolerance": "稳健型",
              "score": 65,
              "maxDrawdownTolerance": -0.10,
              "investmentHorizon": "3-5年",
              "factors": {
                "age": "35岁",
                "income": "年薪50万",
                "experience": "5年投资经验",
                "dependency": "无赡养负担"
              }
            }
            """, clientId);
    }

    @Tool("计算组合在险价值（VaR）")
    public String calculateVaR(
            @Param("客户ID") String clientId,
            @Param("置信度，如 95%%/99%%") String confidence,
            @Param("持有期天数") int holdingDays) {
        double var95 = -45000;
        double var99 = -72000;
        return String.format("""
            {
              "clientId": "%s",
              "confidence": "%s",
              "holdingDays": %d,
              "var": %.2f,
              "cvar": %.2f,
              "interpretation": "在%d天持有期内，损失超过%.0f元的概率不超过%s",
              "status": "正常"
            }
            """, clientId, confidence, holdingDays,
            confidence.equals("99%%") ? var99 : var95,
            confidence.equals("99%%") ? var99 * 1.3 : var95 * 1.3,
            holdingDays,
            confidence.equals("99%%") ? var99 : var95,
            confidence);
    }

    @Tool("执行压力测试场景分析")
    public String stressTest(
            @Param("客户ID") String clientId,
            @Param("测试场景，如 股灾/利率上行/黑天鹅") String scenario) {
        return String.format("""
            {
              "clientId": "%s",
              "scenario": "%s",
              "portfolioImpact": {
                "totalLoss": -125000,
                "lossRate": -0.10,
                "worstAsset": "贵州茅台",
                "worstLoss": -42500,
                "safeAsset": "易方达信用债A",
                "safeLoss": -5000
              },
              "recoveryTime": "预计6-9个月恢复",
              "recommendation": "建议增加债券和对冲配置，降低股票集中度"
            }
            """, clientId, scenario);
    }
}
