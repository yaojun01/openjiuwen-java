package com.openjiuwen.sdk.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an agent-callable tool.
 * <p>
 * Annotated methods are discovered by the SDK and registered with
 * the agent runtime so the agent can call them during execution.
 * <p>
 * Requirements for annotated methods:
 * <ul>
 *   <li>Must be public</li>
 *   <li>Must return String (JSON output)</li>
 *   <li>Must accept a single String parameter (JSON input)</li>
 * </ul>
 * <p>
 * Example:
 * <pre>
 * &#64;Tool(name = "query-order", description = "Query order by ID")
 * public String queryOrder(String input) {
 *     String orderId = parseJson(input).get("orderId");
 *     Order order = orderService.findById(orderId);
 *     return toJson(order);
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /**
     * Tool name used by the agent. Must be unique within a ToolProvider.
     */
    String name();

    /**
     * Human-readable description. Used by the agent's LLM to decide
     * when and how to call this tool.
     */
    String description() default "";
}
