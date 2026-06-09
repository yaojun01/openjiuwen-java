package com.openjiuwen.examples.wealth;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 金融财富助手 Deep Agent — 主控 Agent，编排 3 个子 Agent 协作。
 *
 * 核心展示：
 * 1. META 自主度：主 Agent 动态调度子 Agent 并行工作
 * 2. 多维度信息聚合：持仓 + 市场 + 风险 → 综合财富建议
 * 3. 真实金融场景：客户咨询 → 多维度分析 → 个性化建议
 *
 * 编排流程：
 *   客户咨询 → 主 Agent
 *     ├→ 调用 portfolio-analysis-agent（持仓分析：查持仓 + 资产配置 + 收益率）
 *     ├→ 调用 market-research-agent（市场研究：查趋势 + 个股 + 行业景气）
 *     └→ 调用 risk-assessment-agent（风险评估：风险承受力 + VaR + 压力测试）
 *   → 汇总三方结果 → 生成个性化财富建议
 */
@Agent(
    name = "wealth-deep-agent",
    description = "金融财富助手 Deep Agent — 编排持仓分析、市场研究、风险评估子 Agent",
    autonomyLevel = AutonomyLevel.META,
    systemPrompt = """
        你是资深金融财富顾问 Agent。收到客户咨询后，你需要：

        1. 并行启动三个分析维度：
           - 调用持仓分析 Agent：查询客户当前持仓、资产配置比例、组合收益率
           - 调用市场研究 Agent：查询市场整体趋势、持仓个股详情、相关行业景气度
           - 调用风险评估 Agent：评估客户风险承受力、计算组合 VaR、执行压力测试
        2. 等待三个维度分析完成后，综合判断：
           - 根据客户风险偏好和持仓现状，给出资产配置调整建议
           - 指出当前持仓的风险点和优化空间
           - 结合市场趋势和行业景气度，给出具体操作建议
        3. 生成一份完整的财富分析报告，包含：
           - 当前持仓诊断
           - 市场环境评估
           - 风险状况分析
           - 个性化投资建议（含具体操作方向）

        你拥有调度其他 Agent 的能力，可以并行处理多维度分析。
        所有建议仅供参考，不构成投资建议。
        """
)
public class WealthDeepAgent {

    @Tool("调用持仓分析子 Agent，查询客户持仓详情")
    public String analyzePortfolio(
            @Param("客户ID") String clientId,
            @Param("分析周期，如 1M/3M/6M/1Y") String period) {
        // 实际由 Runtime 路由到 portfolio-analysis-agent
        return String.format("""
            {
              "subAgent": "portfolio-analysis-agent",
              "clientId": "%s",
              "totalAssets": 1250000.00,
              "allocation": {"equity": 0.454, "bond": 0.408, "cash": 0.138},
              "periodReturn": 0.034,
              "sharpeRatio": 0.82,
              "summary": "组合整体收益跑赢沪深300基准，但股票仓位偏高"
            }
            """, clientId);
    }

    @Tool("调用市场研究子 Agent，查询市场环境和个股信息")
    public String researchMarket(
            @Param("市场类型，如 A股/港股") String market,
            @Param("关注的股票代码列表，逗号分隔") String symbols) {
        // 实际由 Runtime 路由到 market-research-agent
        return String.format("""
            {
              "subAgent": "market-research-agent",
              "market": "%s",
              "trend": "震荡上行",
              "stocks": [
                {"symbol": "600519.SH", "rating": "增持", "targetPrice": 2050},
                {"symbol": "000858.SZ", "rating": "中性", "targetPrice": 160}
              ],
              "summary": "市场整体偏多，白酒行业景气度上行"
            }
            """, market);
    }

    @Tool("调用风险评估子 Agent，评估组合风险状况")
    public String assessRisk(
            @Param("客户ID") String clientId,
            @Param("压力测试场景") String stressScenario) {
        // 实际由 Runtime 路由到 risk-assessment-agent
        return String.format("""
            {
              "subAgent": "risk-assessment-agent",
              "clientId": "%s",
              "riskTolerance": "稳健型",
              "var95": -45000,
              "stressScenario": "%s",
              "stressLoss": -125000,
              "summary": "组合风险在可承受范围内，压力测试下最大亏损约10%%"
            }
            """, clientId, stressScenario);
    }

    @Tool("汇总三维度分析结果，生成财富建议报告")
    public String generateWealthAdvice(
            @Param("持仓分析结果") String portfolioResult,
            @Param("市场研究结果") String marketResult,
            @Param("风险评估结果") String riskResult) {
        boolean equityHigh = portfolioResult.contains("equity") || portfolioResult.contains("偏高");
        boolean marketBull = marketResult.contains("上行") || marketResult.contains("偏多");
        boolean riskAcceptable = riskResult.contains("可承受") || riskResult.contains("正常");

        StringBuilder advice = new StringBuilder();
        advice.append("=== 财富分析综合报告 ===\n\n");

        advice.append("【持仓诊断】\n");
        if (equityHigh) {
            advice.append("- 股票仓位偏高（约45%），超出稳健型建议的40%上限\n");
            advice.append("- 建议：适度降低白酒板块集中度，分散至科技和医药\n");
        }
        advice.append("- 现金比例13.8%偏高，可考虑配置货币基金或短债提升收益\n\n");

        advice.append("【市场评估】\n");
        if (marketBull) {
            advice.append("- 市场震荡上行，短期可维持现有股票仓位\n");
            advice.append("- 白酒行业景气度上行，但估值偏高需注意回调风险\n");
        }
        advice.append("- 建议关注：半导体、新能源等政策支持板块\n\n");

        advice.append("【风险分析】\n");
        advice.append("- VaR(95%, 1天): -45,000元，在可承受范围内\n");
        advice.append("- 压力测试（股灾场景）：最大亏损约-125,000元（-10%）\n");
        advice.append("- 恢复预估：6-9个月\n\n");

        advice.append("【综合建议】\n");
        if (equityHigh && riskAcceptable) {
            advice.append("1. 股票仓位从45%降至38-40%，减仓白酒、加仓科技\n");
            advice.append("2. 债券仓位维持40%，增加信用债配置\n");
            advice.append("3. 现金从13.8%降至8%，配置短债基金\n");
            advice.append("4. 设置止损线：单笔亏损不超过2%，组合回撤不超过8%\n");
        }
        advice.append("\n* 以上建议仅供参考，不构成投资建议。请咨询持牌理财顾问。");

        return advice.toString();
    }
}
