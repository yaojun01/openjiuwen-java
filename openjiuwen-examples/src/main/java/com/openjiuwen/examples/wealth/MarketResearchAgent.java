package com.openjiuwen.examples.wealth;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 子 Agent 2：市场研究 Agent。
 *
 * 查询市场趋势、个股信息、行业景气度。
 * GUIDED 级别：只做信息查询，不做投资决策。
 */
@Agent(
    name = "market-research-agent",
    description = "市场研究 Agent — 查询市场趋势、个股信息、行业景气度",
    autonomyLevel = AutonomyLevel.GUIDED,
    systemPrompt = "你是市场研究助手。根据用户请求查询市场数据、行业趋势和个股信息。" +
                   "你只提供客观的市场数据和分析，不直接给出买卖建议。"
)
public class MarketResearchAgent {

    @Tool("查询市场整体趋势")
    public String queryMarketTrend(
            @Param("市场类型，如 A股/港股/美股") String market,
            @Param("时间范围，如 近1周/近1月/近3月") String timeframe) {
        return String.format("""
            {
              "market": "%s",
              "timeframe": "%s",
              "trend": "震荡上行",
              "majorIndices": [
                {"name": "上证指数", "value": 3345.67, "change": 0.012},
                {"name": "深证成指", "value": 10892.34, "change": 0.018},
                {"name": "创业板指", "value": 2156.78, "change": 0.025}
              ],
              "sentiment": "偏多",
              "volume": "两市成交额 1.12 万亿，较前一交易日放量"
            }
            """, market, timeframe);
    }

    @Tool("查询个股详细信息")
    public String getStockInfo(@Param("股票代码，如 600519.SH") String symbol) {
        return switch (symbol) {
            case "600519.SH" -> """
                {
                  "symbol": "600519.SH",
                  "name": "贵州茅台",
                  "price": 1850.00,
                  "change": 0.015,
                  "pe": 28.5,
                  "pb": 9.2,
                  "marketCap": "2.32万亿",
                  "sector": "白酒",
                  "rating": "增持",
                  "targetPrice": 2050.00
                }
                """;
            case "000858.SZ" -> """
                {
                  "symbol": "000858.SZ",
                  "name": "五粮液",
                  "price": 144.00,
                  "change": -0.008,
                  "pe": 22.1,
                  "pb": 5.8,
                  "marketCap": "5580亿",
                  "sector": "白酒",
                  "rating": "中性",
                  "targetPrice": 160.00
                }
                """;
            default -> String.format("""
                {
                  "symbol": "%s",
                  "name": "查询的股票",
                  "price": 0,
                  "change": 0,
                  "note": "暂无该股票数据"
                }
                """, symbol);
        };
    }

    @Tool("查询行业景气度指数")
    public String getSectorOutlook(@Param("行业名称，如 白酒/新能源/半导体") String sector) {
        return String.format("""
            {
              "sector": "%s",
              "outlook": "景气度上行",
              "growthRate": 0.12,
              "policy": "政策支持",
              "risks": ["估值偏高", "竞争加剧"],
              "opportunities": ["消费升级", "渠道下沉"]
            }
            """, sector);
    }
}
