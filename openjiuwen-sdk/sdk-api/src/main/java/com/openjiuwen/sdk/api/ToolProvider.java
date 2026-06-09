package com.openjiuwen.sdk.api;

import java.util.List;

/**
 * SPI interface for registering tools that the agent runtime can call back into.
 * <p>
 * Enterprise developers implement this to expose their business logic
 * (e.g., query ERP system, call internal API) as agent-callable tools.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader} or
 * registered explicitly through {@link AgentConfig}.
 */
public interface ToolProvider {

    /**
     * Unique namespace for this provider's tools.
     * <p>
     * Tool names are qualified as {@code namespace.toolName} to avoid collisions.
     *
     * @return non-null namespace string (alphanumeric + hyphens)
     */
    String getNamespace();

    /**
     * List all tools provided by this provider.
     *
     * @return unmodifiable list of tool definitions, never null
     */
    List<ToolDefinition> getToolDefinitions();

    /**
     * Execute a tool call.
     *
     * @param toolName the tool name (without namespace prefix)
     * @param input    JSON string of tool input parameters
     * @return JSON string of tool output
     * @throws AgentException if tool execution fails
     */
    String executeTool(String toolName, String input);
}
