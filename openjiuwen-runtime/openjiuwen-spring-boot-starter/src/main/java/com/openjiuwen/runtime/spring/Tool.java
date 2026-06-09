package com.openjiuwen.runtime.spring;

import java.lang.annotation.*;

/**
 * 声明一个工具方法。
 *
 * 加在 @Agent 类的方法上，Runtime 会自动将其注册为可调用的工具。
 * 方法签名自动解析为工具参数定义。
 *
 * <pre>
 * {@code @Agent(name = "order-refund")}
 * public class OrderRefundAgent {
 *     {@code @Tool("查询订单状态")}
 *     public OrderStatus checkOrder(String orderId) { ... }
 *
 *     {@code @Tool("处理退款")}
 *     public RefundResult processRefund(
 *         String orderId,
 *         {@code @Param("退款金额")} BigDecimal amount
 *     ) { ... }
 * }
 * </pre>
 *
 * 约束：
 * - @Tool 方法必须定义在 @Agent 类内部
 * - @Tool 方法必须是 public 实例方法
 * - 返回值会被序列化为 JSON 作为工具结果
 * - 方法参数通过 @Param 注解添加描述，未注解的参数使用参数名
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /**
     * 工具描述。
     * LLM 会根据此描述决定是否调用该工具。
     */
    String value() default "";
}
