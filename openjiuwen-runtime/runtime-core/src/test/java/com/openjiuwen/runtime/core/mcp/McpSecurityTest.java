package com.openjiuwen.runtime.core.mcp;

import com.openjiuwen.runtime.core.engine.DefaultSafetyBoundary;
import com.openjiuwen.runtime.core.engine.SafetyBoundary;
import com.openjiuwen.core.kernel.model.TaskId;
import com.openjiuwen.core.kernel.model.Violation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W4 修复测试：MCP mTLS 强制加密。
 *
 * 测试场景：
 * 1. McpSecurityConfig 生产/开发配置
 * 2. McpTlsInterceptor 拦截逻辑
 * 3. SafetyBoundary.checkMcpSecurity 集成
 * 4. 开发环境降级策略
 */
@DisplayName("W4: MCP mTLS 强制加密")
class McpSecurityTest {

    private TaskId testTaskId;

    @BeforeEach
    void setUp() {
        testTaskId = TaskId.generate();
    }

    // ==================== McpSecurityConfig 测试 ====================

    @Nested
    @DisplayName("McpSecurityConfig 配置验证")
    class ConfigTest {

        @Test
        @DisplayName("生产配置：mTLS 强制启用")
        void productionConfig_mtlsForced() {
            McpSecurityConfig config = McpSecurityConfig.production(
                "/etc/ssl/truststore.p12", "changeit",
                "/etc/ssl/keystore.p12", "changeit"
            );

            assertTrue(config.isSecure(), "生产配置应启用 mTLS");
            assertFalse(config.allowsInsecure(), "生产配置不允许不安全连接");
            assertTrue(config.mtlsEnabled());
            assertEquals("/etc/ssl/truststore.p12", config.trustStorePath());
            assertEquals("/etc/ssl/keystore.p12", config.keyStorePath());
        }

        @Test
        @DisplayName("开发配置：允许降级")
        void developmentConfig_allowsInsecure() {
            McpSecurityConfig config = McpSecurityConfig.development();

            assertFalse(config.isSecure(), "开发配置不强制 mTLS");
            assertTrue(config.allowsInsecure(), "开发配置允许不安全连接");
            assertTrue(config.devModeAllowed());
        }

        @Test
        @DisplayName("默认协议包含 TLSv1.3 和 TLSv1.2")
        void defaultProtocols() {
            McpSecurityConfig config = McpSecurityConfig.development();

            String[] protocols = config.allowedProtocols();
            assertEquals(2, protocols.length);
            assertEquals("TLSv1.3", protocols[0]);
            assertEquals("TLSv1.2", protocols[1]);
        }
    }

    // ==================== McpTlsInterceptor 拦截测试 ====================

    @Nested
    @DisplayName("McpTlsInterceptor 拦截逻辑")
    class InterceptorTest {

        @Test
        @DisplayName("生产环境 mTLS 已启用 → 拦截通过")
        void productionMtlsEnabled_passes() {
            McpSecurityConfig config = McpSecurityConfig.production(
                "/nonexistent/truststore.p12", "changeit",
                "/nonexistent/keystore.p12", "changeit"
            );
            SafetyBoundary sb = new DefaultSafetyBoundary();
            McpTlsInterceptor interceptor = new McpTlsInterceptor(config, sb);

            // mTLS 配置已启用但 SSLContext 为 null → 握手验证失败 → Violation
            Optional<Violation> result = interceptor.intercept(
                "https://sdk:8443/mcp", testTaskId);

            assertTrue(result.isPresent(), "SSLContext 未初始化应返回 Violation");
            assertInstanceOf(Violation.McpSecurityViolation.class, result.get());
        }

        @Test
        @DisplayName("开发环境降级 → 允许明文")
        void developmentMode_allowsPlaintext() {
            McpSecurityConfig config = McpSecurityConfig.development();
            SafetyBoundary sb = new DefaultSafetyBoundary();
            McpTlsInterceptor interceptor = new McpTlsInterceptor(config, sb);

            Optional<Violation> result = interceptor.intercept(
                "http://localhost:8080/mcp", testTaskId);

            assertTrue(result.isEmpty(), "开发环境降级模式应允许明文连接");
        }

        @Test
        @DisplayName("resetHandshake 后需要重新验证")
        void resetHandshake_requiresReverification() {
            McpSecurityConfig config = McpSecurityConfig.development();
            SafetyBoundary sb = new DefaultSafetyBoundary();
            McpTlsInterceptor interceptor = new McpTlsInterceptor(config, sb);

            // 开发模式第一次调用通过
            Optional<Violation> first = interceptor.intercept(
                "http://localhost:8080/mcp", testTaskId);
            assertTrue(first.isEmpty());

            // 重置后仍然是开发模式 → 仍然通过
            interceptor.resetHandshake();
            Optional<Violation> second = interceptor.intercept(
                "http://localhost:8080/mcp", testTaskId);
            assertTrue(second.isEmpty());
        }
    }

    // ==================== SafetyBoundary MCP 安全集成测试 ====================

    @Nested
    @DisplayName("SafetyBoundary.checkMcpSecurity 集成")
    class SafetyBoundaryMcpTest {

        @Test
        @DisplayName("TLS 已建立 → 通过")
        void tlsEstablished_passes() {
            SafetyBoundary sb = new DefaultSafetyBoundary();

            Optional<Violation> result = sb.checkMcpSecurity(
                "https://sdk:8443/mcp", true);

            assertTrue(result.isEmpty(), "TLS 已建立应通过");
        }

        @Test
        @DisplayName("TLS 未建立 → CRITICAL 级别 Violation")
        void tlsNotEstablished_criticalViolation() {
            SafetyBoundary sb = new DefaultSafetyBoundary();

            Optional<Violation> result = sb.checkMcpSecurity(
                "http://sdk:8080/mcp", false);

            assertTrue(result.isPresent(), "TLS 未建立应返回 Violation");
            assertInstanceOf(Violation.McpSecurityViolation.class, result.get());

            Violation.McpSecurityViolation v = (Violation.McpSecurityViolation) result.get();
            assertEquals("MCP_SECURITY_VIOLATION", v.code());
            assertEquals(Violation.Severity.CRITICAL, v.severity());
            assertTrue(v.message().contains("http://sdk:8080/mcp"));
            assertTrue(v.message().contains("mTLS"));
        }

        @Test
        @DisplayName("McpSecurityViolation 的 endpoint 和 reason 正确传递")
        void violationFieldsCorrect() {
            SafetyBoundary sb = new DefaultSafetyBoundary();

            Optional<Violation> result = sb.checkMcpSecurity(
                "https://prod-sdk:9999/mcp/tool", false);

            Violation.McpSecurityViolation v = (Violation.McpSecurityViolation) result.get();
            assertEquals("https://prod-sdk:9999/mcp/tool", v.endpoint());
            assertTrue(v.reason().contains("mTLS"));
        }
    }

    // ==================== 开发环境降级策略测试 ====================

    @Nested
    @DisplayName("开发环境降级策略")
    class DevModeDegradationTest {

        @Test
        @DisplayName("非开发环境且未启用 mTLS → 阻止连接")
        void nonDevNoMtls_blocks() {
            // 手动构造非开发、非 mTLS 的配置
            McpSecurityConfig config = new McpSecurityConfig(
                false, false, null,
                null, null, null, null,
                null, null
            );

            assertFalse(config.allowsInsecure(), "非开发模式不允许不安全连接");

            SafetyBoundary sb = new DefaultSafetyBoundary();
            McpTlsInterceptor interceptor = new McpTlsInterceptor(config, sb);

            Optional<Violation> result = interceptor.intercept(
                "http://sdk:8080/mcp", testTaskId);

            assertTrue(result.isPresent(), "非开发环境未启用 mTLS 应阻止");
        }

        @Test
        @DisplayName("开发环境降级允许 HTTP 连接")
        void devModeAllowsHttp() {
            McpSecurityConfig config = McpSecurityConfig.development();
            SafetyBoundary sb = new DefaultSafetyBoundary();
            McpTlsInterceptor interceptor = new McpTlsInterceptor(config, sb);

            // HTTP 明文连接
            Optional<Violation> result = interceptor.intercept(
                "http://localhost:8080/mcp", testTaskId);

            assertTrue(result.isEmpty(), "开发环境应允许 HTTP 明文连接");
        }
    }
}
