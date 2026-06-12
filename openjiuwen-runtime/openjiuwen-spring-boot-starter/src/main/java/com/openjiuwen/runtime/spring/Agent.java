package com.openjiuwen.runtime.spring;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import org.springframework.stereotype.Component;

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
@Component   // 元注解 @Component：让 @Agent 类被 Spring 组件扫描识别为 Bean。文档承诺
             // 「用法与 @Service 完全一致」，但此前漏了 @Component，导致 @Agent 类不被实例化、
             // AgentBeanPostProcessor 无法处理 → @Tool 永不注册、AgentRegistry 为空。Alpha 纯 LLM
             // 路径不依赖 AgentDefinition 故能跑通，而 TOOL_CALL 路径会全部失败。
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
