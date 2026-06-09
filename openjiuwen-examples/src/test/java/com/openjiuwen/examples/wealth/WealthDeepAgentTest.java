package com.openjiuwen.examples.wealth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 金融财富助手 Deep Agent 测试。
 *
 * 测试策略：直接调用 @Tool 方法验证业务逻辑正确性。
 * 不依赖 Spring 上下文和真实 LLM，纯 Java 单元测试。
 */
@DisplayName("金融财富助手 Deep Agent")
class WealthDeepAgentTest {

    // ==================== 子 Agent 1：持仓分析 ====================

    @Nested
    @DisplayName("PortfolioAnalysisAgent: 持仓分析")
    class PortfolioAnalysisTest {

        PortfolioAnalysisAgent agent = new PortfolioAnalysisAgent();

        @Test
        @DisplayName("查询持仓返回完整持仓明细")
        void queryPortfolio_returnsCompletePositions() {
            String result = agent.queryPortfolio("CLT-20240001");

            assertTrue(result.contains("CLT-20240001"), "应包含客户ID");
            assertTrue(result.contains("totalAssets"), "应包含总资产");
            assertTrue(result.contains("positions"), "应包含持仓列表");
            assertTrue(result.contains("600519.SH"), "应包含茅台持仓");
            assertTrue(result.contains("returnRate"), "应包含收益率");
        }

        @Test
        @DisplayName("分析资产配置返回配置比例")
        void analyzeAllocation_returnsRatios() {
            String result = agent.analyzeAllocation("CLT-20240001");

            assertTrue(result.contains("equity"), "应包含股票配置");
            assertTrue(result.contains("bond"), "应包含债券配置");
            assertTrue(result.contains("cash"), "应包含现金配置");
            assertTrue(result.contains("suggestion"), "应包含配置建议");
        }

        @Test
        @DisplayName("计算收益率返回关键指标")
        void calculateReturns_returnsKeyMetrics() {
            String result = agent.calculateReturns("CLT-20240001", "3M");

            assertTrue(result.contains("3M"), "应包含计算周期");
            assertTrue(result.contains("totalReturn"), "应包含总收益率");
            assertTrue(result.contains("sharpeRatio"), "应包含夏普比率");
            assertTrue(result.contains("maxDrawdown"), "应包含最大回撤");
        }
    }

    // ==================== 子 Agent 2：市场研究 ====================

    @Nested
    @DisplayName("MarketResearchAgent: 市场研究")
    class MarketResearchTest {

        MarketResearchAgent agent = new MarketResearchAgent();

        @Test
        @DisplayName("查询市场趋势返回大盘指数")
        void queryMarketTrend_returnsIndices() {
            String result = agent.queryMarketTrend("A股", "近1月");

            assertTrue(result.contains("A股"), "应包含市场类型");
            assertTrue(result.contains("上证指数"), "应包含上证指数");
            assertTrue(result.contains("sentiment"), "应包含市场情绪");
            assertTrue(result.contains("volume"), "应包含成交量");
        }

        @Test
        @DisplayName("查询贵州茅臺返回详细信息")
        void getStockInfo_maotai_returnsDetails() {
            String result = agent.getStockInfo("600519.SH");

            assertTrue(result.contains("贵州茅台"), "应包含股票名称");
            assertTrue(result.contains("pe"), "应包含市盈率");
            assertTrue(result.contains("targetPrice"), "应包含目标价");
            assertEquals("增持", extractJsonField(result, "rating"));
        }

        @Test
        @DisplayName("查询未知股票返回提示")
        void getStockInfo_unknownStock_returnsNote() {
            String result = agent.getStockInfo("999999.SZ");

            assertTrue(result.contains("暂无该股票数据"), "未知股票应有提示");
        }

        @Test
        @DisplayName("查询行业景气度返回分析结果")
        void getSectorOutlook_returnsAnalysis() {
            String result = agent.getSectorOutlook("白酒");

            assertTrue(result.contains("白酒"), "应包含行业名称");
            assertTrue(result.contains("growthRate"), "应包含增长率");
            assertTrue(result.contains("risks"), "应包含风险因素");
            assertTrue(result.contains("opportunities"), "应包含机会");
        }
    }

    // ==================== 子 Agent 3：风险评估 ====================

    @Nested
    @DisplayName("RiskAssessmentAgent: 风险评估")
    class RiskAssessmentTest {

        RiskAssessmentAgent agent = new RiskAssessmentAgent();

        @Test
        @DisplayName("评估风险承受能力返回客户画像")
        void assessRiskTolerance_returnsProfile() {
            String result = agent.assessRiskTolerance("CLT-20240001");

            assertTrue(result.contains("riskTolerance"), "应包含风险偏好");
            assertTrue(result.contains("score"), "应包含风险评分");
            assertTrue(result.contains("investmentHorizon"), "应包含投资期限");
        }

        @Test
        @DisplayName("计算 VaR 返回在险价值")
        void calculateVaR_returnsValueAtRisk() {
            String result = agent.calculateVaR("CLT-20240001", "95%", 1);

            assertTrue(result.contains("var"), "应包含 VaR 值");
            assertTrue(result.contains("cvar"), "应包含 CVaR（条件 VaR）");
            assertTrue(result.contains("interpretation"), "应包含风险解读");
        }

        @Test
        @DisplayName("压力测试返回场景分析")
        void stressTest_returnsScenarioAnalysis() {
            String result = agent.stressTest("CLT-20240001", "股灾");

            assertTrue(result.contains("portfolioImpact"), "应包含组合影响");
            assertTrue(result.contains("totalLoss"), "应包含总损失");
            assertTrue(result.contains("recoveryTime"), "应包含恢复时间");
            assertTrue(result.contains("recommendation"), "应包含建议");
        }
    }

    // ==================== 主 Agent：财富建议生成 ====================

    @Nested
    @DisplayName("WealthDeepAgent: 主控编排与建议生成")
    class WealthDeepAgentOrchestrationTest {

        WealthDeepAgent agent = new WealthDeepAgent();
        PortfolioAnalysisAgent portfolioAgent = new PortfolioAnalysisAgent();
        MarketResearchAgent marketAgent = new MarketResearchAgent();
        RiskAssessmentAgent riskAgent = new RiskAssessmentAgent();

        @Test
        @DisplayName("持仓分析子 Agent 返回有效数据")
        void portfolioSubAgent_returnsValidData() {
            String result = agent.analyzePortfolio("CLT-20240001", "3M");

            assertTrue(result.contains("portfolio-analysis-agent"), "应标注子 Agent");
            assertTrue(result.contains("totalAssets"), "应包含总资产");
            assertTrue(result.contains("sharpeRatio"), "应包含夏普比率");
        }

        @Test
        @DisplayName("市场研究子 Agent 返回有效数据")
        void marketSubAgent_returnsValidData() {
            String result = agent.researchMarket("A股", "600519.SH,000858.SZ");

            assertTrue(result.contains("market-research-agent"), "应标注子 Agent");
            assertTrue(result.contains("trend"), "应包含趋势");
            assertTrue(result.contains("stocks"), "应包含个股信息");
        }

        @Test
        @DisplayName("风险评估子 Agent 返回有效数据")
        void riskSubAgent_returnsValidData() {
            String result = agent.assessRisk("CLT-20240001", "股灾");

            assertTrue(result.contains("risk-assessment-agent"), "应标注子 Agent");
            assertTrue(result.contains("riskTolerance"), "应包含风险偏好");
            assertTrue(result.contains("var95"), "应包含 VaR");
        }

        @Test
        @DisplayName("综合三维度结果生成完整财富建议")
        void generateWealthAdvice_comprehensiveReport() {
            // 模拟完整的三维度分析流程
            String portfolioResult = portfolioAgent.queryPortfolio("CLT-20240001");
            String marketResult = marketAgent.queryMarketTrend("A股", "近1月");
            String riskResult = riskAgent.assessRiskTolerance("CLT-20240001");

            // 主 Agent 汇总生成建议
            String advice = agent.generateWealthAdvice(portfolioResult, marketResult, riskResult);

            // 验证报告完整性
            assertTrue(advice.contains("财富分析综合报告"), "应包含报告标题");
            assertTrue(advice.contains("持仓诊断"), "应包含持仓诊断");
            assertTrue(advice.contains("市场评估"), "应包含市场评估");
            assertTrue(advice.contains("风险分析"), "应包含风险分析");
            assertTrue(advice.contains("综合建议"), "应包含综合建议");
            assertTrue(advice.contains("仅供参考"), "应包含免责声明");
        }

        @Test
        @DisplayName("高股票仓位 + 震荡上行市场 → 适度减仓建议")
        void highEquity_bullMarket_suggestsReduceEquity() {
            String portfolioResult = "equity ratio 偏高 45%";
            String marketResult = "市场震荡上行，偏多";
            String riskResult = "风险在可承受范围内，正常";

            String advice = agent.generateWealthAdvice(portfolioResult, marketResult, riskResult);

            assertTrue(advice.contains("股票仓位从45%降至"), "高仓位应建议减仓");
            assertTrue(advice.contains("债券"), "应包含债券配置建议");
        }

        @Test
        @DisplayName("端到端：完整三维度分析 → 综合建议")
        void endToEnd_fullAnalysis() {
            // 1. 持仓分析
            String portfolio = portfolioAgent.queryPortfolio("CLT-20240001");
            String allocation = portfolioAgent.analyzeAllocation("CLT-20240001");
            String returns = portfolioAgent.calculateReturns("CLT-20240001", "3M");

            // 2. 市场研究
            String market = marketAgent.queryMarketTrend("A股", "近1月");
            String stock = marketAgent.getStockInfo("600519.SH");
            String sector = marketAgent.getSectorOutlook("白酒");

            // 3. 风险评估
            String riskProfile = riskAgent.assessRiskTolerance("CLT-20240001");
            String varResult = riskAgent.calculateVaR("CLT-20240001", "95%", 1);
            String stress = riskAgent.stressTest("CLT-20240001", "股灾");

            // 验证每个维度的输出都是有效 JSON
            for (String result : new String[]{
                portfolio, allocation, returns,
                market, stock, sector,
                riskProfile, varResult, stress
            }) {
                assertTrue(result.startsWith("{"), "输出应以 { 开头: " + result.substring(0, 30));
                assertTrue(result.contains(":"), "输出应包含 JSON 键值对");
            }

            // 4. 主 Agent 生成综合建议
            String advice = agent.generateWealthAdvice(portfolio, market, riskProfile);
            assertFalse(advice.isEmpty(), "综合建议不应为空");
            assertTrue(advice.length() > 100, "综合建议应有实质性内容");
        }
    }

    // ==================== 辅助方法 ====================

    /** 从简单 JSON 字符串中提取字段值（不依赖 Jackson） */
    private String extractJsonField(String json, String field) {
        // 简单实现：找 "field": "value" 模式
        String pattern = "\"" + field + "\": \"";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }
}
