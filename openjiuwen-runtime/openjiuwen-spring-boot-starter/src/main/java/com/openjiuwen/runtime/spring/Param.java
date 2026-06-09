package com.openjiuwen.runtime.spring;

import java.lang.annotation.*;

/**
 * 工具参数描述。
 *
 * 加在 @Tool 方法的参数上，提供参数描述信息。
 * 如果不加，Runtime 会使用参数名和类型自动推断。
 *
 * <pre>
 * {@code @Tool("处理退款")}
 * public RefundResult processRefund(
 *     String orderId,
 *     {@code @Param("退款金额")} BigDecimal amount
 * ) { ... }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Param {

    /**
     * 参数描述。
     */
    String value() default "";

    /**
     * 是否必填。默认 true。
     */
    boolean required() default true;
}
