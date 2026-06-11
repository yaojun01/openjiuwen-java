package com.openjiuwen.runtime.spring;

import com.openjiuwen.runtime.core.dispatch.AgentRegistry;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.runtime.core.engine.SafetyBoundary;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Actuator 健康检查自动配置（独立注册，避免 actuator 不在 classpath 时加载失败）。
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenjiuwenProperties.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
@ConditionalOnProperty(prefix = "openjiuwen", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentKernelHealthAutoConfiguration {

    /**
     * 返回类型用 Object 而非 AgentKernelHealthIndicator，
     * 避免 getDeclaredMethods 在无 actuator 时触发 NoClassDefFoundError。
     * Actuator 通过实例类型检测 HealthIndicator，不受返回类型影响。
     */
    @Bean
    @ConditionalOnMissingBean(name = "agentKernelHealthIndicator")
    public Object agentKernelHealthIndicator(
            AgentKernel kernel,
            AgentRegistry registry,
            SafetyBoundary safetyBoundary,
            OpenjiuwenProperties properties) {
        return new AgentKernelHealthIndicator(kernel, registry, safetyBoundary, properties);
    }
}
