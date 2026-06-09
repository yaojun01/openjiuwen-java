package com.openjiuwen.sdk.remote;

import com.openjiuwen.sdk.api.AgentClient;
import com.openjiuwen.sdk.api.spi.AgentClientProvider;

import java.util.Map;

/**
 * SPI provider that creates {@link RemoteAgentClient} instances.
 * <p>
 * Registered as a ServiceLoader provider for name "remote".
 */
public class RemoteAgentClientProvider implements AgentClientProvider {

    @Override
    public String name() {
        return "remote";
    }

    @Override
    public AgentClient create(Map<String, String> config) {
        String runtimeUrl = config.get("runtimeUrl");
        if (runtimeUrl == null || runtimeUrl.isEmpty()) {
            throw new IllegalArgumentException("runtimeUrl is required for remote client");
        }

        int timeoutMs = getIntConfig(config, "timeoutMs", 30000);
        int retryCount = getIntConfig(config, "retryCount", 3);
        String apiKey = config.get("apiKey");

        return new RemoteAgentClient(runtimeUrl, timeoutMs, retryCount, apiKey);
    }

    private int getIntConfig(Map<String, String> config, String key, int defaultValue) {
        String value = config.get(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
