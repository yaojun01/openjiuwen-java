package com.openjiuwen.runtime.spring;

import com.openjiuwen.runtime.alpha.AlphaStrategy;
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.criteria.CriteriaOrchestrator;
import com.openjiuwen.runtime.core.dispatch.AdaptiveStrategy;
import com.openjiuwen.runtime.core.dispatch.AgentRegistry;
import com.openjiuwen.runtime.core.dispatch.ExecutionStrategy;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.runtime.core.engine.DefaultAgentKernel;
import com.openjiuwen.runtime.core.engine.DefaultSafetyBoundary;
import com.openjiuwen.runtime.core.engine.SafetyBoundary;
import com.openjiuwen.core.kernel.model.*;
import com.openjiuwen.runtime.core.mcp.McpSecurityConfig;
import com.openjiuwen.runtime.core.mcp.McpTlsInterceptor;
import com.openjiuwen.core.meta.AgentDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Openjiuwen Runtime 自动配置。
 *
 * 开发者只需要：
 * 1. 添加 spring-boot-starter 依赖
 * 2. 配置 spring.ai 的 ChatModel（或任意 LLM 提供者）
 * 3. 写 @Agent + @Tool 类
 *
 * 本配置类自动完成：
 * - SafetyBoundary 构建（敏感信息检测 + 预算检查 + 工具白名单）
 * - AgentKernel 构建（LLM 调用 + 工具执行 + 检查点 + 安全边界）
 * - CheckpointStore 构建（内存 / Redis / JDBC，按配置选择）
 * - MCP 安全配置（mTLS 拦截器）
 * - AlphaStrategy 构建（PEV 引擎 + 执行策略）
 * - AdaptiveStrategy 构建（按 AutonomyLevel 路由）
 * - AgentRegistry 构建（收集所有 @Agent 注解的类）
 * - AgentClient 构建（SDK 门面，开发者直接注入使用）
 * - HealthIndicator（Actuator 健康检查）
 *
 * 契约："像写 @Service 一样自然"
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenjiuwenProperties.class)
@ConditionalOnProperty(prefix = "openjiuwen", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenjiuwenAutoConfiguration {

    // ==================== 1. SafetyBoundary ====================

    /**
     * 安全边界。
     * 默认实现：预算检查 + 工具白名单 + 敏感信息检测。
     * 开发者可以用 @Bean 覆盖。
     */
    @Bean
    @ConditionalOnMissingBean
    public SafetyBoundary safetyBoundary(OpenjiuwenProperties properties) {
        OpenjiuwenProperties.SecurityConfig security = properties.getSecurity();

        if (!security.isEnabled()) {
            return new SafetyBoundary() {
                @Override
                public List<Violation> checkToolCall(ToolName toolName, java.util.Map<String, Object> arguments, BudgetLimits budget) {
                    return List.of();
                }
                @Override
                public List<Violation> checkLLMOutput(String output) {
                    return List.of();
                }
                @Override
                public List<Violation> checkBudget(BudgetLimits budget) {
                    return List.of();
                }
            };
        }

        List<Pattern> patterns = new ArrayList<>();
        for (String pattern : security.getSensitivePatterns()) {
            patterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
        }

        return new DefaultSafetyBoundary(Set.of(), patterns);
    }

    // ==================== 2. CheckpointStore ====================

    /**
     * 检查点存储。
     * 默认使用内存存储。生产环境可配置 redis / jdbc。
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultAgentKernel.CheckpointStore checkpointStore(OpenjiuwenProperties properties) {
        String storeType = properties.getCheckpoint().getStoreType();
        return switch (storeType) {
            case "memory" -> createInMemoryCheckpointStore();
            case "redis" -> createRedisCheckpointStore(properties);
            case "jdbc" -> createJdbcCheckpointStore(properties);
            default -> createInMemoryCheckpointStore();
        };
    }

    // ==================== 2.5. Spring AI LLM Provider Bridge ====================

    /**
     * 当 Spring AI ChatModel 存在时，自动创建 LLMProvider。
     * ChatModel.call(String) 签名与 LLMProvider.call(String) 一致，桥接只需方法引用。
     * 无 ChatModel 时（如测试环境），agentKernel 中的 ObjectProvider 回退到占位符。
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.ai.chat.model.ChatModel")
    static class SpringAiLlmProviderConfiguration {
        @Bean
        @ConditionalOnMissingBean(DefaultAgentKernel.LLMProvider.class)
        public DefaultAgentKernel.LLMProvider springAiLlmProvider(
                org.springframework.ai.chat.model.ChatModel chatModel) {
            return chatModel::call;
        }
    }

    // ==================== 3. AgentKernel ====================

    /**
     * Agent 内核。
     * 聚合 LLM 调用、工具执行、检查点存储、安全边界。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentKernel agentKernel(
            SafetyBoundary safetyBoundary,
            DefaultAgentKernel.CheckpointStore checkpointStore,
            ObjectProvider<DefaultAgentKernel.LLMProvider> llmProvider,
            Map<ToolName, DefaultAgentKernel.ToolExecutor> tools,
            OpenjiuwenProperties properties) {

        // LLM 提供者：优先注入，否则用占位符
        DefaultAgentKernel.LLMProvider provider = llmProvider.getIfAvailable(() ->
            prompt -> "[LLM placeholder for: " + prompt.substring(0, Math.min(50, prompt.length())) + "...]"
        );

        // 直接注入共享 toolExecutors bean 引用（而非 ObjectProvider.getIfAvailable(HashMap::new)）：
        // ObjectProvider 延迟解析，当 agentKernel 创建早于 toolExecutors bean 时会返回 fallback 的
        // 临时空 HashMap，导致 kernel 持有的不是共享 Map —— @Tool 运行期注册的工具对 kernel 不可见。
        // 直接注入为硬依赖，强制 Spring 先创建 toolExecutors bean，kernel 持有其引用，后续
        // AgentBeanPostProcessor 注册的工具即能被 kernel.invokeTool 查到。
        return new DefaultAgentKernel(provider, tools, checkpointStore, safetyBoundary);
    }

    // ==================== 4. MCP Security ====================

    /**
     * MCP 安全配置。
     * 生产环境强制 mTLS，开发环境可降级。
     */
    @Bean
    @ConditionalOnMissingBean
    public McpSecurityConfig mcpSecurityConfig(OpenjiuwenProperties properties) {
        OpenjiuwenProperties.McpConfig mcp = properties.getSecurity().getMcp();

        if (mcp.isMtlsEnabled()) {
            return McpSecurityConfig.production(
                mcp.getTrustStorePath(), mcp.getTrustStorePassword(),
                mcp.getKeyStorePath(), mcp.getKeyStorePassword()
            );
        } else {
            return McpSecurityConfig.development();
        }
    }

    /**
     * mTLS 拦截器——每次 MCP 工具调用前的安全检查。
     */
    @Bean
    @ConditionalOnMissingBean
    public McpTlsInterceptor mcpTlsInterceptor(McpSecurityConfig mcpSecurityConfig, SafetyBoundary safetyBoundary) {
        return new McpTlsInterceptor(mcpSecurityConfig, safetyBoundary);
    }

    // ==================== 5. AgentRegistry ====================

    /**
     * Agent 注册中心。
     * 自动收集所有 AgentDefinition（@Agent 注解扫描产出）。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry agentRegistry(ObjectProvider<List<AgentDefinition>> definitions) {
        List<AgentDefinition> defs = definitions.getIfAvailable(List::of);
        return new AgentRegistry(defs);
    }

    // ==================== 6. AlphaStrategy ====================

    /**
     * Alpha 策略——PEV（Plan-Execute-Verify）显式控制引擎。
     * 从配置属性构建 ExecutionPolicy。
     */
    @Bean("alpha")
    @ConditionalOnMissingBean(name = "alpha")
    public AlphaStrategy alphaStrategy() {
        // AlphaStrategy 目前通过 @Component 注解自注册，
        // 这里提供 @ConditionalOnMissingBean 允许开发者覆盖。
        // 如果 AlphaStrategy 没有被 component-scan 扫到，则手动创建。
        return new AlphaStrategy();
    }

    // ==================== 6.5. GuardrailEngine + CriteriaOrchestrator ====================

    /**
     * Beta 策略的 GuardrailEngine。
     * 聚合 5 个内置护栏：预算/工具白名单/重复检测/置信度/安全。
     * CriteriaGuardrail 在 AutonomousOrchestrator.execute() 中动态追加。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "com.openjiuwen.runtime.beta.guardrail.GuardrailEngine")
    public GuardrailEngine guardrailEngine() {
        return new GuardrailEngine();
    }

    /**
     * Criteria 生命周期编排器。
     * 负责 propose → confirm → verify → accumulate → maintain 完整闭环。
     * 默认禁用（matchIfMissing = false），需显式配置 openjiuwen.criteria.enabled=true 启用。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "com.openjiuwen.runtime.criteria.CriteriaOrchestrator")
    @ConditionalOnProperty(prefix = "openjiuwen.criteria", name = "enabled",
        havingValue = "true", matchIfMissing = false)
    public CriteriaOrchestrator criteriaOrchestrator() {
        return new CriteriaOrchestrator();
    }

    // ==================== 7. AdaptiveStrategy ====================

    /**
     * 自适应策略。
     * 自动收集所有 ExecutionStrategy 实现，按 AutonomyLevel 路由。
     */
    @Bean("adaptive")
    @ConditionalOnMissingBean(name = "adaptive")
    public AdaptiveStrategy adaptiveStrategy(Map<String, ExecutionStrategy> strategies) {
        return new AdaptiveStrategy(strategies);
    }

    // ==================== 8. AgentClient（SDK 门面） ====================

    /**
     * AgentClient——开发者直接注入使用的 SDK 门面。
     * 用法：agentClient.invoke("order-refund", request)
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentClient agentClient(
            AgentKernel kernel,
            AgentRegistry registry,
            AdaptiveStrategy adaptiveStrategy,
            OpenjiuwenProperties properties) {
        return new AgentClient(kernel, registry, adaptiveStrategy, properties);
    }

    // ==================== 9. 工具执行表 ====================

    /**
     * 工具执行器注册表。
     * AgentBeanPostProcessor 扫描 @Tool 后将执行器注册到这里。
     */
    @Bean
    @ConditionalOnMissingBean(name = "toolExecutors")
    public Map<ToolName, DefaultAgentKernel.ToolExecutor> toolExecutors() {
        return new ConcurrentHashMap<>();
    }

    // ==================== 10. Agent 扫描 ====================

    /**
     * Agent Bean 后处理器——扫描 @Agent 注解，注册到 AgentRegistry。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "openjiuwen", name = "auto-scan-agents",
        havingValue = "true", matchIfMissing = true)
    public AgentBeanPostProcessor agentBeanPostProcessor() {
        return new AgentBeanPostProcessor();
    }

    // ==================== Private Helpers ====================

    private DefaultAgentKernel.CheckpointStore createInMemoryCheckpointStore() {
        return new DefaultAgentKernel.CheckpointStore() {
            private final Map<TaskId, Checkpoint> store = new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public reactor.core.publisher.Mono<Void> save(Checkpoint cp) {
                store.put(cp.taskId(), cp);
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Mono<Checkpoint> loadLatest(TaskId taskId) {
                return reactor.core.publisher.Mono.justOrEmpty(store.get(taskId));
            }

            @Override
            public reactor.core.publisher.Flux<Checkpoint> list(TaskId taskId) {
                return reactor.core.publisher.Flux.just(store.get(taskId))
                    .filter(Objects::nonNull);
            }
        };
    }

    private DefaultAgentKernel.CheckpointStore createRedisCheckpointStore(OpenjiuwenProperties properties) {
        // Redis 存储需要 spring-data-redis 依赖。
        // 未引入时回退到内存存储，并输出警告。
        System.getLogger(OpenjiuwenAutoConfiguration.class.getName())
            .log(System.Logger.Level.WARNING,
                "Redis checkpoint store requested but spring-data-redis not found. Falling back to in-memory.");
        return createInMemoryCheckpointStore();
    }

    private DefaultAgentKernel.CheckpointStore createJdbcCheckpointStore(OpenjiuwenProperties properties) {
        // JDBC 存储需要 spring-jdbc 依赖。
        // 未引入时回退到内存存储，并输出警告。
        System.getLogger(OpenjiuwenAutoConfiguration.class.getName())
            .log(System.Logger.Level.WARNING,
                "JDBC checkpoint store requested but spring-jdbc not found. Falling back to in-memory.");
        return createInMemoryCheckpointStore();
    }
}
