package com.openjiuwen.runtime.spring;

import com.openjiuwen.core.dispatch.AutonomyLevel;

import java.lang.annotation.*;

/**
 * 声明一个 Agent 类。
 *
 * 用法与 @Service 完全一致——加在类上即可被 Runtime 自动识别。
 * Runtime 会自动：
 * 1. 扫描类中所有 @Tool 方法，注册到 AgentKernel
 * 2. 构建 AgentDefinition 注册到 AgentRegistry
 * 3. 根据 autonomyLevel 路由到对应策略
 *
 * <pre>
 * {@code @Agent(name = "order-refund", autonomyLevel = AutonomyLevel.ASSISTED)}
 * public class OrderRefundAgent {
 *     {@code @Tool("查询订单状态")}
 *     public OrderStatus checkOrder(String orderId) { ... }
 *
 *     {@code @Tool("处理退款")
 *     public RefundResult processRefund(String orderId, BigDecimal amount) { ... }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Agent {

    /**
     * Agent 名称（全局唯一）。
     * 用于 AgentClient.invoke(name, ...) 和日志追踪。
     */
    String name();

    /**
     * Agent 描述。
     * 用于自动生成系统提示和 AgentRegistry 展示。
     */
    String description() default "";

    /**
     * 自主度级别。
     * 决定路由到 Alpha（GUIDED/ASSISTED）还是 Beta（META/AUTONOMOUS）策略。
     * 默认 GUIDED——最安全的模式。
     */
    AutonomyLevel autonomyLevel() default AutonomyLevel.GUIDED;

    /**
     * 使用的 LLM 模型（可选，覆盖全局默认）。
     */
    String model() default "";

    /**
     * 系统提示模板（可选）。
     * 如果不提供，Runtime 会根据 description 和 @Tool 列表自动生成。
     */
    String systemPrompt() default "";
}
