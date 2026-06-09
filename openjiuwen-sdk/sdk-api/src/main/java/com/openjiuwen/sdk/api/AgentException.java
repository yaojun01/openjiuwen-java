package com.openjiuwen.sdk.api;

/**
 * Base exception for all SDK errors.
 */
public class AgentException extends RuntimeException {

    private final String agentName;

    public AgentException(String message) {
        super(message);
        this.agentName = null;
    }

    public AgentException(String agentName, String message) {
        super(message);
        this.agentName = agentName;
    }

    public AgentException(String agentName, String message, Throwable cause) {
        super(message, cause);
        this.agentName = agentName;
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
        this.agentName = null;
    }

    public String getAgentName() {
        return agentName;
    }
}
