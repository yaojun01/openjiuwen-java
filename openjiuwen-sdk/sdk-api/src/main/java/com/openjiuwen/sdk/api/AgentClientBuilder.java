package com.openjiuwen.sdk.api;

import com.openjiuwen.sdk.api.spi.AgentClientProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

/**
 * Builder for constructing {@link AgentClient} instances.
 * <p>
 * Entry point for enterprise developers:
 * <pre>
 * AgentClient client = AgentClientBuilder.remote("http://runtime:8080")
 *     .withTimeout(30, TimeUnit.SECONDS)
 *     .withRetry(3)
 *     .build();
 * </pre>
 */
public final class AgentClientBuilder {

    private static final String PROVIDER_REMOTE = "remote";
    private static final String PROVIDER_EMBEDDED = "embedded";

    private final String providerName;
    private final Map<String, String> config = new HashMap<String, String>();
    private int timeoutMs = 30000;
    private int retryCount = 3;

    private AgentClientBuilder(String providerName) {
        this.providerName = providerName;
    }

    /**
     * Create a builder for remote (HTTP) client.
     *
     * @param runtimeUrl base URL of the Openjiuwen Runtime (e.g., "http://runtime:8080")
     * @return builder instance
     */
    public static AgentClientBuilder remote(String runtimeUrl) {
        AgentClientBuilder builder = new AgentClientBuilder(PROVIDER_REMOTE);
        builder.config.put("runtimeUrl", runtimeUrl);
        return builder;
    }

    /**
     * Create a builder for embedded (in-process) client.
     * Requires Java 21+ runtime.
     *
     * @return builder instance
     */
    public static AgentClientBuilder embedded() {
        return new AgentClientBuilder(PROVIDER_EMBEDDED);
    }

    /**
     * Set invocation timeout.
     *
     * @param timeout timeout value
     * @param unit    time unit
     * @return this builder
     */
    public AgentClientBuilder withTimeout(long timeout, TimeUnit unit) {
        this.timeoutMs = (int) unit.toMillis(timeout);
        return this;
    }

    /**
     * Set retry count for transient failures.
     *
     * @param retryCount number of retries (0 = no retry)
     * @return this builder
     */
    public AgentClientBuilder withRetry(int retryCount) {
        this.retryCount = retryCount;
        return this;
    }

    /**
     * Set API key for authentication.
     *
     * @param apiKey API key string
     * @return this builder
     */
    public AgentClientBuilder withApiKey(String apiKey) {
        config.put("apiKey", apiKey);
        return this;
    }

    /**
     * Add a custom configuration property.
     *
     * @param key   config key
     * @param value config value
     * @return this builder
     */
    public AgentClientBuilder withConfig(String key, String value) {
        config.put(key, value);
        return this;
    }

    /**
     * Build the AgentClient.
     * <p>
     * Uses ServiceLoader to find the matching provider.
     *
     * @return configured AgentClient
     * @throws AgentException if no matching provider is found
     */
    public AgentClient build() {
        config.put("timeoutMs", String.valueOf(timeoutMs));
        config.put("retryCount", String.valueOf(retryCount));

        ServiceLoader<AgentClientProvider> loader = ServiceLoader.load(AgentClientProvider.class);
        for (AgentClientProvider provider : loader) {
            if (providerName.equals(provider.name())) {
                return provider.create(config);
            }
        }

        throw new AgentException(
                "No AgentClientProvider found for '" + providerName + "'. " +
                "Ensure sdk-remote or sdk-embedded is on the classpath."
        );
    }
}
