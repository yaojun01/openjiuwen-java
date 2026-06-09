package com.openjiuwen.examples.pev;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 示例 2：PEV 多步骤 Agent — 订单退款处理。
 *
 * 展示 Alpha PEV 策略（Plan-Execute-Verify）的使用：
 * 1. Plan：分析退款请求，制定退款步骤（查询订单 → 校验规则 → 执行退款）
 * 2. Execute：按计划逐步执行，每步调用对应工具
 * 3. Verify：验证退款结果（金额一致、状态正确）
 *
 * 关键：使用 ASSISTED 自主度，规划阶段需要人工审批
 */
@Agent(
    name = "order-refund-agent",
    description = "订单退款 Agent — PEV 策略驱动，自动规划退款流程并验证结果",
    autonomyLevel = AutonomyLevel.ASSISTED,
    systemPrompt = """
        你是订单退款处理 Agent。用户会提供退款请求，你需要：
        1. 先查询订单详情（订单号、金额、状态）
        2. 校验退款规则（是否在退款期限内、商品是否支持退款）
        3. 执行退款（调用退款接口）
        4. 验证退款结果（确认金额和状态）

        严格按照 Plan → Execute → Verify 流程执行。
        如果任何步骤失败，回滚已执行的操作。
        """
)
public class OrderRefundAgent {

    @Tool("根据订单号查询订单详情")
    public String queryOrder(@Param("订单号") String orderId) {
        return String.format("""
            {
              "orderId": "%s",
              "amount": 299.00,
              "status": "DELIVERED",
              "items": ["无线耳机 x1"],
              "orderDate": "2026-06-01",
              "deliveryDate": "2026-06-05"
            }
            """, orderId);
    }

    @Tool("校验订单是否满足退款条件")
    public String validateRefund(
            @Param("订单号") String orderId,
            @Param("退款原因") String reason) {
        // 模拟退款规则校验
        boolean withinDeadline = true; // 7天内可退
        boolean itemRefundable = true;
        if (withinDeadline && itemRefundable) {
            return "校验通过：订单在退款期限内，商品支持退款。可退款金额：299.00 元";
        }
        return "校验失败：超出退款期限或商品不支持退款";
    }

    @Tool("执行退款操作")
    public String processRefund(
            @Param("订单号") String orderId,
            @Param("退款金额") double amount) {
        return String.format("""
            {
              "refundId": "REF-%s",
              "orderId": "%s",
              "amount": %.2f,
              "status": "PROCESSING",
              "estimatedArrival": "1-3个工作日"
            }
            """, orderId.substring(0, 4), orderId, amount);
    }

    @Tool("查询退款状态，验证退款是否成功")
    public String checkRefundStatus(@Param("退款单号") String refundId) {
        return String.format("""
            {
              "refundId": "%s",
              "status": "SUCCESS",
              "actualAmount": 299.00,
              "completedAt": "2026-06-08T15:30:00Z"
            }
            """, refundId);
    }
}
