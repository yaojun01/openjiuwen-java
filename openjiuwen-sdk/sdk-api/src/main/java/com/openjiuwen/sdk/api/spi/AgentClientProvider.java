package com.openjiuwen.sdk.api.spi;

import com.openjiuwen.sdk.api.AgentClient;
import java.util.Map;

/**
 * SPI for creating AgentClient instances.
 * <p>
 * Implementations are loaded via {@link java.util.ServiceLoader}.
 * The SDK ships two providers:
 * <ul>
 *   <li>{@code RemoteAgentClientProvider} - HTTP-based remote client</li>
 *   <li>{@code EmbeddedAgentClientProvider} - in-process direct client (Java 21)</li>
 * </ul>
 */
public interface AgentClientProvider {

    /**
     * Unique name for this provider (e.g., "remote", "embedded").
     */
    String name();

    /**
     * Create an AgentClient from the given configuration properties.
     *
     * @param config configuration key-value pairs
     * @return a new AgentClient instance
     */
    AgentClient create(Map<String, String> config);
}
