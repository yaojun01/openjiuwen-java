package com.openjiuwen.sdk.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes a tool that can be called by the agent runtime.
 * <p>
 * Includes the tool name, description (used by the agent's LLM to decide
 * when to call it), and parameter schema.
 */
public final class ToolDefinition {

    private final String name;
    private final String description;
    private final List<ParameterDef> parameters;

    private ToolDefinition(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.parameters = Collections.unmodifiableList(new ArrayList<ParameterDef>(builder.parameters));
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<ParameterDef> getParameters() {
        return parameters;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String description = "";
        private List<ParameterDef> parameters = new ArrayList<ParameterDef>();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "tool name must not be null");
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder parameter(String paramName, String type, String paramDesc, boolean required) {
            this.parameters.add(new ParameterDef(paramName, type, paramDesc, required));
            return this;
        }

        public ToolDefinition build() {
            return new ToolDefinition(this);
        }
    }

    /**
     * Describes a single tool parameter.
     */
    public static final class ParameterDef {
        private final String name;
        private final String type;
        private final String description;
        private final boolean required;

        public ParameterDef(String name, String type, String description, boolean required) {
            this.name = Objects.requireNonNull(name);
            this.type = Objects.requireNonNull(type);
            this.description = description != null ? description : "";
            this.required = required;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }

        public boolean isRequired() {
            return required;
        }
    }

    @Override
    public String toString() {
        return "ToolDefinition{name='" + name + "', params=" + parameters.size() + '}';
    }
}
