package com.openjiuwen.sdk.api.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of an agent execution.
 * <p>
 * Carries the agent's text output, structured metadata, and usage statistics.
 */
public final class AgentResult {

    private final boolean success;
    private final String output;
    private final String errorMessage;
    private final Map<String, Object> metadata;
    private final Usage usage;
    private final long durationMs;

    private AgentResult(Builder builder) {
        this.success = builder.success;
        this.output = builder.output;
        this.errorMessage = builder.errorMessage;
        this.metadata = Collections.unmodifiableMap(new HashMap<String, Object>(builder.metadata));
        this.usage = builder.usage;
        this.durationMs = builder.durationMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getOutput() {
        return output;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Usage getUsage() {
        return usage;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public static Builder success(String output) {
        return new Builder().success(true).output(output);
    }

    public static Builder failure(String errorMessage) {
        return new Builder().success(false).errorMessage(errorMessage);
    }

    public static class Builder {
        private boolean success;
        private String output = "";
        private String errorMessage;
        private Map<String, Object> metadata = new HashMap<String, Object>();
        private Usage usage;
        private long durationMs;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public AgentResult build() {
            Objects.requireNonNull(output, "output must not be null");
            return new AgentResult(this);
        }
    }

    @Override
    public String toString() {
        return "AgentResult{" +
                "success=" + success +
                ", output='" + (output != null && output.length() > 100 ? output.substring(0, 100) + "..." : output) + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", durationMs=" + durationMs +
                '}';
    }
}
