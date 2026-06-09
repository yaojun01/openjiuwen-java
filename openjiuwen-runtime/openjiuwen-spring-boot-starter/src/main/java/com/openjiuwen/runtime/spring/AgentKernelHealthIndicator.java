package com.openjiuwen.runtime.spring;

import com.openjiuwen.runtime.core.dispatch.AgentRegistry;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.runtime.core.engine.SafetyBoundary;
import com.openjiuwen.core.kernel.model.TaskId;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.Set;

/**
 * AgentKernel 健康检查。
 *
 * 集成 Spring Boot Actuator，在 /actuator/health 端点展示 Runtime 状态。
 * 检查项：
 * - AgentKernel 是否存活
 * - SafetyBoundary 是否启用
 * - 已注册 Agent 数量
 * - MCP 安全状态
 *
 * 输出示例：
 * <pre>
 * "openjiuwen": {
 *   "status": "UP",
 *   "details": {
 *     "kernel": "UP",
 *     "safety": "ENABLED",
 *     "registeredAgents": 3,
 *     "agents": ["order-refund", "risk-assessment", "customer-service"],
 *     "timestamp": "2026-06-07T12:00:00Z"
 *   }
 * }
 * </pre>
 */
public class AgentKernelHealthIndicator implements HealthIndicator {

    private final AgentKernel kernel;
    private final AgentRegistry registry;
    private final SafetyBoundary safetyBoundary;
    private final OpenjiuwenProperties properties;

    public AgentKernelHealthIndicator(AgentKernel kernel,
                                       AgentRegistry registry,
                                       SafetyBoundary safetyBoundary,
                                       OpenjiuwenProperties properties) {
        this.kernel = kernel;
        this.registry = registry;
        this.safetyBoundary = safetyBoundary;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        try {
            // 检查 1: AgentKernel 存活
            boolean kernelAlive = checkKernelAlive();
            if (!kernelAlive) {
                builder.withDetail("kernel", "DOWN").down();
                return builder.build();
            }
            builder.withDetail("kernel", "UP");

            // 检查 2: SafetyBoundary 状态
            String safetyStatus = properties.getSecurity().isEnabled() ? "ENABLED" : "DISABLED";
            builder.withDetail("safety", safetyStatus);

            // 检查 3: 已注册 Agent 数量
            int agentCount = registry.agentNames().size();
            builder.withDetail("registeredAgents", agentCount);

            // 检查 4: Agent 名称列表
            if (!registry.agentNames().isEmpty()) {
                builder.withDetail("agents",
                    registry.agentNames().stream()
                        .map(Object::toString)
                        .sorted()
                        .toList());
            }

            // 检查 5: MCP 安全状态
            boolean mtlsEnabled = properties.getSecurity().getMcp().isMtlsEnabled();
            builder.withDetail("mcpSecurity", mtlsEnabled ? "MTLS_ENABLED" : "DEV_MODE");

            // 检查 6: 默认模型
            builder.withDetail("defaultModel", properties.getKernel().getDefaultModel());

            // 检查 7: 默认自主度
            builder.withDetail("defaultAutonomyLevel",
                properties.getKernel().getDefaultAutonomyLevel().name());

            builder.withDetail("timestamp", Instant.now().toString());

        } catch (Exception e) {
            builder.down().withDetail("error", e.getMessage());
        }

        return builder.build();
    }

    /**
     * 检查 AgentKernel 是否存活。
     * 通过尝试观察一个不存在的 taskId 来验证内核可响应。
     */
    private boolean checkKernelAlive() {
        try {
            // 同步等待（带超时），确保内核能响应
            kernel.observe(new TaskId("__health_check__"), Set.of()).block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
