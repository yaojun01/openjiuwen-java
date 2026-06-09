package com.openjiuwen.sdk.remote.mcp;

import com.openjiuwen.sdk.api.AgentException;
import com.openjiuwen.sdk.api.Tool;
import com.openjiuwen.sdk.api.ToolDefinition;
import com.openjiuwen.sdk.api.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * ToolProvider that discovers {@link Tool @Tool}-annotated methods
 * from a given object and exposes them via MCP.
 * <p>
 * Usage:
 * <pre>
 * OrderService orderService = new OrderService(); // has @Tool methods
 * McpToolProvider provider = new McpToolProvider("order", orderService);
 * mcpServer.registerProvider(provider);
 * </pre>
 */
public class McpToolProvider implements ToolProvider {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolProvider.class);

    private final String namespace;
    private final Object target;
    private final Map<String, MethodInvoker> toolMethods = new LinkedHashMap<String, MethodInvoker>();

    /**
     * Create a provider that scans the target object for @Tool methods.
     *
     * @param namespace unique namespace for this provider's tools
     * @param target    object containing @Tool annotated methods
     */
    public McpToolProvider(String namespace, Object target) {
        this.namespace = namespace;
        this.target = target;
        scanTools();
    }

    private void scanTools() {
        Class<?> clazz = target.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                validateToolMethod(method);
                String toolName = annotation.name();
                toolMethods.put(toolName, new MethodInvoker(method, annotation.description()));
                LOG.debug("Discovered @Tool: {}.{}", namespace, toolName);
            }
        }
        LOG.info("McpToolProvider '{}' discovered {} tools", namespace, toolMethods.size());
    }

    private void validateToolMethod(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != 1 || paramTypes[0] != String.class) {
            throw new IllegalArgumentException(
                    "@Tool method '" + method.getName() +
                    "' must have exactly one String parameter, found: " +
                    Arrays.toString(paramTypes));
        }
        if (method.getReturnType() != String.class) {
            throw new IllegalArgumentException(
                    "@Tool method '" + method.getName() +
                    "' must return String, found: " +
                    method.getReturnType().getName());
        }
        method.setAccessible(true);
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public List<ToolDefinition> getToolDefinitions() {
        List<ToolDefinition> definitions = new ArrayList<ToolDefinition>();
        for (Map.Entry<String, MethodInvoker> entry : toolMethods.entrySet()) {
            definitions.add(ToolDefinition.builder(entry.getKey())
                    .description(entry.getValue().description)
                    .build());
        }
        return definitions;
    }

    @Override
    public String executeTool(String toolName, String input) {
        MethodInvoker invoker = toolMethods.get(toolName);
        if (invoker == null) {
            throw new AgentException("Unknown tool: " + namespace + "." + toolName);
        }

        try {
            return (String) invoker.method.invoke(target, input);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AgentException("Tool '" + namespace + "." + toolName + "' execution failed",
                    cause);
        }
    }

    /**
     * Internal holder for a discovered @Tool method.
     */
    private static class MethodInvoker {
        final Method method;
        final String description;

        MethodInvoker(Method method, String description) {
            this.method = method;
            this.description = description;
        }
    }
}
