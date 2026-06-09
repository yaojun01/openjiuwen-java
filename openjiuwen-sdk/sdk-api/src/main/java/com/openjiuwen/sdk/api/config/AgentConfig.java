package com.openjiuwen.sdk.api.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for an agent invocation.
 * <p>
 * Built via the Builder pattern. Not thread-safe during construction;
 * the built instance is immutable and thread-safe.
 */
public final class AgentConfig {

    private final String agentName;
    private final String model;
    private final String systemPrompt;
    private final Map<String, String> contextVariables;
    private final int maxIterations;
    private final Budget budget;
    private final List<String> enabledTools;

    private AgentConfig(Builder builder) {
        this.agentName = builder.agentName;
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.contextVariables = new HashMap<String, String>(builder.contextVariables);
        this.maxIterations = builder.maxIterations;
        this.budget = builder.budget;
        this.enabledTools = new ArrayList<String>(builder.enabledTools);
    }

    public String getAgentName() {
        return agentName;
    }

    public String getModel() {
        return model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Map<String, String> getContextVariables() {
        return contextVariables;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public Budget getBudget() {
        return budget;
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public static Builder builder(String agentName) {
        return new Builder(agentName);
    }

    public static class Builder {
        private final String agentName;
        private String model;
        private String systemPrompt;
        private Map<String, String> contextVariables = new HashMap<String, String>();
        private int maxIterations = 10;
        private Budget budget;
        private List<String> enabledTools = new ArrayList<String>();

        private Builder(String agentName) {
            this.agentName = Objects.requireNonNull(agentName, "agentName must not be null");
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder contextVariable(String key, String value) {
            this.contextVariables.put(key, value);
            return this;
        }

        public Builder contextVariables(Map<String, String> variables) {
            this.contextVariables.putAll(variables);
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations must be positive");
            }
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder budget(Budget budget) {
            this.budget = budget;
            return this;
        }

        public Builder enableTool(String toolName) {
            this.enabledTools.add(toolName);
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
