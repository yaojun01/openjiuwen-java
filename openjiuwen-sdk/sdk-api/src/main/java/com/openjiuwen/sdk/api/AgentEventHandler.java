package com.openjiuwen.sdk.api;

import com.openjiuwen.sdk.api.model.AgentResult;

/**
 * Callback interface for receiving asynchronous agent execution events.
 * <p>
 * Implementations must be thread-safe. The SDK calls these methods from
 * an I/O thread, so avoid blocking operations inside handlers.
 */
public interface AgentEventHandler {

    /**
     * Called when the agent starts processing.
     *
     * @param agentName the agent that started
     * @param sessionId unique session identifier for this invocation
     */
    void onStart(String agentName, String sessionId);

    /**
     * Called when the agent produces a thinking/reasoning step.
     *
     * @param content the thinking content (may be partial)
     */
    void onThinking(String content);

    /**
     * Called when the agent invokes a tool.
     *
     * @param toolName name of the tool being called
     * @param input    tool input as JSON string
     */
    void onToolCall(String toolName, String input);

    /**
     * Called when a tool returns its result.
     *
     * @param toolName name of the tool that completed
     * @param output   tool output as JSON string
     */
    void onToolResult(String toolName, String output);

    /**
     * Called when the agent produces partial output (streaming).
     *
     * @param partialOutput incremental output text
     */
    void onPartialOutput(String partialOutput);

    /**
     * Called when the agent completes successfully with a final result.
     *
     * @param result the final agent result
     */
    void onComplete(AgentResult result);

    /**
     * Called when the agent execution fails.
     *
     * @param error exception describing the failure
     */
    void onError(AgentException error);
}
