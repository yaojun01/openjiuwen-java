package com.openjiuwen.sdk.remote.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.sdk.api.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight MCP (Model Context Protocol) server embedded in the SDK.
 * <p>
 * Receives tool-call requests from the Runtime via HTTP POST and dispatches
 * them to registered {@link ToolProvider} instances.
 * <p>
 * Deployed as a Servlet 3.0 async servlet. Enterprise developers register
 * this in their web.xml or via annotation scanning.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code POST /mcp/tools/list} - list available tools</li>
 *   <li>{@code POST /mcp/tools/call} - execute a tool</li>
 * </ul>
 */
@WebServlet(
    urlPatterns = {"/mcp/tools/list", "/mcp/tools/call"},
    asyncSupported = true
)
public class LightweightMcpServer extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(LightweightMcpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, ToolProvider> providers = new ConcurrentHashMap<String, ToolProvider>();

    /**
     * Register a tool provider.
     *
     * @param provider the provider to register
     */
    public void registerProvider(ToolProvider provider) {
        providers.put(provider.getNamespace(), provider);
        LOG.info("Registered MCP tool provider: namespace={}, tools={}",
                provider.getNamespace(), provider.getToolDefinitions().size());
    }

    /**
     * Unregister a tool provider.
     */
    public void unregisterProvider(String namespace) {
        providers.remove(namespace);
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        LOG.info("LightweightMcpServer initialized");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        // Use async processing for tool calls
        AsyncContext asyncCtx = req.startAsync();

        String path = req.getServletPath();
        if (path.endsWith("/list")) {
            handleList(asyncCtx);
        } else if (path.endsWith("/call")) {
            handleCall(asyncCtx);
        } else {
            sendError(asyncCtx, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint");
        }
    }

    private void handleList(AsyncContext asyncCtx) throws IOException {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");

        // Collect all tools from all providers
        List<ObjectNode> toolsList = new ArrayList<ObjectNode>();
        for (ToolProvider provider : providers.values()) {
            for (com.openjiuwen.sdk.api.ToolDefinition def : provider.getToolDefinitions()) {
                ObjectNode toolNode = JSON.createObjectNode();
                toolNode.put("name", provider.getNamespace() + "." + def.getName());
                toolNode.put("description", def.getDescription());

                // Build parameter schema
                ObjectNode paramsNode = JSON.createObjectNode();
                paramsNode.put("type", "object");
                for (com.openjiuwen.sdk.api.ToolDefinition.ParameterDef param : def.getParameters()) {
                    ObjectNode paramNode = JSON.createObjectNode();
                    paramNode.put("type", param.getType());
                    paramNode.put("description", param.getDescription());
                    paramsNode.set(param.getName(), paramNode);
                }
                toolNode.set("parameters", paramsNode);
                toolsList.add(toolNode);
            }
        }

        response.putArray("tools").addAll(toolsList);
        sendJson(asyncCtx, response);
    }

    private void handleCall(AsyncContext asyncCtx) {
        try {
            HttpServletRequest req = (HttpServletRequest) asyncCtx.getRequest();
            JsonNode request = JSON.readTree(req.getInputStream());

            String toolName = request.has("params") && request.get("params").has("name")
                    ? request.get("params").get("name").asText()
                    : null;

            if (toolName == null || toolName.isEmpty()) {
                sendError(asyncCtx, HttpServletResponse.SC_BAD_REQUEST, "Missing tool name");
                return;
            }

            // Parse namespace.toolName
            String[] parts = toolName.split("\\.", 2);
            if (parts.length != 2) {
                sendError(asyncCtx, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid tool name format: expected 'namespace.toolName'");
                return;
            }

            String namespace = parts[0];
            String method = parts[1];

            ToolProvider provider = providers.get(namespace);
            if (provider == null) {
                sendError(asyncCtx, HttpServletResponse.SC_NOT_FOUND,
                        "No provider for namespace: " + namespace);
                return;
            }

            String input = request.has("params") && request.get("params").has("arguments")
                    ? JSON.writeValueAsString(request.get("params").get("arguments"))
                    : "{}";

            String result = provider.executeTool(method, input);

            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (request.has("id")) {
                response.put("id", request.get("id").asInt());
            }
            ObjectNode resultNode = JSON.createObjectNode();
            resultNode.put("content", result);
            response.set("result", resultNode);

            sendJson(asyncCtx, response);
        } catch (Exception e) {
            LOG.error("Tool call failed", e);
            sendError(asyncCtx, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Tool execution failed: " + e.getMessage());
        }
    }

    private void sendJson(AsyncContext asyncCtx, ObjectNode json) throws IOException {
        HttpServletResponse resp = (HttpServletResponse) asyncCtx.getResponse();
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(JSON.writeValueAsString(json));
        asyncCtx.complete();
    }

    private void sendError(AsyncContext asyncCtx, int status, String message) {
        try {
            HttpServletResponse resp = (HttpServletResponse) asyncCtx.getResponse();
            resp.setStatus(status);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            ObjectNode error = JSON.createObjectNode();
            error.put("error", message);
            resp.getWriter().write(JSON.writeValueAsString(error));
        } catch (IOException e) {
            LOG.error("Failed to send error response", e);
        } finally {
            asyncCtx.complete();
        }
    }
}
