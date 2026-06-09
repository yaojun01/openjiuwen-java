package com.openjiuwen.sdk.remote.http;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Simple connection factory with timeout configuration.
 * <p>
 * Uses JDK HttpURLConnection (no external dependency).
 * Java 8 does not have a built-in connection pool in the standard API,
 * so this manages per-request connections with consistent timeout settings.
 */
public class HttpConnectionPool {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HttpConnectionPool(int timeoutMs) {
        this.connectTimeoutMs = Math.min(timeoutMs, 5000); // cap connect at 5s
        this.readTimeoutMs = timeoutMs;
    }

    /**
     * Open a new HTTP connection with configured timeouts.
     *
     * @param url target URL
     * @return configured HttpURLConnection (not yet connected)
     * @throws Exception if URL is invalid
     */
    public HttpURLConnection open(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setAllowUserInteraction(false);
        conn.setRequestProperty("User-Agent", "openjiuwen-sdk/0.1.0");
        return conn;
    }
}
