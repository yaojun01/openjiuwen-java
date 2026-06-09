package com.openjiuwen.sdk.api;

import com.openjiuwen.sdk.api.config.Budget;
import com.openjiuwen.sdk.api.model.AgentResult;

/**
 * Enterprise developer entry point for invoking Openjiuwen agents.
 * <p>
 * All methods are blocking unless explicitly named with {@code Async}.
 * Thread-safe: a single instance can be shared across threads.
 * <p>
 * Usage:
 * <pre>
 * AgentClient client = AgentClientBuilder.remote("http://runtime:8080")
 *     .withTimeout(30, TimeUnit.SECONDS)
 *     .withRetry(3)
 *     .build();
 *
 * AgentResult result = client.invoke("order-refund-agent", "process refund for order 12345");
 * </pre>
 */
public interface AgentClient {

    /**
     * Synchronously invoke an agent and wait for its final result.
     *
     * @param agentName registered agent name in the runtime
     * @param input     natural language input for the agent
     * @return execution result, never null
     * @throws AgentException       if the agent execution fails
     * @throws AgentTimeoutException if execution exceeds configured timeout
     */
    AgentResult invoke(String agentName, String input);

    /**
     * Asynchronously invoke an agent, delivering events to the handler.
     * <p>
     * This method returns immediately. The handler receives streaming events
     * (thinking steps, tool calls, partial output) and the final result.
     *
     * @param agentName registered agent name in the runtime
     * @param input     natural language input for the agent
     * @param handler   callback for async events, must not be null
     * @throws AgentException if the request cannot be initiated
     */
    void invokeAsync(String agentName, String input, AgentEventHandler handler);

    /**
     * Invoke an agent with explicit budget constraints (token limit, cost cap, etc.).
     *
     * @param agentName registered agent name in the runtime
     * @param input     natural language input for the agent
     * @param budget    budget constraints, must not be null
     * @return execution result, never null
     * @throws AgentException       if the agent execution fails
     * @throws AgentTimeoutException if execution exceeds configured timeout
     */
    AgentResult invokeWithBudget(String agentName, String input, Budget budget);
}
