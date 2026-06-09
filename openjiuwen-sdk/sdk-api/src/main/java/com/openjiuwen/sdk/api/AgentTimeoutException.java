package com.openjiuwen.sdk.api;

/**
 * Thrown when an agent invocation exceeds the configured timeout.
 */
public class AgentTimeoutException extends AgentException {

    private final long timeoutMs;

    public AgentTimeoutException(String agentName, long timeoutMs) {
        super(agentName, "Agent '" + agentName + "' timed out after " + timeoutMs + "ms");
        this.timeoutMs = timeoutMs;
    }

    public AgentTimeoutException(String agentName, long timeoutMs, Throwable cause) {
        super(agentName, "Agent '" + agentName + "' timed out after " + timeoutMs + "ms", cause);
        this.timeoutMs = timeoutMs;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
