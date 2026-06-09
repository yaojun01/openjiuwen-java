package com.openjiuwen.sdk.api.model;

/**
 * Token usage statistics for an agent invocation.
 */
public final class Usage {

    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final int toolCallCount;
    private final int iterationCount;

    public Usage(long inputTokens, long outputTokens, int toolCallCount, int iterationCount) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = inputTokens + outputTokens;
        this.toolCallCount = toolCallCount;
        this.iterationCount = iterationCount;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public int getIterationCount() {
        return iterationCount;
    }

    @Override
    public String toString() {
        return "Usage{" +
                "inputTokens=" + inputTokens +
                ", outputTokens=" + outputTokens +
                ", totalTokens=" + totalTokens +
                ", toolCallCount=" + toolCallCount +
                ", iterationCount=" + iterationCount +
                '}';
    }
}
