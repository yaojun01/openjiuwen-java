package com.openjiuwen.examples.deepagent;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 子 Agent 2：风控评估 Agent。
 *
 * 被 OrderDeepAgent 动态调用，独立评估订单风险。
 * 展示不同自主度：ASSISTED 级别，风控决策需要人工确认。
 */
@Agent(
    name = "risk-assess-agent",
    description = "风控评估 Agent — 评估订单风险等级、欺诈检测、信用检查",
    autonomyLevel = AutonomyLevel.ASSISTED,
    systemPrompt = "你是风控评估助手。根据订单信息和用户画像评估风险等级。" +
                   "高风险订单需要人工审核确认。"
)
public class RiskAssessAgent {

    @Tool("评估订单风险等级")
    public String assessRisk(
            @Param("订单号") String orderId,
            @Param("订单金额") double amount) {
        // 模拟风控规则
        String level = amount > 5000 ? "HIGH" : amount > 1000 ? "MEDIUM" : "LOW";
        return String.format("""
            {
              "orderId": "%s",
              "riskLevel": "%s",
              "score": %d,
              "factors": ["金额评估", "用户历史", "设备指纹"],
              "recommendation": "%s"
            }
            """, orderId, level,
            level.equals("HIGH") ? 78 : level.equals("MEDIUM") ? 45 : 12,
            level.equals("HIGH") ? "建议人工审核" : "自动通过");
    }

    @Tool("查询用户信用评分")
    public String checkCredit(@Param("用户ID") String userId) {
        return String.format("""
            {
              "userId": "%s",
              "creditScore": 720,
              "level": "GOOD",
              "orderCount": 15,
              "refundRate": 0.03
            }
            """, userId);
    }
}
