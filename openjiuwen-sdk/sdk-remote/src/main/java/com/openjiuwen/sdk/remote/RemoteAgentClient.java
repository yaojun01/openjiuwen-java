package com.openjiuwen.sdk.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.sdk.api.*;
import com.openjiuwen.sdk.api.config.Budget;
import com.openjiuwen.sdk.api.model.AgentResult;
import com.openjiuwen.sdk.api.model.Usage;
import com.openjiuwen.sdk.remote.http.HttpConnectionPool;
import com.openjiuwen.sdk.remote.retry.RetryPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Remote AgentClient that communicates with the Runtime via HTTP REST API.
 * <p>
 * Thread-safe. Uses a connection pool and retry policy internally.
 * Java 8 compatible — uses HttpURLConnection, no external HTTP client.
 */
public class RemoteAgentClient implements AgentClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String runtimeUrl;
    private final int timeoutMs;
    private final String apiKey;
    private final HttpConnectionPool connectionPool;
    private final RetryPolicy retryPolicy;

    public RemoteAgentClient(String runtimeUrl, int timeoutMs, int retryCount, String apiKey) {
        this.runtimeUrl = normalizeUrl(runtimeUrl);
        this.timeoutMs = timeoutMs;
        this.apiKey = apiKey;
        this.connectionPool = new HttpConnectionPool(timeoutMs);
        this.retryPolicy = new RetryPolicy(retryCount);
    }

    @Override
    public AgentResult invoke(String agentName, String input) {
        return retryPolicy.execute(agentName, () -> doInvoke(agentName, input, null));
    }

    @Override
    public void invokeAsync(String agentName, String input, AgentEventHandler handler) {
        Thread thread = new Thread(new AsyncInvocation(agentName, input, null, handler),
                "openjiuwen-async-" + agentName);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public AgentResult invokeWithBudget(String agentName, String input, Budget budget) {
        return retryPolicy.execute(agentName, () -> doInvoke(agentName, input, budget));
    }

    private AgentResult doInvoke(String agentName, String input, Budget budget) throws Exception {
        String url = runtimeUrl + "/api/v1/agents/" + agentName + "/invoke";
        HttpURLConnection conn = connectionPool.open(new URL(url));

        // Build request body
        ObjectNode body = JSON.createObjectNode();
        body.put("input", input);
        if (budget != null) {
            ObjectNode budgetNode = JSON.createObjectNode();
            if (budget.getMaxInputTokens() > 0) {
                budgetNode.put("maxInputTokens", budget.getMaxInputTokens());
            }
            if (budget.getMaxOutputTokens() > 0) {
                budgetNode.put("maxOutputTokens", budget.getMaxOutputTokens());
            }
            if (budget.getMaxCostUsd() > 0) {
                budgetNode.put("maxCostUsd", budget.getMaxCostUsd());
            }
            if (budget.getMaxIterations() > 0) {
                budgetNode.put("maxIterations", budget.getMaxIterations());
            }
            body.set("budget", budgetNode);
        }

        // Write request
        byte[] bodyBytes = JSON.writeValueAsBytes(body);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setFixedLengthStreamingMode(bodyBytes.length);
        conn.setDoOutput(true);

        OutputStream out = conn.getOutputStream();
        try {
            out.write(bodyBytes);
            out.flush();
        } finally {
            out.close();
        }

        // Read response
        int status = conn.getResponseCode();
        InputStream responseStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

        AgentResult result;
        try {
            byte[] responseBytes = readFully(responseStream);
            JsonNode responseJson = JSON.readTree(responseBytes);

            if (status >= 400) {
                String errorMsg = responseJson.has("error") ? responseJson.get("error").asText() :
                        "HTTP " + status;
                throw new AgentException(agentName, errorMsg);
            }

            result = parseResult(responseJson);
        } finally {
            if (responseStream != null) {
                responseStream.close();
            }
            conn.disconnect();
        }

        return result;
    }

    private AgentResult parseResult(JsonNode json) {
        AgentResult.Builder builder = AgentResult.success(
                json.has("output") ? json.get("output").asText() : ""
        );

        if (json.has("metadata")) {
            JsonNode meta = json.get("metadata");
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = meta.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                builder.metadata(field.getKey(), field.getValue().asText());
            }
        }

        if (json.has("usage")) {
            JsonNode usageNode = json.get("usage");
            builder.usage(new Usage(
                    usageNode.has("inputTokens") ? usageNode.get("inputTokens").asLong() : 0,
                    usageNode.has("outputTokens") ? usageNode.get("outputTokens").asLong() : 0,
                    usageNode.has("toolCallCount") ? usageNode.get("toolCallCount").asInt() : 0,
                    usageNode.has("iterationCount") ? usageNode.get("iterationCount").asInt() : 0
            ));
        }

        if (json.has("durationMs")) {
            builder.durationMs(json.get("durationMs").asLong());
        }

        return builder.build();
    }

    private byte[] readFully(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buffer.write(tmp, 0, n);
        }
        return buffer.toByteArray();
    }

    private static String normalizeUrl(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Runnable for async invocations.
     */
    private class AsyncInvocation implements Runnable {
        private final String agentName;
        private final String input;
        private final Budget budget;
        private final AgentEventHandler handler;

        AsyncInvocation(String agentName, String input, Budget budget, AgentEventHandler handler) {
            this.agentName = agentName;
            this.input = input;
            this.budget = budget;
            this.handler = handler;
        }

        @Override
        public void run() {
            String sessionId = java.util.UUID.randomUUID().toString();
            try {
                handler.onStart(agentName, sessionId);
                AgentResult result;
                if (budget != null) {
                    result = invokeWithBudget(agentName, input, budget);
                } else {
                    result = invoke(agentName, input);
                }
                handler.onComplete(result);
            } catch (AgentException e) {
                handler.onError(e);
            } catch (Exception e) {
                handler.onError(new AgentException(agentName, "Async invocation failed", e));
            }
        }
    }
}
