package com.openjiuwen.examples.wealth;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 子 Agent 1：持仓分析 Agent。
 *
 * 查询客户当前持仓，分析资产配置和收益率。
 * ASSISTED 级别：持仓变动建议需要人工确认。
 */
@Agent(
    name = "portfolio-analysis-agent",
    description = "持仓分析 Agent — 查询持仓、分析资产配置、计算收益率",
    autonomyLevel = AutonomyLevel.ASSISTED,
    systemPrompt = "你是专业的持仓分析助手。根据客户信息查询当前持仓，" +
                   "分析资产配置比例，计算各资产的收益率。所有金额单位为人民币元。"
)
public class PortfolioAnalysisAgent {

    @Tool("查询客户当前持仓明细")
    public String queryPortfolio(@Param("客户ID") String clientId) {
        return String.format("""
            {
              "clientId": "%s",
              "totalAssets": 1250000.00,
              "positions": [
                {"symbol": "600519.SH", "name": "贵州茅台", "shares": 100, "cost": 180000, "marketValue": 185000, "returnRate": 0.028},
                {"symbol": "000858.SZ", "name": "五粮液", "shares": 500, "cost": 75000, "marketValue": 72000, "returnRate": -0.04},
                {"symbol": "510300.SH", "name": "沪深300ETF", "shares": 5000, "cost": 220000, "marketValue": 228000, "returnRate": 0.036},
                {"symbol": "0700.HK", "name": "腾讯控股", "shares": 200, "cost": 76000, "marketValue": 82000, "returnRate": 0.079},
                {"symbol": "BOND-FUND", "name": "易方达信用债A", "shares": 50000, "cost": 500000, "marketValue": 510000, "returnRate": 0.02}
              ],
              "cashBalance": 173000.00
            }
            """, clientId);
    }

    @Tool("分析资产配置比例")
    public String analyzeAllocation(@Param("客户ID") String clientId) {
        return """
            {
              "equity": {"ratio": 0.454, "value": 567000, "status": "偏高"},
              "bond":   {"ratio": 0.408, "value": 510000, "status": "正常"},
              "cash":   {"ratio": 0.138, "value": 173000, "status": "偏高"},
              "suggestion": "股票仓位偏高，建议适度减仓至40%%以下，增加债券配置"
            }
            """;
    }

    @Tool("计算组合整体收益率")
    public String calculateReturns(
            @Param("客户ID") String clientId,
            @Param("计算周期，如 1M/3M/6M/1Y") String period) {
        return String.format("""
            {
              "clientId": "%s",
              "period": "%s",
              "totalReturn": 0.034,
              "annualizedReturn": 0.041,
              "maxDrawdown": -0.068,
              "sharpeRatio": 0.82,
              "benchmark": {"index": "沪深300", "return": 0.021, "alpha": 0.013}
            }
            """, clientId, period);
    }
}
