# Openjiuwen-Java MCP 安全层 + 工具安全体系完整方案

> 2026-06-07 | 安全架构设计 | P1 优先级（W4: MCP 敏感数据传输安全）
> 前置：[Deep Agent 架构 v3.1](./2026-06-06-openjiuwen-java-architecture-v3.md) + [帕累托变体 Alpha](./2026-06-07-openjiuwen-pareto-variant-alpha-developer-control.md) + [帕累托变体 Beta](./2026-06-07-openjiuwen-beta-llm-autonomous-orchestration.md) + [GEPA Runtime 融合](./2026-06-07-gepa-runtime-cycles-1-10.md)
> 设计约束：SDK 端 Java 8+，Runtime 端 Java 21 + Spring Boot 3.3+

---

## 零、架构定位：安全层在三层架构中的位置

```
┌───────────────────────────────────────────────────────────────────────────┐
│  L3 策略层：AlphaStrategy / BetaStrategy / AdaptiveStrategy               │
│      ↕ 调用安全层做拦截                                                     │
│  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  │
│  ★ 安全层（本方案设计范围）                                                  │
│      L1 mTLS 传输安全                                                      │
│      L2 身份认证（JWT / API Key / mTLS CN）                                │
│      L3 权限控制（ToolWhitelist / Role-Based）                              │
│      L4 参数校验（类型 + 范围 + 敏感字段脱敏）                                │
│      L5 操作审计（完整调用链日志）                                            │
│      L6 幂等保障（CompensableTool + 幂等键）                                 │
│  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  │
│  L1 内核层：AgentKernel + SafetyBoundary + CheckpointStore + VFS           │
│      ↕ SafetyBoundary 调用安全层做 Violation 判定                           │
└───────────────────────────────────────────────────────────────────────────┘

SDK 侧（Java 8）                              Runtime 侧（Java 21）
┌────────────────────┐     mTLS over HTTP     ┌────────────────────┐
│ MCP Server          │ ←──────────────────→  │ MCP Client          │
│ (Servlet 3.0 async) │   L1: 加密传输         │ (Spring WebClient)  │
│                     │   L2: 双向证书认证      │                     │
│ mTLS: javax.net.ssl│   L3: 工具白名单检查    │ mTLS: SSLContext    │
│ (Java 8 原生支持)    │   L4: 参数校验         │ (Java 21 原生)      │
│                     │   L5: 审计日志         │                     │
│ SensitiveFieldFilter│   L6: 幂等保障         │ SafetyBoundary      │
│ (脱敏拦截器)         │                       │ GuardrailEngine     │
└────────────────────┘                       └────────────────────┘
```

---

## 一、L1 MCP 传输安全（mTLS）

### 1.1 mTLS 双向认证设计

**核心问题**：SDK（Java 8 企业系统）和 Runtime（Java 21 Spring Boot）之间通过 HTTP 传输 MCP 协议。MCP 调用携带业务参数（订单号、金额、客户信息），这些数据在网络上是明文——这是 W4 的核心风险。

**方案**：mTLS（双向 TLS），SDK 和 Runtime 各持有证书，互相验证对方身份。

#### 1.1.1 Runtime 端（Java 21 + Spring Boot 3.3+）

```java
package com.openjiuwen.runtime.security.transport;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * Runtime 端 mTLS 配置。
 *
 * Spring Boot 3.3+ 原生支持 server.ssl 配置。
 * 此配置类在 production profile 下生效。
 */
@Configuration
@Profile("production")
public class RuntimeTlsConfiguration {

    private final TlsProperties tlsProperties;

    public RuntimeTlsConfiguration(TlsProperties tlsProperties) {
        this.tlsProperties = tlsProperties;
    }

    /**
     * 配置嵌入式的 Tomcat/Netty 支持 mTLS。
     *
     * Spring Boot application.yml 配置：
     * server:
     *   ssl:
     *     enabled: true
     *     client-auth: need          ← 强制客户端证书
     *     key-store: classpath:runtime.p12
     *     key-store-password: ${TLS_KS_PASSWORD}
     *     key-store-type: PKCS12
     *     trust-store: classpath:truststore.p12
     *     trust-store-password: ${TLS_TS_PASSWORD}
     *     trust-store-type: PKCS12
     *     protocol: TLSv1.3
     *     enabled-protocols: TLSv1.2,TLSv1.3
     */
    // 大部分配置通过 application.yml 完成，这里处理运行时动态加载
}
```

```java
package com.openjiuwen.runtime.security.transport;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TLS 配置属性。
 * 与 Spring Boot server.ssl 对齐，额外增加 mTLS 特有配置。
 */
@ConfigurationProperties(prefix = "openjiuwen.security.tls")
public record TlsProperties(
    boolean enabled,
    boolean mtlsRequired,              // 是否强制 mTLS（false = 允许匿名客户端）
    String certificateRotationPath,     // 证书热更新监听路径
    int certificateCheckIntervalSeconds // 证书有效期检查间隔
) {
    public TlsProperties {
        if (certificateCheckIntervalSeconds <= 0) {
            certificateCheckIntervalSeconds = 3600; // 默认每小时检查
        }
    }
}
```

#### 1.1.2 SDK 端（Java 8+）

```java
package com.openjiuwen.sdk.security;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * SDK 端 mTLS 配置。
 *
 * Java 8 原生支持 SSLContext / KeyManagerFactory / TrustManagerFactory。
 * 不需要任何额外依赖。
 *
 * 使用方式：
 *   SslContext ssl = SdkTlsFactory.createMtlsContext(
 *       "/path/to/client.p12", "clientPassword",
 *       "/path/to/truststore.p12", "trustPassword"
 *   );
 *   HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
 *   conn.setSSLSocketFactory(ssl.getSocketFactory());
 *   conn.setHostnameVerifier((host, session) -> true); // 生产环境用严格验证
 */
public final class SdkTlsFactory {

    private SdkTlsFactory() {}

    /**
     * 创建支持 mTLS 的 SSLContext。
     *
     * @param keyStorePath    客户端证书（PKCS12 格式）
     * @param keyStorePassword 证书密码
     * @param trustStorePath   信任的 CA 证书（PKCS12 格式）
     * @param trustStorePassword 信任库密码
     * @return 配置好的 SSLContext
     */
    public static SSLContext createMtlsContext(
            String keyStorePath, String keyStorePassword,
            String trustStorePath, String trustStorePassword) throws Exception {

        // 1. 加载客户端证书（SDK 证明自己的身份）
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(keyStorePath)) {
            keyStore.load(is, keyStorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyStorePassword.toCharArray());

        // 2. 加载信任库（SDK 验证 Runtime 的证书）
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(trustStorePath)) {
            trustStore.load(is, trustStorePassword.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // 3. 构建 SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        return sslContext;
    }

    /**
     * 创建仅服务端验证的 SSLContext（不要求客户端证书）。
     * 用于开发环境或证书尚未部署时的降级。
     */
    public static SSLContext createServerAuthOnlyContext(
            String trustStorePath, String trustStorePassword) throws Exception {

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(trustStorePath)) {
            trustStore.load(is, trustStorePassword.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());

        return sslContext;
    }
}
```

#### 1.1.3 MCP Client（Runtime 端）使用 mTLS 调 SDK

```java
package com.openjiuwen.runtime.security.transport;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * MCP Client 的 mTLS WebClient 配置。
 *
 * Runtime 通过 MCP Client 调用 SDK 端的 MCP Server。
 * 所有 HTTP 请求走 mTLS。
 */
@Component
public class McpTlsWebClientFactory {

    private final TlsProperties tlsProperties;

    public McpTlsWebClientFactory(TlsProperties tlsProperties) {
        this.tlsProperties = tlsProperties;
    }

    /**
     * 创建配置了 mTLS 的 WebClient。
     * 用于 Runtime → SDK 的 MCP 调用。
     */
    public WebClient createMtlsWebClient(String sdkBaseUrl) throws Exception {
        if (!tlsProperties.enabled()) {
            return WebClient.create(sdkBaseUrl);
        }

        // 加载客户端证书
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var is = new FileInputStream(tlsProperties.keyStorePath())) {
            keyStore.load(is, tlsProperties.keyStorePassword().toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, tlsProperties.keyStorePassword().toCharArray());

        // 加载信任库
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var is = new FileInputStream(tlsProperties.trustStorePath())) {
            trustStore.load(is, tlsProperties.trustStorePassword().toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SslContext sslContext = SslContextBuilder.forClient()
            .keyManager(kmf)
            .trustManager(tmf)
            .build();

        HttpClient httpClient = HttpClient.create()
            .secure(spec -> spec.sslContext(sslContext));

        return WebClient.builder()
            .baseUrl(sdkBaseUrl)
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
            .build();
    }
}
```

### 1.2 证书管理策略

| 场景 | 方案 | 证书颁发 | 证书轮换 | 适用环境 |
|------|------|---------|---------|---------|
| **企业内网 CA** | 企业自有 PKI（如 Microsoft AD CS、HashiCorp Vault） | 企业 CA 签发 | Vault 自动轮换 | 金融/电力/政务 |
| **自签名** | 运维脚本批量生成 | 自建 Root CA → 签发子证书 | 手动轮换，Cron 提醒 | 开发/测试/小型部署 |
| **Let's Encrypt** | ACME 协议自动签发 | Let's Encrypt | certbot 自动续期 | 有公网 IP 的生产环境 |
| **云厂商证书管理** | AWS ACM / 阿里云 SSL / 华为云 SCM | 云厂商 CA | 云厂商自动续期 | 公有云部署 |

**推荐策略**：

```
开发环境  → 自签名（零成本，一键脚本生成）
测试环境  → 自签名或企业 CA（与生产一致）
生产环境  → 企业 CA（金融/电力）或 Let's Encrypt（互联网场景）
```

**证书生命周期管理**：

```java
package com.openjiuwen.runtime.security.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 证书有效期监控。
 * 定期检查证书是否即将过期，提前告警。
 */
public class CertificateHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(CertificateHealthMonitor.class);
    private static final Duration WARNING_THRESHOLD = Duration.ofDays(30);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final TlsProperties properties;

    public CertificateHealthMonitor(TlsProperties properties) {
        this.properties = properties;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
            this::checkCertificates,
            0,
            properties.certificateCheckIntervalSeconds(),
            TimeUnit.SECONDS
        );
    }

    private void checkCertificates() {
        try {
            // 检查服务端证书
            checkCertificateExpiry(properties.keyStorePath(), "server");

            // 检查信任库中的 CA 证书
            checkCertificateExpiry(properties.trustStorePath(), "trust");

        } catch (Exception e) {
            log.error("证书检查失败", e);
        }
    }

    private void checkCertificateExpiry(String keystorePath, String label) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var is = new java.io.FileInputStream(keystorePath)) {
            ks.load(is, (char[]) null);
        }

        var aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (ks.isCertificateEntry(alias) || ks.isKeyEntry(alias)) {
                var cert = (X509Certificate) ks.getCertificate(alias);
                Date expiry = cert.getNotAfter();
                Duration remaining = Duration.between(Instant.now(), expiry.toInstant());

                if (remaining.isZero() || remaining.isNegative()) {
                    log.error("[{}] 证书已过期: alias={}, expiry={}", label, alias, expiry);
                } else if (remaining.compareTo(WARNING_THRESHOLD) < 0) {
                    log.warn("[{}] 证书即将过期: alias={}, 剩余{}天", label, alias, remaining.toDays());
                }
            }
        }
    }

    public void stop() {
        scheduler.shutdown();
    }
}
```

### 1.3 降级策略

```java
package com.openjiuwen.runtime.security.transport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * TLS 降级配置。
 *
 * 三个 Profile 控制安全等级：
 * - production  : mTLS 强制（client-auth: need）
 * - staging     : TLS 服务端验证（client-auth: want，不强制客户端证书）
 * - development : HTTP 明文（不启用 SSL）
 */
@Configuration
public class TlsProfileConfiguration {

    /**
     * 开发环境：完全跳过 TLS。
     * 风险明确：仅限本地开发，网络不可信时严禁使用。
     */
    @Bean
    @Profile("development")
    public TlsProperties developmentTlsProperties() {
        return new TlsProperties(false, false, null, 3600);
    }

    /**
     * 测试/预发环境：TLS 但不强求 mTLS。
     * 证书验证单向：客户端验证服务端证书，服务端不验证客户端。
     */
    @Bean
    @Profile("staging")
    public TlsProperties stagingTlsProperties() {
        return new TlsProperties(true, false, null, 3600);
    }

    /**
     * 生产环境：强制 mTLS。
     */
    @Bean
    @Profile("production")
    public TlsProperties productionTlsProperties() {
        return new TlsProperties(true, true, null, 3600);
    }
}
```

---

## 二、工具级安全分层（L1-L6）

### 2.1 安全分层总览

```
请求流向：SDK → MCP over mTLS → Runtime → SafetyBoundary → GuardrailEngine → Tool 执行

         SDK 端                    网络层                      Runtime 端
  ┌─────────────────┐    ┌──────────────────┐    ┌─────────────────────────────────────┐
  │ L6 幂等键注入    │    │ L1 mTLS 加密      │    │ L2 证书 CN / JWT / API Key 认证      │
  │ L4 参数脱敏预处理 │───→│                  │───→│ L3 ToolWhitelist + RBAC 权限检查      │
  │                  │    │                  │    │ L4 参数类型+范围校验                   │
  │                  │    │                  │    │ L5 调用链审计日志                      │
  │                  │    │                  │    │ L6 幂等键校验 + CompensableTool       │
  └─────────────────┘    └──────────────────┘    └─────────────────────────────────────┘
```

### 2.2 L2 身份认证

```java
package com.openjiuwen.runtime.security.auth;

import java.security.Principal;
import java.util.Set;

/**
 * 认证身份。sealed interface 确保只有已知的认证方式。
 *
 * 三种认证方式：
 * 1. mTLS 证书 CN — 传输层自动完成，最安全
 * 2. JWT Token    — 适合 SDK 无法配置证书的场景
 * 3. API Key      — 最简单，适合内部服务间调用
 */
public sealed interface AuthenticatedIdentity extends Principal
    permits MtlsCertificateIdentity, JwtTokenIdentity, ApiKeyIdentity {

    /** 认证方式 */
    AuthMethod method();

    /** 该身份拥有的角色 */
    Set<String> roles();

    /** 该身份可以访问的工具（空 = 不限制，由 L3 权限控制） */
    Set<String> allowedTools();

    /** 租户 ID（多租户隔离） */
    String tenantId();
}

/**
 * mTLS 证书身份。
 * 从客户端证书的 CN（Common Name）和 OU（Organization Unit）提取。
 */
public record MtlsCertificateIdentity(
    String cn,           // 证书 Common Name = 客户端标识
    String ou,           // 证书 Organization Unit = 部门
    String tenantId,
    Set<String> roles,
    Set<String> allowedTools
) implements AuthenticatedIdentity {
    @Override public AuthMethod method() { return AuthMethod.MTLS; }
    @Override public String getName() { return cn; }
}

/**
 * JWT Token 身份。
 */
public record JwtTokenIdentity(
    String subject,      // JWT sub 字段
    String issuer,       // JWT iss 字段
    String tenantId,
    Set<String> roles,
    Set<String> allowedTools
) implements AuthenticatedIdentity {
    @Override public AuthMethod method() { return AuthMethod.JWT; }
    @Override public String getName() { return subject; }
}

/**
 * API Key 身份。
 */
public record ApiKeyIdentity(
    String keyId,        // API Key 标识（不是 Key 本身）
    String tenantId,
    Set<String> roles,
    Set<String> allowedTools
) implements AuthenticatedIdentity {
    @Override public AuthMethod method() { return AuthMethod.API_KEY; }
    @Override public String getName() { return keyId; }
}

public enum AuthMethod { MTLS, JWT, API_KEY }
```

#### L2 认证解析器

```java
package com.openjiuwen.runtime.security.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.security.cert.X509Certificate;
import java.util.Optional;

/**
 * 认证解析器。从请求中提取身份。
 *
 * 优先级：mTLS 证书 > JWT Token > API Key
 */
@Component
public class AuthenticatedIdentityResolver {

    private final AuthProperties authProperties;

    public AuthenticatedIdentityResolver(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /**
     * 从 HTTP 请求中解析身份。
     */
    public Optional<AuthenticatedIdentity> resolve(ServerWebExchange exchange) {
        // 1. 尝试 mTLS 证书
        if (authProperties.mtls().enabled()) {
            Optional<AuthenticatedIdentity> mtls = resolveFromCertificate(exchange);
            if (mtls.isPresent()) return mtls;
        }

        // 2. 尝试 JWT Token
        if (authProperties.jwt().enabled()) {
            Optional<AuthenticatedIdentity> jwt = resolveFromJwt(exchange);
            if (jwt.isPresent()) return jwt;
        }

        // 3. 尝试 API Key
        if (authProperties.apiKey().enabled()) {
            return resolveFromApiKey(exchange);
        }

        return Optional.empty();
    }

    private Optional<AuthenticatedIdentity> resolveFromCertificate(ServerWebExchange exchange) {
        X509Certificate[] certs = (X509Certificate[])
            exchange.getAttribute("javax.servlet.request.X509Certificate");

        if (certs == null || certs.length == 0) return Optional.empty();

        X509Certificate clientCert = certs[0];
        String cn = extractCN(clientCert.getSubjectX500Principal().getName());

        // 从配置中查找该 CN 对应的身份映射
        return authProperties.mtls().identityMapping().entrySet().stream()
            .filter(e -> e.getKey().equals(cn))
            .findFirst()
            .map(e -> new MtlsCertificateIdentity(
                cn,
                e.getValue().ou(),
                e.getValue().tenantId(),
                e.getValue().roles(),
                e.getValue().allowedTools()
            ));
    }

    private Optional<AuthenticatedIdentity> resolveFromJwt(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return Optional.empty();

        String token = authHeader.substring(7);
        // JWT 验证和解析逻辑（使用 Spring Security OAuth2 Resource Server 或 nimbus-jose-jwt）
        // 简化示意
        return Optional.empty(); // 实际实现需要 JWT 解析
    }

    private Optional<AuthenticatedIdentity> resolveFromApiKey(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey == null) return Optional.empty();

        // 从配置中查找 API Key 映射（Key 本身不在配置中，用 hash 匹配）
        return authProperties.apiKey().identityMapping().entrySet().stream()
            .filter(e -> hashMatches(apiKey, e.getKey()))
            .findFirst()
            .map(e -> new ApiKeyIdentity(
                e.getValue().keyId(),
                e.getValue().tenantId(),
                e.getValue().roles(),
                e.getValue().allowedTools()
            ));
    }

    private String extractCN(String dn) {
        // 从 "CN=runtime-client,OU=finance,O=bank" 中提取 CN
        for (String part : dn.split(",")) {
            if (part.trim().startsWith("CN=")) {
                return part.trim().substring(3);
            }
        }
        return dn;
    }

    private boolean hashMatches(String apiKey, String hashedKey) {
        // 用 SHA-256 或 BCrypt 匹配
        return true; // 简化
    }
}
```

### 2.3 L3 权限控制

```java
package com.openjiuwen.runtime.security.authorization;

import com.openjiuwen.runtime.security.auth.AuthenticatedIdentity;

import java.util.Map;
import java.util.Set;

/**
 * 权限检查器。决定一个身份能调什么工具。
 *
 * 两种模式：
 * 1. 白名单模式：allowedTools 非空 → 只能调列表中的工具
 * 2. RBAC 模式：roleToolMapping 定义角色→工具映射
 */
public final class ToolAccessChecker {

    private final Map<String, Set<String>> roleToolMapping;

    public ToolAccessChecker(Map<String, Set<String>> roleToolMapping) {
        this.roleToolMapping = Map.copyOf(roleToolMapping);
    }

    /**
     * 检查身份是否有权调用指定工具。
     *
     * @return AccessDecision（允许/拒绝/需要审批）
     */
    public AccessDecision check(AuthenticatedIdentity identity, String toolName) {
        // 1. 身份级别的工具白名单
        if (!identity.allowedTools().isEmpty()) {
            if (!identity.allowedTools().contains(toolName)
                    && !identity.allowedTools().contains("*")) {
                return AccessDecision.deny(
                    "工具 '" + toolName + "' 不在身份白名单中");
            }
        }

        // 2. RBAC 检查：角色是否有此工具权限
        for (String role : identity.roles()) {
            Set<String> toolsForRole = roleToolMapping.get(role);
            if (toolsForRole != null
                    && (toolsForRole.contains(toolName) || toolsForRole.contains("*"))) {
                return AccessDecision.allow();
            }
        }

        // 3. 无匹配角色
        if (identity.roles().isEmpty()) {
            return AccessDecision.deny("无角色，无法访问任何工具");
        }

        return AccessDecision.deny(
            "角色 " + identity.roles() + " 无权访问工具 '" + toolName + "'");
    }
}

/**
 * 访问决策。
 */
public sealed interface AccessDecision {
    record Allowed() implements AccessDecision {}
    record Denied(String reason) implements AccessDecision {}
    record RequiresApproval(String reason, String approvalGateId) implements AccessDecision {}

    static AccessDecision allow() { return new Allowed(); }
    static AccessDecision deny(String reason) { return new Denied(reason); }
    static AccessDecision needsApproval(String reason, String gateId) {
        return new RequiresApproval(reason, gateId);
    }
}
```

### 2.4 L4 参数校验

```java
package com.openjiuwen.runtime.security.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具参数校验器。
 *
 * 四重校验：
 * 1. 类型校验：参数类型与 JSON Schema 匹配
 * 2. 范围校验：数值/字符串长度在合理范围内
 * 3. 必填校验：required 参数必须存在
 * 4. 敏感字段检测：密码、卡号等不应出现在日志中
 */
public final class ToolParameterValidator {

    private final SensitivityClassifier sensitivityClassifier;

    public ToolParameterValidator(SensitivityClassifier sensitivityClassifier) {
        this.sensitivityClassifier = sensitivityClassifier;
    }

    /**
     * 校验工具调用参数。
     *
     * @return 校验结果（通过/失败/警告）
     */
    public ValidationResult validate(String toolName, Map<String, Object> args,
                                      ParameterSchema schema) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<SensitiveField> sensitiveFields = new ArrayList<>();

        // 1. 必填校验
        for (var required : schema.requiredParams()) {
            if (!args.containsKey(required) || args.get(required) == null) {
                errors.add("必填参数缺失: " + required);
            }
        }

        // 2. 类型校验
        for (var entry : args.entrySet()) {
            String paramName = entry.getKey();
            Object value = entry.getValue();

            var typeSpec = schema.typeOf(paramName);
            if (typeSpec.isPresent()) {
                String error = typeSpec.get().checkType(paramName, value);
                if (error != null) errors.add(error);
            }

            // 3. 敏感字段检测
            if (sensitivityClassifier.isSensitive(paramName, value)) {
                sensitiveFields.add(new SensitiveField(paramName,
                    sensitivityClassifier.classify(paramName)));
                warnings.add("参数 '" + paramName + "' 包含敏感数据");
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings, sensitiveFields);
    }
}

/**
 * 参数 Schema 定义。
 */
public record ParameterSchema(
    Map<String, TypeSpec> paramTypes,
    Set<String> requiredParams
) {
    public java.util.Optional<TypeSpec> typeOf(String paramName) {
        return java.util.Optional.ofNullable(paramTypes.get(paramName));
    }
}

/**
 * 类型规格。
 */
public sealed interface TypeSpec {
    String checkType(String name, Object value);

    record StringType(int minLength, int maxLength, Pattern pattern) implements TypeSpec {
        @Override
        public String checkType(String name, Object value) {
            if (!(value instanceof String s)) return name + ": 期望 string，实际 " + value.getClass().getSimpleName();
            if (s.length() < minLength) return name + ": 长度 " + s.length() + " < 最小 " + minLength;
            if (s.length() > maxLength) return name + ": 长度 " + s.length() + " > 最大 " + maxLength;
            if (pattern != null && !pattern.matcher(s).matches())
                return name + ": 不匹配正则 " + pattern;
            return null;
        }
    }

    record NumberType(double min, double max) implements TypeSpec {
        @Override
        public String checkType(String name, Object value) {
            if (!(value instanceof Number n)) return name + ": 期望 number，实际 " + value.getClass().getSimpleName();
            double d = n.doubleValue();
            if (d < min) return name + ": 值 " + d + " < 最小 " + min;
            if (d > max) return name + ": 值 " + d + " > 最大 " + max;
            return null;
        }
    }

    record EnumType(Set<String> allowedValues) implements TypeSpec {
        @Override
        public String checkType(String name, Object value) {
            if (!allowedValues.contains(value.toString()))
                return name + ": 值 '" + value + "' 不在允许列表 " + allowedValues + " 中";
            return null;
        }
    }
}

/**
 * 校验结果。
 */
public record ValidationResult(
    boolean valid,
    List<String> errors,
    List<String> warnings,
    List<SensitiveField> sensitiveFields
) {}

/**
 * 敏感字段标记。
 */
public record SensitiveField(
    String fieldName,
    SensitivityLevel level
) {}

public enum SensitivityLevel {
    CRITICAL,   // 密码、密钥、Token — 严禁记录
    HIGH,       // 身份证号、银行卡号 — 脱敏后记录
    MEDIUM,     // 手机号、邮箱 — 部分脱敏
    LOW         // 姓名、地址 — 按需脱敏
}
```

### 2.5 L5 操作审计

```java
package com.openjiuwen.runtime.security.audit;

import com.openjiuwen.runtime.security.auth.AuthenticatedIdentity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 安全审计日志。
 *
 * 记录每一次工具调用的完整上下文：
 * - 谁调的（身份）
 * - 调了什么（工具名 + 参数脱敏版）
 * - 结果如何（成功/失败/被拦截）
 * - 耗时多少
 * - 决策推理链（Beta 变体）
 *
 * 审计日志有三个去向：
 * 1. 结构化日志（SLF4J + JSON layout）→ ELK / Loki
 * 2. 数据库表（JDBC）→ 合规审计系统
 * 3. 事件流（Kafka）→ 实时告警
 */
public record SecurityAuditRecord(
    String auditId,                        // 全局唯一审计 ID
    Instant timestamp,                     // 审计时间

    // L2 身份信息
    String authMethod,                     // MTLS / JWT / API_KEY
    String principal,                      // 身份标识
    String tenantId,                       // 租户

    // 调用信息
    String taskId,                         // 任务 ID
    String agentName,                      // Agent 名称
    String toolName,                       // 工具名称
    Map<String, Object> sanitizedArgs,     // 脱敏后的参数
    ToolCallResult result,                 // 调用结果

    // L3 权限检查
    AccessCheckResult accessCheck,         // 权限检查结果

    // L4 参数校验
    ValidationCheckResult validationCheck, // 参数校验结果

    // L6 幂等检查
    IdempotencyCheckResult idempotencyCheck, // 幂等检查结果

    // 耗时
    Duration duration,

    // 安全拦截
    List<String> guardrailViolations,       // 护栏拦截记录
    String safetyBoundaryViolation          // SafetyBoundary 拦截（如果有）
) {}

public record ToolCallResult(
    boolean success,
    String errorType,                       // 参数错误 / 权限拒绝 / 工具执行异常 / 超时
    String errorMessage,
    Map<String, Object> sanitizedResult     // 脱敏后的返回值
) {}

public record AccessCheckResult(
    boolean allowed,
    String reason
) {}

public record ValidationCheckResult(
    boolean valid,
    List<String> errors,
    List<SensitiveField> sensitiveFieldsFound
) {}

public record IdempotencyCheckResult(
    boolean isIdempotent,
    String idempotencyKey,
    boolean wasDuplicate
) {}
```

#### 审计记录器

```java
package com.openjiuwen.runtime.security.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 审计记录器。
 *
 * 三路输出：
 * 1. 结构化日志（必选）— Logger.info JSON
 * 2. 数据库持久化（可选）— JdbcAuditStore
 * 3. 事件广播（可选）— Spring ApplicationEvent → Kafka
 */
@Component
public class SecurityAuditRecorder {

    private static final Logger auditLog = LoggerFactory.getLogger("SECURITY_AUDIT");

    private final ApplicationEventPublisher eventPublisher;
    private final AuditStore auditStore;

    public SecurityAuditRecorder(
            ApplicationEventPublisher eventPublisher,
            AuditStore auditStore) {
        this.eventPublisher = eventPublisher;
        this.auditStore = auditStore;
    }

    /**
     * 记录一次安全审计。
     * 同步写日志 + 异步写数据库 + 广播事件。
     */
    public void record(SecurityAuditRecord record) {
        // 1. 结构化日志（同步，必须成功）
        auditLog.info("SECURITY_AUDIT id={} principal={} tenant={} tool={} result={} duration={}ms",
            record.auditId(),
            record.principal(),
            record.tenantId(),
            record.toolName(),
            record.result().success() ? "SUCCESS" : "FAILED",
            record.duration().toMillis()
        );

        // 2. 数据库持久化（异步，失败不影响主流程）
        try {
            auditStore.storeAsync(record);
        } catch (Exception e) {
            auditLog.error("审计记录持久化失败: auditId={}", record.auditId(), e);
        }

        // 3. 事件广播（Spring Event → Kafka）
        eventPublisher.publishEvent(new SecurityAuditEvent(record));
    }
}

/**
 * 审计存储接口。
 */
public interface AuditStore {
    void storeAsync(SecurityAuditRecord record);
    java.util.List<SecurityAuditRecord> query(AuditQuery query);
}

/**
 * 审计事件。
 */
public record SecurityAuditEvent(SecurityAuditRecord record) {}
```

### 2.6 L6 幂等保障（与 CompensableTool 一起设计，见第三节）

### 2.7 安全检查管道（SecurityPipeline）

将 L2-L6 串联成一个可插拔的安全检查管道：

```java
package com.openjiuwen.runtime.security;

import com.openjiuwen.runtime.security.auth.AuthenticatedIdentity;
import com.openjiuwen.runtime.security.authorization.AccessDecision;
import com.openjiuwen.runtime.security.authorization.ToolAccessChecker;
import com.openjiuwen.runtime.security.audit.SecurityAuditRecord;
import com.openjiuwen.runtime.security.audit.SecurityAuditRecorder;
import com.openjiuwen.runtime.security.validation.ToolParameterValidator;
import com.openjiuwen.runtime.security.validation.ValidationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 安全检查管道。
 *
 * 每次工具调用前，按顺序执行 L2→L3→L4→L6 检查。
 * L1（mTLS）在传输层已完成，不在这个管道中。
 * L5（审计）在调用完成后记录。
 *
 * 设计原则：
 * - Fail-Fast：任何一层失败立即返回，不继续后续检查
 * - 可观测：每一步检查结果都记录到审计日志
 * - 可扩展：新的检查层通过 pipeline.addCheck() 加入
 */
public class SecurityPipeline {

    private final AuthenticatedIdentityResolver identityResolver;
    private final ToolAccessChecker accessChecker;
    private final ToolParameterValidator parameterValidator;
    private final IdempotencyChecker idempotencyChecker;
    private final SecurityAuditRecorder auditRecorder;

    public SecurityCheckResult check(SecurityCheckRequest request) {
        Instant start = Instant.now();
        String auditId = UUID.randomUUID().toString();

        // ===== L2: 身份认证 =====
        var identityOpt = identityResolver.resolve(request.exchange());
        if (identityOpt.isEmpty()) {
            recordAndReturn(auditId, start, null, request, "AUTH_FAILED",
                "未提供有效身份凭证", null, null, null);
            return SecurityCheckResult.denied("未认证");
        }
        AuthenticatedIdentity identity = identityOpt.get();

        // ===== L3: 权限控制 =====
        AccessDecision access = accessChecker.check(identity, request.toolName());
        if (access instanceof AccessDecision.Denied(String reason)) {
            recordAndReturn(auditId, start, identity, request, "ACCESS_DENIED",
                reason, null, null, null);
            return SecurityCheckResult.denied(reason);
        }

        // ===== L4: 参数校验 =====
        ValidationResult validation = parameterValidator.validate(
            request.toolName(), request.args(), request.schema());
        if (!validation.valid()) {
            recordAndReturn(auditId, start, identity, request, "VALIDATION_FAILED",
                String.join("; ", validation.errors()), null, validation, null);
            return SecurityCheckResult.invalid(validation);
        }

        // ===== L6: 幂等检查 =====
        IdempotencyCheckResult idempotency = idempotencyChecker.check(
            request.toolName(), request.args(), request.schema().idempotencyPolicy());
        if (idempotency.wasDuplicate()) {
            recordAndReturn(auditId, start, identity, request, "DUPLICATE",
                "重复请求，幂等键=" + idempotency.idempotencyKey(), null, validation, idempotency);
            return SecurityCheckResult.duplicate(idempotency);
        }

        // ===== 全部通过 =====
        return SecurityCheckResult.approved(identity, validation, idempotency, auditId);
    }

    private void recordAndReturn(String auditId, Instant start,
                                  AuthenticatedIdentity identity,
                                  SecurityCheckRequest request,
                                  String errorType, String errorMessage,
                                  Object accessResult,
                                  ValidationResult validation,
                                  IdempotencyCheckResult idempotency) {
        auditRecorder.record(new SecurityAuditRecord(
            auditId, Instant.now(),
            identity != null ? identity.method().name() : "NONE",
            identity != null ? identity.getName() : "anonymous",
            identity != null ? identity.tenantId() : "unknown",
            request.taskId(), request.agentName(), request.toolName(),
            request.args(),
            new ToolCallResult(false, errorType, errorMessage, null),
            null, null, idempotency,
            Duration.between(start, Instant.now()),
            List.of(), null
        ));
    }
}

/**
 * 安全检查结果。
 */
public sealed interface SecurityCheckResult {
    record Approved(
        AuthenticatedIdentity identity,
        ValidationResult validation,
        IdempotencyCheckResult idempotency,
        String auditId
    ) implements SecurityCheckResult {}

    record Denied(String reason) implements SecurityCheckResult {}
    record Invalid(ValidationResult validation) implements SecurityCheckResult {}
    record Duplicate(IdempotencyCheckResult idempotency) implements SecurityCheckResult {}

    static SecurityCheckResult approved(AuthenticatedIdentity identity,
            ValidationResult validation, IdempotencyCheckResult idempotency,
            String auditId) {
        return new Approved(identity, validation, idempotency, auditId);
    }
    static SecurityCheckResult denied(String reason) { return new Denied(reason); }
    static SecurityCheckResult invalid(ValidationResult v) { return new Invalid(v); }
    static SecurityCheckResult duplicate(IdempotencyCheckResult i) { return new Duplicate(i); }
}
```

---

## 三、CompensableTool 设计

### 3.1 核心抽象

```java
package com.openjiuwen.runtime.security.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Map;

/**
 * 标记工具的幂等特性。
 *
 * 用法：
 * @Idempotent                              — 天然幂等（查询类）
 * @Idempotent(key = "orderId")             — 用参数中的 orderId 做幂等键
 * @Idempotent(key = "#orderId + '_' + #operation")  — SpEL 表达式生成幂等键
 * @Idempotent(compensable = true)          — 非幂等，但可以补偿
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * 幂等键生成策略。
     * 默认 "" = 自动生成（toolName + hash(args)）。
     * 可以用 SpEL 表达式引用参数，如 "#orderId"。
     */
    String key() default "";

    /**
     * 幂等键有效期。
     * 在此时间内，相同的幂等键会被认为是重复请求。
     */
    long ttlSeconds() default 300; // 默认 5 分钟

    /**
     * 是否可补偿。
     * false = 天然幂等（如查询），重复调用无副作用。
     * true  = 非幂等（如转账、退款），但可以执行补偿操作。
     */
    boolean compensable() default false;

    /**
     * 补偿操作的工具名。
     * 仅当 compensable = true 时有效。
     * 调用失败时，Runtime 调用此工具做反向操作。
     */
    String compensateTool() default "";

    /**
     * 最大重试次数。
     * 非幂等工具失败后，重试前需要先补偿。
     */
    int maxRetries() default 1;
}
```

### 3.2 幂等检查器

```java
package com.openjiuwen.runtime.security.idempotency;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等检查器。
 *
 * 两种策略：
 * 1. 天然幂等：不检查，直接放行（查询、只读操作）
 * 2. 幂等键检查：检查相同 key 的请求是否在 TTL 内已执行
 */
public class IdempotencyChecker {

    private final IdempotencyStore store;

    public IdempotencyChecker(IdempotencyStore store) {
        this.store = store;
    }

    /**
     * 检查幂等性。
     */
    public IdempotencyCheckResult check(String toolName, Map<String, Object> args,
                                         IdempotencyPolicy policy) {
        if (policy == null || policy == IdempotencyPolicy.NATURALLY_IDEMPOTENT) {
            // 天然幂等，不需要检查
            return new IdempotencyCheckResult(true, null, false);
        }

        // 生成幂等键
        String idempotencyKey = generateKey(toolName, args, policy.keyExpression());
        Instant expiry = Instant.now().plusSeconds(policy.ttlSeconds());

        // 检查是否已存在
        Optional<IdempotencyRecord> existing = store.get(idempotencyKey);
        if (existing.isPresent()) {
            // 重复请求
            return new IdempotencyCheckResult(true, idempotencyKey, true);
        }

        // 尝试写入（CAS 语义，防并发）
        boolean written = store.putIfAbsent(idempotencyKey, new IdempotencyRecord(
            idempotencyKey, toolName, args, Instant.now(), expiry
        ));

        if (!written) {
            // 并发写入失败 = 另一个请求已经在处理
            return new IdempotencyCheckResult(true, idempotencyKey, true);
        }

        return new IdempotencyCheckResult(true, idempotencyKey, false);
    }

    /**
     * 标记幂等键为已完成。
     */
    public void markCompleted(String idempotencyKey, Object result) {
        store.updateResult(idempotencyKey, result);
    }

    /**
     * 删除幂等键（补偿操作后需要删除，允许重试）。
     */
    public void remove(String idempotencyKey) {
        store.remove(idempotencyKey);
    }

    private String generateKey(String toolName, Map<String, Object> args, String keyExpression) {
        if (keyExpression != null && !keyExpression.isBlank()) {
            // 如果 keyExpression 是 SpEL，解析后生成键
            // 简化实现：直接拼接
            return "idem:" + toolName + ":" + keyExpression + ":" + hashArgs(args);
        }
        return "idem:" + toolName + ":" + hashArgs(args);
    }

    private String hashArgs(Map<String, Object> args) {
        try {
            String serialized = args.toString(); // 简化，实际用 Jackson
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(serialized.getBytes());
            return bytesToHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(args.hashCode());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

/**
 * 幂等策略。
 */
public enum IdempotencyPolicy {
    NATURALLY_IDEMPOTENT,    // 天然幂等（查询、只读）
    KEY_BASED,               // 基于幂等键
    COMPENSABLE              // 可补偿（非幂等，但有回滚路径）
}

/**
 * 幂等存储接口。
 */
public interface IdempotencyStore {
    Optional<IdempotencyRecord> get(String key);
    boolean putIfAbsent(String key, IdempotencyRecord record);
    void updateResult(String key, Object result);
    void remove(String key);
}

// 三种实现：
// 1. InMemoryIdempotencyStore   — 开发环境
// 2. RedisIdempotencyStore      — 生产环境（TTL 原生支持）
// 3. JdbcIdempotencyStore       — 审计需求（持久化查询）
```

### 3.3 补偿操作执行器

```java
package com.openjiuwen.runtime.security.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 补偿操作执行器。
 *
 * 非幂等工具调用失败后的处理策略：
 * 1. 自动补偿：如果有对应的补偿工具，自动调用
 * 2. 人工介入：没有补偿工具或补偿失败，升级到人工
 * 3. 标记悬挂：记录未补偿的操作，等待人工处理
 */
public class CompensationExecutor {

    private static final Logger log = LoggerFactory.getLogger(CompensationExecutor.class);

    private final ToolExecutor toolExecutor;
    private final IdempotencyStore idempotencyStore;
    final CompensationAuditStore compensationAuditStore;

    public CompensationExecutor(ToolExecutor toolExecutor,
                                 IdempotencyStore idempotencyStore,
                                 CompensationAuditStore compensationAuditStore) {
        this.toolExecutor = toolExecutor;
        this.idempotencyStore = idempotencyStore;
        this.compensationAuditStore = compensationAuditStore;
    }

    /**
     * 执行补偿。
     *
     * @param originalTool   原始工具名
     * @param originalArgs   原始参数
     * @param originalResult 原始结果（可能部分成功）
     * @param idempotencyKey 幂等键
     * @param policy         幂等策略
     * @return 补偿结果
     */
    public CompensationResult compensate(String originalTool,
                                          Map<String, Object> originalArgs,
                                          Object originalResult,
                                          String idempotencyKey,
                                          IdempotencyPolicy policy) {
        log.warn("开始补偿: tool={}, key={}", originalTool, idempotencyKey);

        if (policy != IdempotencyPolicy.COMPENSABLE) {
            log.error("非 Compensable 工具调用失败，无法自动补偿: tool={}", originalTool);
            return CompensationResult.manualInterventionRequired(
                originalTool, originalArgs, originalResult, "非 Compensable 工具");
        }

        // 从注解或配置中查找补偿工具
        String compensateTool = resolveCompensateTool(originalTool);
        if (compensateTool == null) {
            log.error("未找到补偿工具: originalTool={}", originalTool);
            return CompensationResult.manualInterventionRequired(
                originalTool, originalArgs, originalResult, "无补偿工具");
        }

        // 生成补偿参数
        Map<String, Object> compensateArgs = buildCompensateArgs(
            originalArgs, originalResult);

        try {
            // 执行补偿
            Object compensateResult = toolExecutor.execute(compensateTool, compensateArgs);

            // 补偿成功，删除幂等键，允许重试
            idempotencyStore.remove(idempotencyKey);

            compensationAuditStore.record(CompensationAuditRecord.success(
                originalTool, compensateTool, originalArgs, compensateArgs,
                idempotencyKey));

            return CompensationResult.success(compensateTool, compensateResult);

        } catch (Exception e) {
            log.error("补偿失败: compensateTool={}, error={}", compensateTool, e.getMessage(), e);

            // 补偿也失败了 → 必须人工介入
            compensationAuditStore.record(CompensationAuditRecord.failed(
                originalTool, compensateTool, originalArgs, compensateArgs,
                idempotencyKey, e.getMessage()));

            return CompensationResult.manualInterventionRequired(
                originalTool, originalArgs, originalResult,
                "补偿工具执行失败: " + e.getMessage());
        }
    }

    private String resolveCompensateTool(String originalTool) {
        // 从 @Idempotent(compensateTool = "refundReverse") 注解中提取
        // 或从配置中查找映射
        return null; // 简化
    }

    private Map<String, Object> buildCompensateArgs(
            Map<String, Object> originalArgs, Object originalResult) {
        // 根据补偿工具的参数需求，从原始参数和结果中构建
        // 通常是反向操作：退款 → 退款冲正
        return Map.of("originalArgs", originalArgs, "originalResult", originalResult);
    }
}

/**
 * 补偿结果。
 */
public sealed interface CompensationResult {
    record Success(String compensateTool, Object result) implements CompensationResult {}
    record ManualInterventionRequired(String originalTool,
                                       Map<String, Object> originalArgs,
                                       Object originalResult,
                                       String reason) implements CompensationResult {}

    static CompensationResult success(String tool, Object result) {
        return new Success(tool, result);
    }
    static CompensationResult manualInterventionRequired(
            String tool, Map<String, Object> args, Object result, String reason) {
        return new ManualInterventionRequired(tool, args, result, reason);
    }
}
```

### 3.4 补偿操作示例

```java
/**
 * 退款工具 — 非幂等，可补偿。
 */
@Tool(description = "执行退款操作")
@Idempotent(
    key = "#orderId",                    // 用订单号做幂等键
    compensable = true,                  // 非幂等，可补偿
    compensateTool = "refundReverse",    // 补偿工具 = 退款冲正
    ttlSeconds = 600,                    // 10 分钟内重复请求会被拦截
    maxRetries = 1                       // 失败后最多重试 1 次（先补偿再重试）
)
public PaymentResult processRefund(
    @Param(description = "订单号") String orderId,
    @Param(description = "退款金额") BigDecimal amount
) {
    return paymentGateway.refund(orderId, amount);
}

/**
 * 退款冲正工具 — 补偿操作。
 */
@Tool(description = "退款冲正（补偿操作）")
public PaymentResult refundReverse(
    @Param(description = "原订单号") String orderId,
    @Param(description = "冲正金额") BigDecimal amount
) {
    return paymentGateway.reverse(orderId, amount);
}

/**
 * 查询订单 — 天然幂等。
 */
@Tool(description = "查询订单状态")
@Idempotent  // 默认：天然幂等，不检查
public OrderStatus queryOrder(
    @Param(description = "订单号") String orderId
) {
    return orderService.getStatus(orderId);
}
```

---

## 四、敏感数据脱敏

### 4.1 脱敏分类器

```java
package com.openjiuwen.runtime.security.sanitization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 敏感数据分类器。
 *
 * 判断一个字段名/值是否包含敏感数据，以及敏感级别。
 *
 * 规则来源：
 * 1. 内置规则：常见的敏感字段名（password, creditCard, idCard, phone, email 等）
 * 2. 配置规则：application.yml 中的自定义规则
 * 3. 正则规则：值级别的内容检测（如 18 位身份证号、16 位银行卡号）
 */
public final class SensitivityClassifier {

    private final List<SensitivityRule> rules;

    public SensitivityClassifier(List<SensitivityRule> rules) {
        // 内置规则优先级最低，用户自定义规则优先级最高
        this.rules = List.copyOf(rules);
    }

    /**
     * 判断字段是否敏感。
     */
    public boolean isSensitive(String fieldName, Object value) {
        return classify(fieldName) != SensitivityLevel.NONE;
    }

    /**
     * 分类字段的敏感级别。
     */
    public SensitivityLevel classify(String fieldName) {
        for (SensitivityRule rule : rules) {
            if (rule.matches(fieldName)) {
                return rule.level();
            }
        }
        return SensitivityLevel.NONE;
    }

    /**
     * 对 Map 做脱敏。
     * 返回脱敏后的新 Map（不修改原始数据）。
     */
    public Map<String, Object> sanitize(Map<String, Object> data) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeValue(entry.getKey(), entry.getValue()));
        }
        return sanitized;
    }

    /**
     * 对单个值做脱敏。
     */
    public Object sanitizeValue(String fieldName, Object value) {
        if (value == null) return null;

        SensitivityLevel level = classify(fieldName);
        if (level == SensitivityLevel.NONE) return value;

        String strValue = value.toString();
        return switch (level) {
            case CRITICAL -> "***REDACTED***";                           // 完全遮蔽
            case HIGH     -> maskMiddle(strValue, 3, 4);                 // 保留前3后4
            case MEDIUM   -> maskMiddle(strValue, 2, 2);                 // 保留前2后2
            case LOW      -> maskMiddle(strValue, 1, 1);                 // 保留前1后1
            case NONE     -> strValue;
        };
    }

    private String maskMiddle(String value, int keepPrefix, int keepSuffix) {
        if (value.length() <= keepPrefix + keepSuffix) {
            return "***";
        }
        String prefix = value.substring(0, keepPrefix);
        String suffix = value.substring(value.length() - keepSuffix);
        int maskLength = value.length() - keepPrefix - keepSuffix;
        return prefix + "*".repeat(Math.max(1, maskLength)) + suffix;
    }
}

/**
 * 脱敏规则。
 */
public record SensitivityRule(
    String name,
    SensitivityLevel level,
    List<Pattern> fieldNamePatterns,    // 字段名匹配
    int priority                         // 优先级（高优先）
) implements Comparable<SensitivityRule> {
    boolean matches(String fieldName) {
        String lower = fieldName.toLowerCase();
        return fieldNamePatterns.stream()
            .anyMatch(p -> p.matcher(lower).find());
    }

    @Override
    public int compareTo(SensitivityRule other) {
        return Integer.compare(other.priority, this.priority); // 降序
    }
}

public enum SensitivityLevel {
    CRITICAL,   // 密码、密钥、Token
    HIGH,       // 身份证号、银行卡号
    MEDIUM,     // 手机号、邮箱
    LOW,        // 姓名、地址
    NONE        // 不敏感
}
```

### 4.2 内置脱敏规则

```java
package com.openjiuwen.runtime.security.sanitization;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内置脱敏规则。
 * 覆盖金融/电力/制造行业常见的敏感数据类型。
 */
public final class BuiltInSensitivityRules {

    private BuiltInSensitivityRules() {}

    public static List<SensitivityRule> defaults() {
        List<SensitivityRule> rules = new ArrayList<>();

        // ===== CRITICAL：完全遮蔽 =====
        rules.add(new SensitivityRule("password", SensitivityLevel.CRITICAL,
            List.of(Pattern.compile("password|passwd|pwd|secret|token|api.?key|private.?key")),
            100));

        // ===== HIGH：保留前3后4 =====
        rules.add(new SensitivityRule("id-card", SensitivityLevel.HIGH,
            List.of(Pattern.compile("id.?card|identity|idcard|sfz|身份证")),
            90));
        rules.add(new SensitivityRule("bank-card", SensitivityLevel.HIGH,
            List.of(Pattern.compile("bank.?card|credit.?card|card.?no|card.?number|银行卡")),
            90));
        rules.add(new SensitivityRule("cvv", SensitivityLevel.CRITICAL,
            List.of(Pattern.compile("cvv|cvc|security.?code")),
            95));

        // ===== MEDIUM：保留前2后2 =====
        rules.add(new SensitivityRule("phone", SensitivityLevel.MEDIUM,
            List.of(Pattern.compile("phone|mobile|tel|cell|手机|电话")),
            80));
        rules.add(new SensitivityRule("email", SensitivityLevel.MEDIUM,
            List.of(Pattern.compile("email|mail|邮箱")),
            80));

        // ===== LOW：保留前1后1 =====
        rules.add(new SensitivityRule("name", SensitivityLevel.LOW,
            List.of(Pattern.compile("^(name|姓名|realname)$")),
            70));
        rules.add(new SensitivityRule("address", SensitivityLevel.LOW,
            List.of(Pattern.compile("address|addr|地址")),
            70));

        return rules;
    }
}
```

### 4.3 脱敏在哪个层面做

```
脱敏执行位置：

    SDK 端（Java 8）                   Runtime 端（Java 21）              审计日志
  ┌─────────────────┐              ┌─────────────────────┐         ┌──────────────┐
  │                 │              │                     │         │              │
  │ ① 返回值预处理   │              │ ② 参数校验时检测     │         │ ③ 日志写入前  │
  │   (可选)        │              │   (L4 必做)         │         │   (L5 必做)   │
  │                 │              │                     │         │              │
  │ SDK 可以在工具   │              │ Runtime 在 L4 参数   │         │ 审计日志永远  │
  │ 返回前做脱敏，   │              │ 校验时标记敏感字段，  │         │ 记录脱敏版。  │
  │ 但不能依赖它     │              │ L5 审计时使用脱敏版。 │         │ 原始值仅在    │
  │ (SDK 不可信)     │              │                     │         │ 数据库中保留， │
  │                 │              │                     │         │ 需权限查询。   │
  └─────────────────┘              └─────────────────────┘         └──────────────┘

结论：
- ① SDK 端脱敏 = 最佳实践，但不强制（SDK 在企业 JVM，企业自己控制）
- ② Runtime 端 = 强制。参数传入后先标记敏感字段，日志用脱敏版
- ③ 审计日志 = 永远脱敏。原始值仅在合规数据库（加密存储）中保留
```

```java
package com.openjiuwen.runtime.security.sanitization;

import java.util.Map;

/**
 * 审计日志脱敏器。
 *
 * 对外输出（日志、事件流、API 响应）永远使用脱敏版。
 * 原始值仅存储在加密的合规数据库中，查询需要权限。
 */
public final class AuditSanitizer {

    private final SensitivityClassifier classifier;
    private final SecretValueEncryptor encryptor;

    public AuditSanitizer(SensitivityClassifier classifier,
                           SecretValueEncryptor encryptor) {
        this.classifier = classifier;
        this.encryptor = encryptor;
    }

    /**
     * 对审计记录做脱敏。
     * 返回 SanitizedAuditData，包含：
     * - sanitized: 脱敏后的数据（用于日志和外部输出）
     * - encrypted: 加密后的原始数据（用于合规数据库）
     */
    public SanitizedAuditData sanitizeForAudit(Map<String, Object> data) {
        Map<String, Object> sanitized = classifier.sanitize(data);

        // 对敏感字段的原始值做加密存储
        Map<String, String> encrypted = new java.util.LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            if (classifier.isSensitive(entry.getKey(), entry.getValue())) {
                encrypted.put(entry.getKey(),
                    encryptor.encrypt(entry.getValue().toString()));
            }
        }

        return new SanitizedAuditData(sanitized, encrypted);
    }
}

public record SanitizedAuditData(
    Map<String, Object> sanitized,   // 脱敏版（日志用）
    Map<String, String> encrypted     // 加密版（数据库用）
) {}
```

---

## 五、与 SafetyBoundary 的集成

### 5.1 新增 Violation 类型

```java
package com.openjiuwen.runtime.kernel;

/**
 * 扩展后的 Violation sealed interface。
 *
 * 原有 5 种 + 新增 6 种安全相关 Violation。
 */
public sealed interface Violation {
    // ===== 原有 Violation（GEPA C1-10 已有）=====
    record ToolNotAllowed(ToolName tool) implements Violation {}
    record BudgetExhausted(long used, long limit) implements Violation {}
    record DangerousOperation(String reason) implements Violation {}
    record RecursionDepthExceeded(int depth, int max) implements Violation {}
    record IterationLimitExceeded(int count, int max) implements Violation {}

    // ===== 新增安全层 Violation =====

    /** L2: 身份认证失败 */
    record AuthenticationFailed(String method, String reason) implements Violation {}

    /** L3: RBAC 权限不足 */
    record AccessDenied(String principal, String tool, Set<String> requiredRoles) implements Violation {}

    /** L4: 参数校验失败 */
    record ParameterValidationFailed(String tool, List<String> errors) implements Violation {}

    /** L4: 检测到敏感数据泄露风险 */
    record SensitiveDataLeakRisk(String field, SensitivityLevel level) implements Violation {}

    /** L6: 幂等键冲突（重复请求） */
    record IdempotencyConflict(String idempotencyKey, String tool) implements Violation {}

    /** L6: 补偿操作失败（需要人工介入） */
    record CompensationFailed(String originalTool, String compensateTool, String error) implements Violation {}
}
```

### 5.2 集成 SafetyBoundary

```java
package com.openjiuwen.runtime.kernel;

import com.openjiuwen.runtime.security.SecurityPipeline;
import com.openjiuwen.runtime.security.SecurityCheckResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 扩展后的 SafetyBoundary 实现。
 *
 * 原有职责不变（Token 预算 / 迭代限制 / 递归深度），
 * 新增安全层集成（L2-L6）。
 */
public class DefaultSafetyBoundary implements SafetyBoundary {

    private final SecurityPipeline securityPipeline;
    private final TokenBudgetManager budgetManager;
    private final IterationTracker iterationTracker;
    private final AuditRecorder auditRecorder;

    public DefaultSafetyBoundary(SecurityPipeline securityPipeline,
                                  TokenBudgetManager budgetManager,
                                  IterationTracker iterationTracker,
                                  AuditRecorder auditRecorder) {
        this.securityPipeline = securityPipeline;
        this.budgetManager = budgetManager;
        this.iterationTracker = iterationTracker;
        this.auditRecorder = auditRecorder;
    }

    @Override
    public Optional<Violation> checkInvoke(TaskId task, ToolName tool, Map<String, Object> args) {
        // ===== 原有检查（不变） =====

        // 1. 工具白名单检查
        // ... (已有逻辑)

        // 2. Token 预算检查
        if (budgetManager.isExhausted(task)) {
            return Optional.of(new Violation.BudgetExhausted(
                budgetManager.used(task), budgetManager.limit(task)));
        }

        // 3. 危险操作检测
        // ... (已有逻辑)

        // ===== 新增安全层检查 =====

        // 4. 安全管道（L2 身份 + L3 权限 + L4 参数 + L6 幂等）
        SecurityCheckResult securityResult = securityPipeline.check(
            new SecurityCheckRequest(task, tool, args));

        return switch (securityResult) {
            case SecurityCheckResult.Approved _ -> Optional.empty(); // 通过
            case SecurityCheckResult.Denied(String reason) ->
                Optional.of(new Violation.AccessDenied("unknown", tool.value(), java.util.Set.of()));
            case SecurityCheckResult.Invalid(var validation) ->
                Optional.of(new Violation.ParameterValidationFailed(tool.value(), validation.errors()));
            case SecurityCheckResult.Duplicate(var idempotency) ->
                Optional.of(new Violation.IdempotencyConflict(idempotency.idempotencyKey(), tool.value()));
        };
    }

    @Override
    public Optional<Violation> checkSpawn(TaskId parent, AgentName child, Budget requested) {
        // 原有逻辑不变
        return Optional.empty();
    }

    @Override
    public void audit(TaskId task, AgentEvent event) {
        auditRecorder.record(task, event);
    }

    @Override
    public boolean isIterationExceeded(TaskId task, int maxIterations) {
        return iterationTracker.count(task) >= maxIterations;
    }

    @Override
    public boolean isBudgetExhausted(TaskId task) {
        return budgetManager.isExhausted(task);
    }
}
```

### 5.3 新增 Guardrail（Beta 变体）

```java
package com.openjiuwen.runtime.strategy.beta;

import com.openjiuwen.runtime.security.auth.AuthenticatedIdentity;
import com.openjiuwen.runtime.security.authorization.AccessDecision;
import com.openjiuwen.runtime.security.authorization.ToolAccessChecker;
import com.openjiuwen.runtime.security.sanitization.SensitivityClassifier;
import com.openjiuwen.runtime.security.validation.ToolParameterValidator;
import com.openjiuwen.runtime.security.validation.ValidationResult;

/**
 * 新增的安全护栏，集成到 Beta 变体的 GuardrailEngine。
 *
 * 原有 5 个护栏（ToolWhitelist / DangerousOperation /
 * SensitiveToolApproval / BudgetExhaustion / MaxDepth）
 * + 新增 3 个安全护栏。
 */
// ===== 新增护栏 1: RBAC 权限护栏 =====

/**
 * RBAC 权限护栏。
 * 在 ToolWhitelistGuardrail 之后检查。
 * 即使工具在白名单中，也需要角色有权限。
 */
public final class RbacGuardrail implements Guardrail {

    private final ToolAccessChecker accessChecker;
    private final AuthenticatedIdentity currentIdentity;

    public RbacGuardrail(ToolAccessChecker accessChecker,
                          AuthenticatedIdentity currentIdentity) {
        this.accessChecker = accessChecker;
        this.currentIdentity = currentIdentity;
    }

    @Override public String name() { return "rbac"; }
    @Override public int priority() { return 99; } // 在 ToolWhitelist(100) 之后

    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        if (decision instanceof ToolCallDecision(_, _, String tool, _)) {
            AccessDecision access = accessChecker.check(currentIdentity, tool);
            if (access instanceof AccessDecision.Denied(String reason)) {
                return new GuardrailResult(false,
                    "RBAC 权限不足: " + reason, ConstraintSeverity.BLOCK);
            }
        }
        return new GuardrailResult(true, null, null);
    }
}

// ===== 新增护栏 2: 参数校验护栏 =====

/**
 * 参数校验护栏。
 * 在 RBAC 护栏之后检查。
 * 检查工具调用参数的类型、范围、必填。
 */
public final class ParameterValidationGuardrail implements Guardrail {

    private final ToolParameterValidator validator;

    public ParameterValidationGuardrail(ToolParameterValidator validator) {
        this.validator = validator;
    }

    @Override public String name() { return "parameter-validation"; }
    @Override public int priority() { return 98; }

    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        if (decision instanceof ToolCallDecision(_, _, String tool, Map<String,Object> args)) {
            ValidationResult result = validator.validate(tool, args,
                resolveSchema(tool)); // 从 ToolRegistry 获取 Schema
            if (!result.valid()) {
                return new GuardrailResult(false,
                    "参数校验失败: " + String.join("; ", result.errors()),
                    ConstraintSeverity.BLOCK);
            }
        }
        return new GuardrailResult(true, null, null);
    }

    private ParameterSchema resolveSchema(String tool) {
        // 从 ToolRegistry 获取工具的参数 Schema
        return null; // 简化
    }
}

// ===== 新增护栏 3: 敏感数据泄露防护护栏 =====

/**
 * 敏感数据泄露防护。
 * 检查工具调用结果中是否包含不应暴露的敏感数据。
 * 例如：LLM 不应通过工具获取密码、密钥等。
 */
public final class SensitiveDataLeakPreventionGuardrail implements Guardrail {

    private final SensitivityClassifier classifier;

    public SensitiveDataLeakPreventionGuardrail(SensitivityClassifier classifier) {
        this.classifier = classifier;
    }

    @Override public String name() { return "sensitive-data-leak-prevention"; }
    @Override public int priority() { return 97; }

    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        // 主要检查 FinalAnswerDecision 中是否包含敏感数据
        if (decision instanceof FinalAnswerDecision(_, _, String answer)) {
            if (containsSensitiveData(answer)) {
                return new GuardrailResult(false,
                    "最终答案包含敏感数据，请脱敏后再输出",
                    ConstraintSeverity.BLOCK);
            }
        }
        return new GuardrailResult(true, null, null);
    }

    private boolean containsSensitiveData(String text) {
        // 检测 18 位身份证号、16/19 位银行卡号、手机号等
        // 使用正则匹配
        return java.util.regex.Pattern.compile("\\d{17}[\\dXx]").matcher(text).find()  // 身份证
            || java.util.regex.Pattern.compile("\\d{16,19}").matcher(text).find();       // 银行卡
    }
}
```

### 5.4 GuardrailEngine 扩展后的完整护栏清单

```
GuardrailEngine 护栏清单（按优先级排序）：

 优先级  护栏名                          类型     来源
 ────── ─────────────────────────────── ──────── ────────
  300    BudgetExhaustionGuardrail       原有      Beta v1
  250    MaxDepthGuardrail               原有      Beta v1
  200    DangerousOperationGuardrail     原有      Beta v1
  150    SensitiveToolApprovalGuardrail  原有      Beta v1
  100    ToolWhitelistGuardrail          原有      Beta v1
   99    RbacGuardrail                   ★新增     安全层
   98    ParameterValidationGuardrail    ★新增     安全层
   97    SensitiveDataLeakPreventionGuardrail ★新增 安全层
```

---

## 六、完整的安全配置示例

### application.yml

```yaml
# ============================================================
# Openjiuwen-Java 安全配置（生产环境）
# ============================================================

server:
  ssl:
    enabled: true
    client-auth: need                              # 强制 mTLS
    key-store: ${TLS_KEYSTORE_PATH}                # /certs/runtime.p12
    key-store-password: ${TLS_KS_PASSWORD}         # 环境变量注入
    key-store-type: PKCS12
    trust-store: ${TLS_TRUSTSTORE_PATH}            # /certs/truststore.p12
    trust-store-password: ${TLS_TS_PASSWORD}       # 环境变量注入
    trust-store-type: PKCS12
    protocol: TLSv1.3
    enabled-protocols: TLSv1.2,TLSv1.3
    ciphers: TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384

openjiuwen:
  security:
    # ===== 总开关 =====
    enabled: true

    # ===== L1: mTLS 配置 =====
    tls:
      enabled: true
      mtls-required: true
      key-store-path: ${TLS_KEYSTORE_PATH}
      key-store-password: ${TLS_KS_PASSWORD}
      trust-store-path: ${TLS_TRUSTSTORE_PATH}
      trust-store-password: ${TLS_TS_PASSWORD}
      certificate-check-interval-seconds: 3600     # 每小时检查证书有效期

    # ===== L2: 身份认证 =====
    auth:
      mtls:
        enabled: true
        identity-mapping:                           # CN → 身份映射
          "sdk-order-service":
            ou: "finance"
            tenant-id: "tenant-finance-001"
            roles: ["order-operator", "refund-operator"]
            allowed-tools: ["queryOrder", "checkRefundRule", "processRefund", "sendNotification"]
          "sdk-risk-service":
            ou: "risk"
            tenant-id: "tenant-finance-001"
            roles: ["risk-analyst"]
            allowed-tools: ["queryOrder", "queryCustomer", "riskAssessment"]
      jwt:
        enabled: true
        jwk-set-uri: ${JWT_JWK_URI}                 # https://auth.internal.bank.com/.well-known/jwks.json
        issuer: https://auth.internal.bank.com
        audience: openjiuwen-runtime
      api-key:
        enabled: true
        identity-mapping:
          "hashed-key-abc123":
            key-id: "internal-monitor"
            tenant-id: "tenant-ops"
            roles: ["monitor"]
            allowed-tools: ["healthCheck", "getMetrics"]

    # ===== L3: 权限控制 =====
    authorization:
      enabled: true
      role-tool-mapping:
        "order-operator":    ["queryOrder", "checkRefundRule", "sendNotification"]
        "refund-operator":   ["queryOrder", "checkRefundRule", "processRefund", "sendNotification", "writeAuditLog"]
        "risk-analyst":      ["queryOrder", "queryCustomer", "riskAssessment"]
        "admin":             ["*"]                    # 管理员所有工具
        "monitor":           ["healthCheck", "getMetrics"]

    # ===== L4: 参数校验 =====
    validation:
      enabled: true
      strict-mode: true                             # 严格模式：未知参数也报错
      max-string-length: 10000                      # 字符串参数最大长度
      max-number-value: 1000000000                  # 数值参数最大值
      max-args-count: 20                            # 单次调用最大参数个数

    # ===== L5: 审计日志 =====
    audit:
      enabled: true
      store: jdbc                                   # jdbc | redis | memory
      async: true                                   # 异步写入数据库
      batch-size: 100                               # 批量写入大小
      flush-interval-ms: 5000                       # 刷新间隔
      retention-days: 365                           # 审计日志保留天数（金融合规要求 5 年）
      sanitize-before-log: true                     # 日志中强制脱敏
      encrypt-original-values: true                 # 原始值加密存储
      encryption-key: ${AUDIT_ENCRYPTION_KEY}       # 加密密钥（HSM / Vault 管理）

    # ===== L6: 幂等保障 =====
    idempotency:
      enabled: true
      store: redis                                  # redis | memory | jdbc
      default-ttl-seconds: 300                      # 默认幂等键有效期 5 分钟
      compensation:
        enabled: true
        max-retries: 1                              # 补偿后最大重试次数
        manual-intervention-webhook: ${COMPENSATION_WEBHOOK_URL}  # 需要人工介入时通知

    # ===== 敏感数据脱敏 =====
    sanitization:
      enabled: true
      built-in-rules: true                          # 启用内置规则
      custom-rules:
        - name: "bank-account-number"
          level: HIGH
          patterns: ["account.?no", "account.?number", "账号"]
          priority: 90
        - name: "social-insurance"
          level: HIGH
          patterns: ["social.?insurance", "社保号"]
          priority: 90
        - name: "power-meter-id"
          level: MEDIUM
          patterns: ["meter.?id", "电表号"]
          priority: 80
```

### 开发环境配置（application-development.yml）

```yaml
server:
  ssl:
    enabled: false                                  # 开发环境不启用 SSL

openjiuwen:
  security:
    enabled: false                                  # 开发环境关闭安全检查
    tls:
      enabled: false
    auth:
      mtls:
        enabled: false
      jwt:
        enabled: false
      api-key:
        enabled: true                               # 简单 API Key 即可
        identity-mapping:
          "dev-key":
            key-id: "developer"
            tenant-id: "dev"
            roles: ["admin"]
            allowed-tools: ["*"]
    authorization:
      enabled: false
    validation:
      enabled: true                                 # 参数校验保留
      strict-mode: false
    audit:
      enabled: true
      store: memory
      sanitize-before-log: false                    # 开发环境不脱敏（方便调试）
    idempotency:
      enabled: false                                # 开发环境关闭幂等检查
    sanitization:
      enabled: false                                # 开发环境关闭脱敏
```

---

## 七、苏格拉底式自我诘问

### 诘问 1：mTLS 在 MCP 场景下是不是过度工程？

**质疑**：SDK 和 Runtime 通常部署在同一个内网甚至同一个 K8s 集群。如果网络本身就是可信的（VPC 隔离），mTLS 的运维成本（证书管理、轮换、故障排查）是否值得？

**回应**：这个质疑有道理但忽略了两个现实：

1. **合规要求是非谈判的**。金融行业（银保监会《银行保险机构数据安全管理指引》）、电力（等保三级）、政务（GB/T 35273）明确要求"传输中数据加密"。不是"值不值得"的问题，是"不做不合规"。

2. **mTLS 不只是加密，还是双向身份认证**。单纯 TLS（服务端证书）只保护数据不被窃听。mTLS 让 Runtime 能确认"谁在调用"——这是 L2 身份认证的基础。没有 mTLS，就只能靠 API Key/JWT，而 API Key 泄露的风险比证书私钥泄露高得多（证书可以吊销，API Key 泄露后很难发现）。

**修正**：但不需要所有场景都强制 mTLS。降级策略是正确的——开发环境不启用，测试环境单向 TLS，生产环境 mTLS。证书管理交给 Vault 或云厂商 KMS，运维成本可控。

### 诘问 2：六层安全检查会不会导致显著的延迟增加？

**质疑**：每次工具调用要经过 L2→L3→L4→L6 四层检查。如果每层都是数据库查询（身份查找、RBAC 查找、幂等检查），工具调用的延迟可能翻倍。工具调用是 Agent 执行的高频操作——一个 Deep Agent 可能调用 50+ 次工具。

**回应**：延迟是真实风险，但可以系统性控制：

1. **L2 身份认证**：mTLS 证书解析是零网络 I/O 的本地操作。JWT 验证是纯 CPU（签名校验）。这两个的延迟 < 1ms。

2. **L3 权限检查**：RBAC 映射是静态配置，加载到内存中。延迟 < 0.1ms。

3. **L4 参数校验**：纯 CPU 操作（正则匹配 + 类型检查）。延迟 < 1ms。

4. **L6 幂等检查**：唯一需要网络 I/O 的层（Redis GET）。延迟 < 1ms（Redis 内网延迟）。

5. **总增加延迟**：~3ms，对比 LLM 推理延迟（500-5000ms），占比 < 0.6%。

**但有一个陷阱**：如果审计日志是同步写数据库，每次工具调用多一个 JDBC INSERT（~5ms）。所以审计日志必须是异步的（批量写入或消息队列）。方案中已设计为异步。

### 诘问 3：CompensableTool 的补偿操作本身失败了怎么办？

**质疑**：退款失败了 → 调 refundReverse 补偿 → refundReverse 也失败了。这比不补偿更糟糕：现在有两个失败的操作需要处理。补偿链如果变成补偿-补偿-补偿的无限递归怎么办？

**回应**：这是分布式系统的经典问题——saga 模式的补偿幂等性。

1. **补偿操作必须是幂等的**。refundReverse 可以被重复调用而不会产生副作用（例如，用状态机确保"已冲正"的记录不会重复冲正）。这是补偿工具开发者必须保证的。

2. **补偿只做一次**。如果补偿失败，不做"补偿的补偿"——直接升级到人工介入。方案中 `CompensationExecutor` 在补偿失败后返回 `ManualInterventionRequired`，不会再递归。

3. **悬挂记录**。所有未完成补偿的操作记录在 `CompensationAuditStore` 中，有定时扫描任务（ScheduledTask）检查悬挂记录，自动告警。

4. **最终一致性 vs 强一致性**。金融场景可能需要强一致性（TCC 模式），但 MCP 工具调用天然是异步的——用最终一致性（Saga + 补偿）是正确的选择。强一致性需要在数据库层做（分布式事务），不是 Agent 框架的职责。

### 诘问 4：敏感数据脱敏的"误杀"问题——脱敏规则太激进会不会影响 LLM 的推理质量？

**质疑**：如果脱敏规则把订单号中间 8 位遮蔽成 `ORD-1234****5678`，LLM 看到的是遮蔽后的值，它可能无法正确推理（比如需要用完整订单号调用下一个工具）。脱敏做得越激进，LLM 的能力越受限。

**回应**：这是一个真实的设计张力——安全 vs 效能。

**核心洞察**：脱敏不是在 LLM 的推理上下文中做，而是在审计日志中做。

```
正确的分层：

1. 工具执行时  → 使用原始值（不脱敏）
2. LLM 推理时 → 使用原始值（不脱敏，LLM 需要完整信息）
3. 审计日志时 → 使用脱敏值（安全合规）
4. API 返回时 → 按需脱敏（取决于调用方的权限）
```

方案中 `SensitiveDataLeakPreventionGuardrail` 检查的是 `FinalAnswerDecision`——即 LLM 给用户的最终输出。LLM 在推理过程中可以使用完整的敏感数据（它本来就需要），但在输出给用户时，如果答案中包含明文身份证号或银行卡号，护栏会拦截。

**误杀的边界**：如果 LLM 的任务就是"查询用户身份证号并告知"，那拦截是正确行为——敏感数据不应通过 LLM 输出通道泄露。正确的做法是：LLM 调用脱敏工具返回脱敏后的值，而不是返回原始值。

### 诘问 5（附加）：SafetyBoundary 已经有了 ToolNotAllowed 和 DangerousOperation，新增的 SecurityPipeline 会不会和它重复检查？

**质疑**：SafetyBoundary.checkInvoke() 已经检查工具白名单，SecurityPipeline 的 L3 也检查。如果两处都检查同一个工具调用，性能浪费且逻辑可能不一致。

**回应**：这确实是重复，需要明确职责边界。

**修正方案**：SafetyBoundary 不再自己做 ToolNotAllowed 检查——委托给 SecurityPipeline。SafetyBoundary 只保留"安全层不管的"检查（Token 预算、迭代限制、递归深度）。安全相关的检查（L2-L6）全部走 SecurityPipeline，SafetyBoundary 只是调用入口。

```
SafetyBoundary.checkInvoke():
  1. 调用 SecurityPipeline.check()  → L2/L3/L4/L6
  2. 检查 Token 预算                → BudgetExhausted
  3. 检查迭代次数                   → IterationLimitExceeded
  4. 检查递归深度                   → RecursionDepthExceeded

SafetyBoundary 不再直接做：
  - ToolNotAllowed  → 委托给 L3 AccessChecker
  - DangerousOperation → 委托给 L4 ParameterValidator
```

这样避免了重复检查，职责也清晰了：SafetyBoundary = 安全层入口 + 非 L2-L6 的底层保护。

---

## 八、安全层模块结构与实现优先级

### 模块结构

```
openjiuwen-runtime/
└── runtime-security/
    ├── src/main/java/com/openjiuwen/runtime/security/
    │   │
    │   ├── transport/                    ← L1: mTLS
    │   │   ├── RuntimeTlsConfiguration.java
    │   │   ├── TlsProperties.java
    │   │   ├── McpTlsWebClientFactory.java
    │   │   └── CertificateHealthMonitor.java
    │   │
    │   ├── auth/                         ← L2: 身份认证
    │   │   ├── AuthenticatedIdentity.java       (sealed interface)
    │   │   ├── MtlsCertificateIdentity.java     (record)
    │   │   ├── JwtTokenIdentity.java            (record)
    │   │   ├── ApiKeyIdentity.java              (record)
    │   │   ├── AuthenticatedIdentityResolver.java
    │   │   └── AuthProperties.java
    │   │
    │   ├── authorization/                ← L3: 权限控制
    │   │   ├── ToolAccessChecker.java
    │   │   └── AccessDecision.java              (sealed interface)
    │   │
    │   ├── validation/                   ← L4: 参数校验
    │   │   ├── ToolParameterValidator.java
    │   │   ├── ParameterSchema.java
    │   │   ├── TypeSpec.java                    (sealed interface)
    │   │   └── ValidationResult.java
    │   │
    │   ├── audit/                        ← L5: 操作审计
    │   │   ├── SecurityAuditRecord.java
    │   │   ├── SecurityAuditRecorder.java
    │   │   ├── AuditStore.java                  (interface)
    │   │   ├── InMemoryAuditStore.java
    │   │   ├── JdbcAuditStore.java
    │   │   └── SecurityAuditEvent.java
    │   │
    │   ├── idempotency/                  ← L6: 幂等保障
    │   │   ├── Idempotent.java                  (annotation)
    │   │   ├── IdempotencyChecker.java
    │   │   ├── IdempotencyStore.java            (interface)
    │   │   ├── InMemoryIdempotencyStore.java
    │   │   ├── RedisIdempotencyStore.java
    │   │   ├── CompensationExecutor.java
    │   │   └── CompensationResult.java          (sealed interface)
    │   │
    │   ├── sanitization/                 ← 敏感数据脱敏
    │   │   ├── SensitivityClassifier.java
    │   │   ├── SensitivityRule.java
    │   │   ├── SensitivityLevel.java
    │   │   ├── BuiltInSensitivityRules.java
    │   │   ├── AuditSanitizer.java
    │   │   └── SecretValueEncryptor.java
    │   │
    │   ├── SecurityPipeline.java         ← 安全检查管道
    │   ├── SecurityCheckRequest.java
    │   └── SecurityCheckResult.java             (sealed interface)
    │
    └── src/main/java/com/openjiuwen/runtime/strategy/beta/
        ├── RbacGuardrail.java                    ← 新增护栏
        ├── ParameterValidationGuardrail.java     ← 新增护栏
        └── SensitiveDataLeakPreventionGuardrail.java ← 新增护栏

openjiuwen-sdk-remote/
└── src/main/java/com/openjiuwen/sdk/security/
    ├── SdkTlsFactory.java                       ← SDK 端 mTLS（Java 8）
    └── SdkTlsProperties.java
```

### 实现优先级

| 优先级 | 安全层 | 工作量 | 理由 |
|--------|--------|--------|------|
| **P1-A** | L1 mTLS（Runtime + SDK） | 3 人天 | W4 核心要求，传输加密是基线 |
| **P1-B** | L2 身份认证（mTLS CN + API Key） | 2 人天 | mTLS 已有证书，提取 CN 即可 |
| **P1-C** | L4 参数校验（类型+必填） | 2 人天 | 防止 LLM 幻觉导致非法参数 |
| **P1-D** | L5 审计日志（结构化日志） | 2 人天 | 企业合规必需，最先做日志 |
| **P2-A** | L3 RBAC 权限 | 2 人天 | 多租户场景必需 |
| **P2-B** | L6 幂等 + 补偿 | 3 人天 | 金融退款场景刚需 |
| **P2-C** | 敏感数据脱敏 | 2 人天 | 审计日志配合 |
| **P2-D** | JWT 认证 | 1 人天 | 不使用 mTLS 的场景备选 |
| **P3** | SafetyBoundary 集成 | 2 人天 | 重构 checkInvoke 职责 |

---

## 九、风险扫描

| # | 风险 | 级别 | 缓解 |
|---|------|------|------|
| SR1 | Java 8 SDK 的 SSLContext 不支持 TLSv1.3 | **LOW** | Java 8u261+ 支持 TLSv1.3；旧版本降级到 TLSv1.2 |
| SR2 | mTLS 证书过期导致全部调用失败 | **HIGH** | CertificateHealthMonitor 提前 30 天告警 + Vault 自动轮换 |
| SR3 | 审计日志量过大（每秒 1000+ 工具调用） | **MEDIUM** | 异步批量写入 + Kafka 分流 + 日志采样 |
| SR4 | 补偿操作失败导致悬挂事务 | **MEDIUM** | 悬挂记录扫描 + 自动告警 + 人工处理 SLA |
| SR5 | 脱敏规则误杀导致 LLM 推理质量下降 | **LOW** | 脱敏仅在审计日志层，LLM 推理用原始值 |
| SR6 | RBAC 配置错误导致合法工具被拒绝 | **MEDIUM** | dev 模式下详细错误信息 + 审计日志记录被拒绝原因 |

---

_最后更新：2026-06-07_
_设计者：背包（Claude Code）_
_模型：glm-5.1_
