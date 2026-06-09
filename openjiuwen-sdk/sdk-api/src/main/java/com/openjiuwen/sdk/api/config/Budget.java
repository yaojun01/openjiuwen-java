package com.openjiuwen.sdk.api.config;

import java.util.Objects;

/**
 * Budget constraints for an agent invocation.
 * <p>
 * Controls resource consumption: token limits, cost caps, and iteration caps.
 * All fields are optional; unset fields mean "no limit".
 */
public final class Budget {

    private final long maxInputTokens;
    private final long maxOutputTokens;
    private final double maxCostUsd;
    private final int maxIterations;

    private Budget(Builder builder) {
        this.maxInputTokens = builder.maxInputTokens;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.maxCostUsd = builder.maxCostUsd;
        this.maxIterations = builder.maxIterations;
    }

    /**
     * Maximum input tokens allowed, or -1 for unlimited.
     */
    public long getMaxInputTokens() {
        return maxInputTokens;
    }

    /**
     * Maximum output tokens allowed, or -1 for unlimited.
     */
    public long getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * Maximum cost in USD, or -1 for unlimited.
     */
    public double getMaxCostUsd() {
        return maxCostUsd;
    }

    /**
     * Maximum agent loop iterations, or -1 for unlimited.
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long maxInputTokens = -1;
        private long maxOutputTokens = -1;
        private double maxCostUsd = -1;
        private int maxIterations = -1;

        public Builder maxInputTokens(long maxInputTokens) {
            if (maxInputTokens <= 0 && maxInputTokens != -1) {
                throw new IllegalArgumentException("maxInputTokens must be positive or -1");
            }
            this.maxInputTokens = maxInputTokens;
            return this;
        }

        public Builder maxOutputTokens(long maxOutputTokens) {
            if (maxOutputTokens <= 0 && maxOutputTokens != -1) {
                throw new IllegalArgumentException("maxOutputTokens must be positive or -1");
            }
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder maxCostUsd(double maxCostUsd) {
            if (maxCostUsd <= 0 && maxCostUsd != -1) {
                throw new IllegalArgumentException("maxCostUsd must be positive or -1");
            }
            this.maxCostUsd = maxCostUsd;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            if (maxIterations <= 0 && maxIterations != -1) {
                throw new IllegalArgumentException("maxIterations must be positive or -1");
            }
            this.maxIterations = maxIterations;
            return this;
        }

        public Budget build() {
            return new Budget(this);
        }
    }

    @Override
    public String toString() {
        return "Budget{" +
                "maxInputTokens=" + maxInputTokens +
                ", maxOutputTokens=" + maxOutputTokens +
                ", maxCostUsd=" + maxCostUsd +
                ", maxIterations=" + maxIterations +
                '}';
    }
}
