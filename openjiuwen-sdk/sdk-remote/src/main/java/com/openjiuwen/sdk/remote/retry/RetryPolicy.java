package com.openjiuwen.sdk.remote.retry;

import com.openjiuwen.sdk.api.AgentException;
import com.openjiuwen.sdk.api.AgentTimeoutException;

/**
 * Retry policy with exponential backoff.
 * <p>
 * Retries on transient failures (HTTP 5xx, IOException).
 * Does not retry on 4xx errors or timeouts.
 */
public class RetryPolicy {

    private final int maxRetries;
    private final long baseDelayMs;

    public RetryPolicy(int maxRetries) {
        this(maxRetries, 500L);
    }

    public RetryPolicy(int maxRetries, long baseDelayMs) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
    }

    /**
     * Execute an action with retry logic.
     *
     * @param agentName agent name for error messages
     * @param action    the action to execute
     * @return result of the action
     * @throws AgentException if all retries are exhausted
     */
    public <T> T execute(String agentName, RetryableAction<T> action) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.execute();
            } catch (AgentTimeoutException e) {
                // Never retry timeouts
                throw e;
            } catch (AgentException e) {
                // Don't retry client errors (4xx)
                if (isClientError(e)) {
                    throw e;
                }
                lastException = e;
            } catch (Exception e) {
                lastException = e;
            }

            if (attempt < maxRetries) {
                sleep(backoffMs(attempt));
            }
        }

        throw new AgentException(agentName,
                "All " + maxRetries + " retries exhausted",
                lastException);
    }

    private long backoffMs(int attempt) {
        // Exponential backoff with jitter: baseDelay * 2^attempt + random jitter
        long delay = baseDelayMs * (1L << attempt);
        long jitter = (long) (delay * 0.2 * Math.random());
        return delay + jitter;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isClientError(AgentException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("HTTP 4");
    }

    /**
     * Functional interface for retryable actions.
     */
    public interface RetryableAction<T> {
        T execute() throws Exception;
    }
}
