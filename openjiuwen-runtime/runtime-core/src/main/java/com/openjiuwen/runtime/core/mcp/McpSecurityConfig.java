package com.openjiuwen.runtime.core.mcp;

import javax.net.ssl.SSLContext;

/**
 * MCP 安全配置——控制 Runtime 与 SDK 之间 MCP 通信的加密策略。
 *
 * W4 修复核心：
 * - 生产环境：强制 mTLS（双向 TLS 认证）
 * - 开发环境：可降级为明文（通过配置开关）
 * - 无论哪个环境，安全状态都可被 SafetyBoundary 查询
 *
 * 设计原则：
 * - 安全是默认的，不安全需要显式声明
 * - @ConditionalOnProperty 控制降级策略
 * - SSLContext 注入点支持多种证书管理方案
 */
public record McpSecurityConfig(
    boolean mtlsEnabled,
    boolean devModeAllowed,
    SSLContext sslContext,
    String trustStorePath,
    String keyStorePath,
    String trustStorePassword,
    String keyStorePassword,
    String[] allowedProtocols,
    String[] allowedCipherSuites
) {

    public McpSecurityConfig {
        if (allowedProtocols == null || allowedProtocols.length == 0) {
            allowedProtocols = new String[]{"TLSv1.3", "TLSv1.2"};
        }
        if (allowedCipherSuites == null || allowedCipherSuites.length == 0) {
            allowedCipherSuites = new String[]{
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
            };
        }
    }

    /** 生产环境配置：强制 mTLS */
    public static McpSecurityConfig production(
            String trustStorePath, String trustStorePassword,
            String keyStorePath, String keyStorePassword) {
        return new McpSecurityConfig(
            true, false, null,
            trustStorePath, keyStorePath,
            trustStorePassword, keyStorePassword,
            null, null
        );
    }

    /** 开发环境配置：降级允许明文 */
    public static McpSecurityConfig development() {
        return new McpSecurityConfig(
            false, true, null,
            null, null, null, null,
            null, null
        );
    }

    /** 是否安全（mTLS 已启用） */
    public boolean isSecure() {
        return mtlsEnabled;
    }

    /** 是否允许不安全的连接（仅开发环境） */
    public boolean allowsInsecure() {
        return devModeAllowed && !mtlsEnabled;
    }
}
