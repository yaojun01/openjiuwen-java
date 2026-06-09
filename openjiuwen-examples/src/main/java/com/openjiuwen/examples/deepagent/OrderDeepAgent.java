package com.openjiuwen.examples.deepagent;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 示例 3：Deep Agent — 订单处理主 Agent（编排子 Agent 协作）。
 *
 * 核心展示：
 * 1. 主 Agent 使用 META 自主度，可以动态生成/调度子 Agent
 * 2. @Tool 方法中通过 AgentClient 调用其他 Agent（子 Agent 编排）
 * 3. 多 Agent 并行执行 + 结果聚合
 *
 * 编排流程：
 *   用户下单请求 → 主 Agent
 *     ├→ 并行调用 inventory-check-agent（库存检查）
 *     └→ 并行调用 risk-assess-agent（风控评估）
 *   → 汇总结果 → 决策（通过/拒绝/需人工审核）
 */
@Agent(
    name = "order-deep-agent",
    description = "订单处理 Deep Agent — 编排库存检查和风控评估子 Agent",
    autonomyLevel = AutonomyLevel.META,
    systemPrompt = """
        你是订单处理的主控 Agent。收到订单请求后，你需要：

        1. 并行启动两个子任务：
           - 调用库存检查 Agent 确认商品是否有库存
           - 调用风控评估 Agent 评估订单风险等级
        2. 等待两个子任务完成后，综合判断：
           - 库存充足 + 风险低 → 自动通过，锁定库存
           - 库存不足 → 告知缺货
           - 风险高 → 转人工审核
        3. 给出最终处理结果

        你拥有调度其他 Agent 的能力，可以并行处理子任务。
        """
)
public class OrderDeepAgent {

    /**
     * 调用库存检查子 Agent。
     * Runtime 会将此 Tool 的调用路由到 inventory-check-agent。
     */
    @Tool("检查商品库存，调用库存检查子 Agent")
    public String checkInventory(
            @Param("商品SKU列表，逗号分隔") String skus,
            @Param("所需数量") int quantity) {
        // 实际由 Runtime 路由到 inventory-check-agent
        // 这里返回模拟结果，真实场景由 AgentKernel.invoke() 执行
        return String.format("""
            {
              "subAgent": "inventory-check-agent",
              "result": "所有商品库存充足",
              "details": [{"sku": "%s", "available": %d, "sufficient": true}],
              "lockId": "LOCK-BATCH-001"
            }
            """, skus.split(",")[0], quantity);
    }

    /**
     * 调用风控评估子 Agent。
     */
    @Tool("评估订单风控，调用风控评估子 Agent")
    public String assessRisk(
            @Param("订单号") String orderId,
            @Param("订单金额") double amount,
            @Param("用户ID") String userId) {
        return String.format("""
            {
              "subAgent": "risk-assess-agent",
              "result": "风险评估完成",
              "riskLevel": "%s",
              "score": %d,
              "recommendation": "自动通过"
            }
            """, amount > 5000 ? "MEDIUM" : "LOW",
            amount > 5000 ? 45 : 12);
    }

    /**
     * 汇总子 Agent 结果，做最终决策。
     */
    @Tool("综合库存和风控结果，做出订单决策")
    public String makeDecision(
            @Param("库存检查结果") String inventoryResult,
            @Param("风控评估结果") String riskResult) {
        // 主 Agent 的决策逻辑
        boolean inStock = inventoryResult.contains("充足");
        boolean lowRisk = riskResult.contains("LOW");

        if (inStock && lowRisk) {
            return "决策：自动通过。库存充足，风险低。已锁定库存，订单进入履约流程。";
        } else if (!inStock) {
            return "决策：拒绝。库存不足，建议补货后重试。";
        } else {
            return "决策：转人工审核。风险较高，需要风控团队确认。";
        }
    }
}
