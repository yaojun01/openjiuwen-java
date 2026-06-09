# Openjiuwen-Java Runtime: Spring 集成层详细架构

> 2026-06-07 | Spring Boot Starter + Spring AI 集成 + Actuator
> 前置：v2 架构（2026-06-06）的三 Artifact 结构 + Beta 变体的 7 系统调用
> 方法：接口分层 + 条件装配 + 压榨 Spring AI 已有能力而非重复造轮子
> 参考：Spring AI 1.1.x 源码（ChatModel / ToolCallback / Advisor / MCP / VectorStore）

---

## 零、设计前提与边界

### 已决策的依赖策略

| 依赖 Spring 的部分 | 自建部分 | 理由 |
|---|---|---|
| IoC 容器 + AutoConfiguration | AgentKernel（编排引擎） | 编排逻辑是差异化核心 |
| ChatModel（20+ Provider） | SafetyBoundary（安全边界） | 安全校验不能用通用拦截器替代 |
| ToolCallback / MCP | PEV 引擎（Plan-Execute-Verify） | PEV 是执行策略，Spring AI 没有 |
| VectorStore | AdaptiveStrategy（自适应策略选择） | 策略路由是 Runtime 的大脑 |
| Actuator / Micrometer | GuardrailEngine（护栏引擎） | 护栏是深度防御，不是监控 |
| Reactor（Flux/Mono） | Meta-Agent（自我反思） | 元认知循环是自建推理链 |
| Spring Data | Agent Memory 多槽位 | Memory 槽位管理超越 Spring AI ChatMemory |

### 模块归属

```
openjiuwen-core         ← 纯接口 + 注解 + 模型，Java 21，零 Spring 依赖
openjiuwen-runtime      ← Spring Boot 执行引擎（本文重点）
openjiuwen-sdk          ← Java 8 桥接层（不涉及 Spring）
```

---

## 一、Spring Boot Starter 设计

### 1.1 Maven 模块结构

```
openjiuwen-runtime/
├── runtime-core/                          ← 核心抽象（零 Spring 依赖）
├── runtime-strategies/                    ← 执行策略实现
├── runtime-deep/                          ← Deep Agent 四支柱
├── runtime-checkpoint/                    ← 检查点存储
├── runtime-memory/                        ← 记忆系统
├── runtime-guardrail/                     ← 安全护栏引擎
└── runtime-spring-boot-starter/           ← Spring Boot 自动配置（本文重点）
    ├── src/main/java/com/openjiuwen/runtime/spring/
    │   ├── autoconfigure/
    │   │   ├── OpenJiuwenAutoConfiguration.java          ← 主入口
    │   │   ├── OpenJiuwenAgentAutoConfiguration.java     ← Agent 注册
    │   │   ├── OpenJiuwenStrategyAutoConfiguration.java  ← 执行策略
    │   │   ├── OpenJiuwenMemoryAutoConfiguration.java    ← 记忆系统
    │   │   ├── OpenJiuwenGuardrailAutoConfiguration.java ← 安全护栏
    │   │   ├── OpenJiuwenActuatorAutoConfiguration.java  ← Actuator 端点
    │   │   └── OpenJiuwenMcpAutoConfiguration.java       ← MCP 集成
    │   ├── properties/
    │   │   ├── OpenJiuwenProperties.java                 ← 根配置
    │   │   ├── OpenJiuwenAgentProperties.java            ← Agent 级配置
    │   │   ├── OpenJiuwenMemoryProperties.java           ← 记忆配置
    │   │   ├── OpenJiuwenGuardrailProperties.java        ← 护栏配置
    │   │   └── OpenJiuwenCheckpointProperties.java       ← 检查点配置
    │   ├── agent/
    │   │   ├── AgentBeanPostProcessor.java               ← @Agent 注解扫描
    │   │   └── AgentToolBridge.java                      ← @Tool → ToolCallback 桥接
    │   ├── endpoint/
    │   │   ├── AgentHealthEndpoint.java
    │   │   ├── AgentMetricsEndpoint.java
    │   │   └── AgentTaskEndpoint.java
    │   └── advisor/
    │       ├── GuardrailAdvisor.java                     ← 护栏 Advisor
    │       └── CheckpointAdvisor.java                    ← 检查点 Advisor
    └── src/main/resources/
        └── META-INF/spring/
            └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 1.2 自动配置类（@AutoConfiguration）

#### 1.2.1 主入口：OpenJiuwenAutoConfiguration

```java
package com.openjiuwen.runtime.spring.autoconfigure;

import com.openjiuwen.runtime.core.AgentKernel;
import com.openjiuwen.runtime.core.strategy.ExecutionStrategy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Openjiuwen Runtime 自动配置主入口。
 *
 * 生效条件：
 * 1. classpath 上有 ChatModel（Spring AI 已引入）
 * 2. 开发者未手动禁用 openjiuwen.enabled=false
 *
 * 注册顺序：在 Spring AI 的自动配置之后执行，
 * 确保 ChatModel Bean 已经就绪。
 */
@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@ConditionalOnProperty(prefix = "openjiuwen", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({
    OpenJiuwenProperties.class,
    OpenJiuwenAgentProperties.class,
    OpenJiuwenMemoryProperties.class,
    OpenJiuwenGuardrailProperties.class,
    OpenJiuwenCheckpointProperties.class
})
@Import({
    OpenJiuwenAgentAutoConfiguration.class,
    OpenJiuwenStrategyAutoConfiguration.class,
    OpenJiuwenMemoryAutoConfiguration.class,
    OpenJiuwenGuardrailAutoConfiguration.class,
    OpenJiuwenActuatorAutoConfiguration.class,
    OpenJiuwenMcpAutoConfiguration.class
})
public class OpenJiuwenAutoConfiguration {

    /**
     * AgentKernel：Runtime 的核心编排引擎。
     *
     * 职责：接收 AgentClient 的调用请求，选择 ExecutionStrategy，
     * 驱动执行循环，返回事件流。
     *
     * 为什么是 @Component 而不是接口：Kernel 是 Runtime 的心脏，
     * 开发者不应该替换它——如果需要定制，替换 Strategy 和 Store。
     */
    @Bean("openjiuwenAgentKernel")
    @ConditionalOnMissingBean(AgentKernel.class)
    public AgentKernel agentKernel(
            ChatModel chatModel,
            AgentRegistry agentRegistry,
            StrategyRegistry strategyRegistry,
            MemoryStore memoryStore,
            CheckpointStore checkpointStore,
            GuardrailEngine guardrailEngine,
            OpenJiuwenProperties properties
    ) {
        return AgentKernel.builder()
                .chatModel(chatModel)
                .agentRegistry(agentRegistry)
                .strategyRegistry(strategyRegistry)
                .memoryStore(memoryStore)
                .checkpointStore(checkpointStore)
                .guardrailEngine(guardrailEngine)
                .maxConcurrentTasks(properties.getMaxConcurrentTasks())
                .defaultTimeout(properties.getDefaultTimeout())
                .build();
    }

    /**
     * AgentClient：开发者入口。
     * 同 JVM 内直接调用 AgentKernel，不走 HTTP。
     */
    @Bean("openjiuwenAgentClient")
    @ConditionalOnMissingBean(AgentClient.class)
    public AgentClient agentClient(AgentKernel kernel) {
        return new LocalAgentClient(kernel);
    }

    /**
     * AgentRegistry：扫描并注册所有 @Agent 注解的类。
     */
    @Bean("openjiuwenAgentRegistry")
    @ConditionalOnMissingBean(AgentRegistry.class)
    public AgentRegistry agentRegistry(ApplicationContext ctx) {
        Map<String, Object> agentBeans = ctx.getBeansWithAnnotation(
            com.openjiuwen.core.annotation.Agent.class
        );
        return AgentRegistry.from(agentBeans);
    }

    /**
     * StrategyRegistry：收集所有 ExecutionStrategy Bean。
     */
    @Bean("openjiuwenStrategyRegistry")
    @ConditionalOnMissingBean(StrategyRegistry.class)
    public StrategyRegistry strategyRegistry(
            ObjectProvider<List<ExecutionStrategy>> strategies
    ) {
        List<ExecutionStrategy> allStrategies = strategies.stream()
                .flatMap(List::stream)
                .toList();
        return StrategyRegistry.from(allStrategies);
    }
}
```

#### 1.2.2 Agent 注册：OpenJiuwenAgentAutoConfiguration

```java
package com.openjiuwen.runtime.spring.autoconfigure;

import com.openjiuwen.runtime.spring.agent.AgentBeanPostProcessor;
import com.openjiuwen.runtime.spring.agent.AgentToolBridge;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Agent 定义和工具的自动注册。
 *
 * 核心职责：
 * 1. AgentBeanPostProcessor：扫描 @Agent 注解类，提取元数据
 * 2. AgentToolBridge：将 @Tool 方法转换为 Spring AI 的 ToolCallback[]
 */
@AutoConfiguration
@ConditionalOnClass(ToolCallback.class)
public class OpenJiuwenAgentAutoConfiguration {

    /**
     * BeanPostProcessor：在 Spring 容器初始化每个 Bean 之后，
     * 检查是否有 @Agent 注解，有则提取元数据注册到 AgentRegistry。
     *
     * 为什么用 BeanPostProcessor 而不是 ClassPathScanning：
     * - 开发者可能用 @ConditionalOnProperty 动态注册 Agent
     * - BeanPostProcessor 能捕获到所有注册方式（@Bean / @Component / XML）
     * - 不需要指定 base-package
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentBeanPostProcessor agentBeanPostProcessor(AgentRegistry registry) {
        return new AgentBeanPostProcessor(registry);
    }

    /**
     * 工具桥接：将 @Agent 类中的 @Tool 方法转为 Spring AI ToolCallback。
     *
     * 关键设计：
     * - 用 ToolCallbacks.from(agentInstance) 一次性提取所有 @Tool 方法
     * - 每个 Agent 的 ToolCallback[] 绑定到 AgentDefinition
     * - MCP 远程工具通过 SyncMcpToolCallbackProvider 单独注册
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentToolBridge agentToolBridge() {
        return new AgentToolBridge();
    }
}
```

#### 1.2.3 执行策略：OpenJiuwenStrategyAutoConfiguration

```java
package com.openjiuwen.runtime.spring.autoconfigure;

import com.openjiuwen.runtime.core.strategy.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 注册内置执行策略。开发者可以用 @Bean 注册自定义策略覆盖。
 */
@AutoConfiguration
public class OpenJiuwenStrategyAutoConfiguration {

    @Bean("reactStrategy")
    @ConditionalOnMissingBean(name = "reactStrategy")
    public ReActStrategy reactStrategy() {
        return new ReActStrategy();
    }

    @Bean("deepStrategy")
    @ConditionalOnMissingBean(name = "deepStrategy")
    public DeepStrategy deepStrategy(
            SubAgentSpawner spawner,
            VirtualFileSystem vfs
    ) {
        return new DeepStrategy(spawner, vfs);
    }

    @Bean("workflowStrategy")
    @ConditionalOnMissingBean(name = "workflowStrategy")
    public WorkflowStrategy workflowStrategy() {
        return new WorkflowStrategy();
    }

    @Bean("planExecuteVerifyStrategy")
    @ConditionalOnMissingBean(name = "planExecuteVerifyStrategy")
    public PlanExecuteVerifyStrategy planExecuteVerifyStrategy() {
        return new PlanExecuteVerifyStrategy();
    }

    /**
     * AdaptiveStrategy：根据任务特征自动选择策略。
     * 这是 Runtime 的"大脑"——不是硬编码 strategy="react"，
     * 而是让 Runtime 自己判断用什么策略。
     */
    @Bean("adaptiveStrategy")
    @ConditionalOnMissingBean(name = "adaptiveStrategy")
    public AdaptiveStrategy adaptiveStrategy(StrategyRegistry registry) {
        return new AdaptiveStrategy(registry);
    }
}
```

#### 1.2.4 记忆系统：OpenJiuwenMemoryAutoConfiguration

```java
package com.openjiuwen.runtime.spring.autoconfigure;

import com.openjiuwen.runtime.core.memory.MemoryStore;
import com.openjiuwen.runtime.memory.InMemoryMemoryStore;
import com.openjiuwen.runtime.memory.VectorStoreMemoryStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 记忆系统自动配置。
 *
 * 两层记忆：
 * - 短期记忆：InMemoryMemoryStore（对话历史，Session 级别）
 * - 长期记忆：VectorStoreMemoryStore（语义搜索，跨 Session）
 *
 * 条件装配逻辑：
 * - classpath 上有 VectorStore → 自动启用长期记忆
 * - 没有 VectorStore → 只用短期记忆（纯内存）
 * - 开发者可以提供自定义 MemoryStore Bean 覆盖
 */
@AutoConfiguration
public class OpenJiuwenMemoryAutoConfiguration {

    /**
     * 默认短期记忆：纯内存。
     * 生产环境应替换为 RedisMemoryStore。
     */
    @Bean("openjiuwenMemoryStore")
    @ConditionalOnMissingBean(MemoryStore.class)
    @ConditionalOnProperty(
        prefix = "openjiuwen.memory",
        name = "type",
        havingValue = "in-memory",
        matchIfMissing = true
    )
    public MemoryStore inMemoryMemoryStore(OpenJiuwenMemoryProperties props) {
        return new InMemoryMemoryStore(props.getMaxSessions(), props.getSessionTtl());
    }

    /**
     * 长期记忆：基于 Spring AI VectorStore。
     * 当 classpath 上有 VectorStore Bean 时自动启用。
     */
    @Bean("openjiuwenVectorMemoryStore")
    @ConditionalOnClass(VectorStore.class)
    @ConditionalOnMissingBean(name = "openjiuwenVectorMemoryStore")
    @ConditionalOnProperty(prefix = "openjiuwen.memory.long-term", name = "enabled", havingValue = "true")
    public MemoryStore vectorMemoryStore(VectorStore vectorStore, OpenJiuwenMemoryProperties props) {
        return new VectorStoreMemoryStore(vectorStore, props.getTopK());
    }
}
```

#### 1.2.5 安全护栏：OpenJiuwenGuardrailAutoConfiguration

```java
package com.openjiuwen.runtime.spring.autoconfigure;

import com.openjiuwen.runtime.core.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.guardrail.DefaultGuardrailEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 安全护栏引擎自动配置。
 *
 * GuardrailEngine 是自建组件——不依赖 Spring AI 的 Advisor。
 * 理由：Advisor 是拦截器链，只能做"前置检查/后置处理"；
 * GuardrailEngine 是独立的安全评估引擎，有预算管理、深度限制、
 * 工具白名单等 Runtime 级别的安全语义，不是简单的拦截器。
 */
@AutoConfiguration
public class OpenJiuwenGuardrailAutoConfiguration {

    @Bean("openjiuwenGuardrailEngine")
    @ConditionalOnMissingBean(GuardrailEngine.class)
    public GuardrailEngine guardrailEngine(OpenJiuwenGuardrailProperties props) {
        return DefaultGuardrailEngine.builder()
                .maxIterations(props.getMaxIterations())
                .maxTokenBudget(props.getMaxTokenBudget())
                .maxSubAgentDepth(props.getMaxSubAgentDepth())
                .toolWhitelist(props.getToolWhitelist())
                .sensitivePatterns(props.getSensitivePatterns())
                .humanApprovalRequired(props.isHumanApprovalRequired())
                .build();
    }
}
```

#### 1.2.6 检查点存储：OpenJiuwenCheckpointAutoConfiguration

```java
package com.openjiuwen.runtime.spring.autoconfigure;

import com.openjiuwen.runtime.core.checkpoint.CheckpointStore;
import com.openjiuwen.runtime.checkpoint.InMemoryCheckpointStore;
import com.openjiuwen.runtime.checkpoint.RedisCheckpointStore;
import com.openjiuwen.runtime.checkpoint.JdbcCheckpointStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 检查点存储自动配置。
 * 三级后端，按条件选择：
 * - in-memory（默认，开发调试）
 * - redis（生产，亚毫秒读写）
 * - jdbc（审计，持久化可查询）
 */
@AutoConfiguration
public class OpenJiuwenCheckpointAutoConfiguration {

    @Bean("openjiuwenCheckpointStore")
    @ConditionalOnMissingBean(CheckpointStore.class)
    @ConditionalOnProperty(
        prefix = "openjiuwen.checkpoint",
        name = "type",
        havingValue = "in-memory",
        matchIfMissing = true
    )
    public CheckpointStore inMemoryCheckpointStore() {
        return new InMemoryCheckpointStore();
    }

    @Bean("openjiuwenRedisCheckpointStore")
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(name = "openjiuwenRedisCheckpointStore")
    @ConditionalOnProperty(
        prefix = "openjiuwen.checkpoint",
        name = "type",
        havingValue = "redis"
    )
    public CheckpointStore redisCheckpointStore(
            RedisConnectionFactory connectionFactory,
            OpenJiuwenCheckpointProperties props
    ) {
        return new RedisCheckpointStore(connectionFactory, props.getTtl());
    }

    @Bean("openjiuwenJdbcCheckpointStore")
    @ConditionalOnClass(javax.sql.DataSource.class)
    @ConditionalOnMissingBean(name = "openjiuwenJdbcCheckpointStore")
    @ConditionalOnProperty(
        prefix = "openjiuwen.checkpoint",
        name = "type",
        havingValue = "jdbc"
    )
    public CheckpointStore jdbcCheckpointStore(
            javax.sql.DataSource dataSource,
            OpenJiuwenCheckpointProperties props
    ) {
        return new JdbcCheckpointStore(dataSource, props.getTableName());
    }
}
```

#### 1.2.7 MCP 集成：OpenJiuwenMcpAutoConfiguration

```java
package com.openjiuwen.runtime.spring.autoconfigure;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * MCP 工具桥接自动配置。
 *
 * 当 classpath 上有 Spring AI MCP Client 时自动生效。
 * 作用：将 MCP 协议发现的远程工具转为 Spring AI ToolCallback，
 * 注册到 AgentKernel 的工具池中。
 *
 * 设计决策：
 * - 不自己造 MCP Client，直接用 Spring AI 的 SyncMcpToolCallbackProvider
 * - MCP Server 端（SDK 内嵌的轻量实现）不在 Runtime 侧
 * - Runtime 只作为 MCP Client 消费工具
 */
@AutoConfiguration
@ConditionalOnClass(McpSyncClient.class)
@ConditionalOnProperty(prefix = "openjiuwen.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenJiuwenMcpAutoConfiguration {

    /**
     * 将所有 MCP Client 发现的工具聚合为 ToolCallback[]。
     * 注入到 AgentKernel 的工具池中。
     */
    @Bean("openjiuwenMcpToolCallbacks")
    @ConditionalOnProperty(prefix = "openjiuwen.mcp", name = "tool-callbacks-enabled", havingValue = "true", matchIfMissing = true)
    public ToolCallback[] mcpToolCallbacks(
            ObjectProvider<List<McpSyncClient>> mcpClients
    ) {
        List<McpSyncClient> clients = mcpClients.stream()
                .flatMap(List::stream)
                .toList();
        if (clients.isEmpty()) {
            return new ToolCallback[0];
        }
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .build()
                .getToolCallbacks();
    }
}
```

### 1.3 配置属性（@ConfigurationProperties）

#### 1.3.1 根配置：OpenJiuwenProperties

```java
package com.openjiuwen.runtime.spring.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * openjiuwen.* 配置属性。
 *
 * 开发者通过 application.yml 的 openjiuwen 前缀配置。
 */
@ConfigurationProperties(prefix = "openjiuwen")
public class OpenJiuwenProperties {

    /** 是否启用 Openjiuwen Runtime（默认 true） */
    private boolean enabled = true;

    /** 默认使用的 LLM 模型（空 = 用 Spring AI 默认） */
    private String defaultModel = "";

    /** 最大并发任务数（虚拟线程场景建议 10000+） */
    private int maxConcurrentTasks = 10000;

    /** 任务默认超时 */
    private Duration defaultTimeout = Duration.ofMinutes(5);

    /** 默认执行策略名 */
    private String defaultStrategy = "react";

    /** 是否在启动时扫描 @Agent（开发阶段可关闭加速启动） */
    private boolean agentScanEnabled = true;

    /** Agent 扫描的基础包（空 = 扫描所有） */
    private String[] basePackages = {};

    // getter/setter 省略（IDE 生成）
}
```

#### 1.3.2 Agent 级配置：OpenJiuwenAgentProperties

```java
package com.openjiuwen.runtime.spring.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.Map;

/**
 * openjiuwen.agent.* 配置。
 *
 * 支持对每个 Agent 单独配置，覆盖 @Agent 注解的默认值。
 * Map 的 key = Agent name（@Agent(name="xxx")）。
 *
 * 示例 yml:
 *   openjiuwen:
 *     agent:
 *       defaults:
 *         max-iterations: 15
 *         timeout: 3m
 *       agents:
 *         order-service:
 *           model: deepseek-chat
 *           max-iterations: 20
 *         approval-agent:
 *           strategy: deep
 *           timeout: 30m
 */
@ConfigurationProperties(prefix = "openjiuwen.agent")
public class OpenJiuwenAgentProperties {

    /** 所有 Agent 的默认值 */
    private AgentConfig defaults = new AgentConfig();

    /** 按 Agent name 单独配置 */
    private Map<String, AgentConfig> agents = Map.of();

    public static class AgentConfig {
        /** LLM 模型 */
        private String model = "";
        /** 最大迭代次数 */
        private int maxIterations = 10;
        /** 执行超时 */
        private Duration timeout = Duration.ofMinutes(5);
        /** 执行策略 */
        private String strategy = "";
        /** 系统提示文件路径（classpath:prompts/xxx.md） */
        private String systemPromptFile = "";

        // getter/setter
    }

    // getter/setter
}
```

#### 1.3.3 记忆配置：OpenJiuwenMemoryProperties

```java
package com.openjiuwen.runtime.spring.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "openjiuwen.memory")
public class OpenJiuwenMemoryProperties {

    /** 记忆类型：in-memory / redis / vector */
    private String type = "in-memory";

    /** 最大 Session 数（in-memory 模式） */
    private int maxSessions = 10000;

    /** Session TTL */
    private Duration sessionTtl = Duration.ofHours(1);

    /** 长期记忆（语义搜索） */
    private LongTerm longTerm = new LongTerm();

    public static class LongTerm {
        /** 是否启用长期记忆（需要 VectorStore Bean） */
        private boolean enabled = false;
        /** 语义搜索返回 top-K 条 */
        private int topK = 5;
    }

    // getter/setter
}
```

#### 1.3.4 护栏配置：OpenJiuwenGuardrailProperties

```java
package com.openjiuwen.runtime.spring.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "openjiuwen.guardrail")
public class OpenJiuwenGuardrailProperties {

    /** 全局最大迭代次数（硬限制，不可被 @Agent 覆盖） */
    private int maxIterations = 50;

    /** 全局 Token 预算上限 */
    private int maxTokenBudget = 100000;

    /** 子 Agent 最大递归深度 */
    private int maxSubAgentDepth = 3;

    /** 工具白名单（空 = 允许所有） */
    private List<String> toolWhitelist = List.of();

    /** 敏感信息检测正则（手机号、身份证等） */
    private List<String> sensitivePatterns = List.of(
        "1[3-9]\\d{9}",           // 手机号
        "\\d{17}[\\dXx]"          // 身份证号
    );

    /** 是否要求人工确认（金额操作、删除操作等） */
    private boolean humanApprovalRequired = false;

    /** 输出内容审查 */
    private boolean outputSanitizationEnabled = true;

    // getter/setter
}
```

#### 1.3.5 检查点配置：OpenJiuwenCheckpointProperties

```java
package com.openjiuwen.runtime.spring.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "openjiuwen.checkpoint")
public class OpenJiuwenCheckpointProperties {

    /** 存储类型：in-memory / redis / jdbc */
    private String type = "in-memory";

    /** 检查点保留时间 */
    private Duration ttl = Duration.ofHours(24);

    /** JDBC 表名 */
    private String tableName = "openjiuwen_checkpoints";

    /** 是否在每步保存检查点（生产=true，开发=false 减少开销） */
    private boolean saveEveryStep = false;

    // getter/setter
}
```

### 1.4 条件装配策略总览

```
条件装配决策树：

openjiuwen.enabled=true（默认）
  ├── classpath 有 ChatModel → 注册 AgentKernel
  │     ├── classpath 有 VectorStore → 启用长期记忆
  │     ├── classpath 有 McpSyncClient → 启用 MCP 工具发现
  │     ├── classpath 有 RedisConnectionFactory → 可用 Redis Checkpoint
  │     ├── classpath 有 DataSource → 可用 JDBC Checkpoint
  │     └── classpath 有 Actuator → 注册 Agent 端点
  ├── @Agent 注解扫描 → 注册 AgentDefinition
  ├── @Tool 方法 → 转为 ToolCallback
  └── ExecutionStrategy Beans → 收集到 StrategyRegistry
```

### 1.5 AutoConfiguration.imports

```properties
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenAutoConfiguration
```

Spring Boot 3.x 使用这个文件替代老式的 spring.factories。

---

## 二、Spring AI 集成点

### 2.1 ChatModel：谁用它？怎么封装？

#### 消费方矩阵

| Runtime 组件 | 使用方式 | 为什么 |
|---|---|---|
| **ReActStrategy** | 直接调用 `chatModel.call(Prompt)` | ReAct 循环的每一步推理都调 ChatModel |
| **DeepStrategy** | 通过 ReActStrategy 间接调用 | Deep = ReAct + 三件套，底层复用 ReAct |
| **PlanExecuteVerifyStrategy** | 三次调用：Plan / Execute / Verify | 三个阶段各自独立调 ChatModel |
| **AdaptiveStrategy** | 不直接调，委托给选中的 Strategy | 只做路由，不做推理 |
| **GuardrailEngine** | 不调 ChatModel | 安全校验基于规则和模式匹配，不调 LLM |
| **SubAgentSpawner** | 通过 AgentKernel 间接调用 | 子 Agent 复用同一个 AgentKernel |

#### 封装层：ChatModelAdapter

```java
package com.openjiuwen.runtime.spring.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * ChatModel 适配器。
 *
 * 为什么需要适配层而不是直接用 ChatModel：
 * 1. 统一错误处理（LLM 调用失败 → Agent 错误事件）
 * 2. 注入 Token 统计（每次调用后记录 input/output tokens）
 * 3. 注入 GuardrailEngine（调用前后过护栏检查）
 * 4. 支持多模型路由（同一个 Agent 可以配置 fallback 模型）
 * 5. 适配 StreamingChatModel（ReAct 用同步，Deep 用流式）
 */
public class ChatModelAdapter {

    private final ChatModel chatModel;
    private final TokenCounter tokenCounter;
    private final GuardrailEngine guardrailEngine;
    private final ChatOptions defaultOptions;

    /**
     * 同步调用（ReAct / PlanExecuteVerify 使用）。
     */
    public ChatResponse call(Prompt prompt, String modelHint) {
        // 1. 前置护栏检查
        guardrailEngine.checkInput(prompt);

        // 2. 调用 ChatModel
        ChatResponse response = chatModel.call(applyModelHint(prompt, modelHint));

        // 3. Token 统计
        tokenCounter.record(response.getMetadata());

        // 4. 后置护栏检查
        guardrailEngine.checkOutput(response);

        return response;
    }

    /**
     * 流式调用（Deep Agent 的 Streaming 使用）。
     */
    public Flux<ChatResponse> stream(Prompt prompt, String modelHint) {
        return chatModel.stream(applyModelHint(prompt, modelHint))
                .doOnNext(chunk -> {
                    if (chunk.getMetadata() != null) {
                        tokenCounter.record(chunk.getMetadata());
                    }
                });
    }

    private Prompt applyModelHint(Prompt prompt, String modelHint) {
        // 如果 Agent 指定了模型，覆盖默认模型
        if (modelHint != null && !modelHint.isBlank()) {
            ChatOptions options = ChatOptions.builder()
                    .model(modelHint)
                    .build();
            return new Prompt(prompt.getInstructions(), options);
        }
        return prompt;
    }
}
```

#### 在 AgentKernel 中的注入方式

```java
// AgentKernel 不直接持有 ChatModel，而是持有 ChatModelAdapter。
// ChatModelAdapter 由 AutoConfiguration 创建。
public class AgentKernel {

    private final ChatModelAdapter chatModelAdapter; // 不是 ChatModel

    public Flux<AgentEvent> run(String agentName, String input) {
        AgentDefinition def = registry.get(agentName);
        ExecutionStrategy strategy = strategyRegistry.get(def.strategy());

        TaskContext ctx = TaskContext.builder()
                .chatModelAdapter(chatModelAdapter) // 传给 Strategy
                .agentDefinition(def)
                .userInput(input)
                .build();

        return strategy.execute(ctx);
    }
}
```

### 2.2 ToolCallback / MCP：invoke() 如何桥接到 Spring AI

#### @Tool → ToolCallback 转换

```java
package com.openjiuwen.runtime.spring.agent;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * Agent 工具桥接器。
 *
 * 职责：将 @Agent 类中的 @Tool 注解方法转为 Spring AI ToolCallback[]。
 *
 * 转换方式：直接用 Spring AI 的 ToolCallbacks.from() 静态方法。
 * 它会扫描对象上的 @Tool 注解方法（Spring AI 1.1+ 的注解），
 * 生成对应的 ToolCallback 实例。
 *
 * 我们的 @Agent 注解类中的 @Tool 方法，Spring AI 的 MethodToolCallbackProvider
 * 会自动识别——前提是开发者使用 Spring AI 的 org.springframework.ai.tool.annotation.Tool
 * 注解，或者我们提供一个自定义的 MethodToolCallbackProvider 来识别我们的注解。
 */
public class AgentToolBridge {

    /**
     * 将 Agent 实例中的工具方法转为 ToolCallback[]。
     */
    public ToolCallback[] resolveToolCallbacks(Object agentInstance) {
        return ToolCallbacks.from(agentInstance);
    }

    /**
     * 将 Agent 实例的工具 + MCP 远程工具合并。
     */
    public ToolCallback[] mergeToolCallbacks(
            Object agentInstance,
            ToolCallback[] mcpToolCallbacks
    ) {
        ToolCallback[] localTools = resolveToolCallbacks(agentInstance);
        if (mcpToolCallbacks == null || mcpToolCallbacks.length == 0) {
            return localTools;
        }
        ToolCallback[] merged = new ToolCallback[localTools.length + mcpToolCallbacks.length];
        System.arraycopy(localTools, 0, merged, 0, localTools.length);
        System.arraycopy(mcpToolCallbacks, 0, merged, localTools.length, mcpToolCallbacks.length);
        return merged;
    }
}
```

#### invoke() 系统调用的完整桥接路径

```
开发者写 @Tool 方法
       │
       ▼
AgentBeanPostProcessor 在 Bean 初始化后提取元数据
       │
       ▼
AgentToolBridge.resolveToolCallbacks(agentInstance)
  → ToolCallbacks.from(agentInstance)  ← Spring AI 提供的转换
       │
       ▼
ToolCallback[] 存入 AgentDefinition.tools
       │
       ▼
AgentKernel.run() → ExecutionStrategy.execute()
       │
       ▼
ReActStrategy.execute() 循环：
  1. chatModel.call(prompt_with_tools)  ← Spring AI ChatModel
  2. 解析 ChatResponse 中的 toolCalls
  3. 从 AgentDefinition.tools 中找匹配的 ToolCallback
  4. toolCallback.call(toolInput)       ← Spring AI ToolCallback.call()
  5. 将工具结果加入消息列表
  6. 继续循环或结束
```

#### MCP 远程工具的桥接路径

```
企业 JVM（Java 8）                          Runtime JVM（Java 21）
┌─────────────────┐                        ┌──────────────────────┐
│ SDK 内嵌 MCP     │  ← MCP over HTTP ──→  │ Spring AI MCP Client  │
│ Server           │                        │ (McpSyncClient)       │
│ @Tool 方法       │                        │       │               │
└─────────────────┘                        │       ▼               │
                                            │ SyncMcpToolCallback  │
                                            │ Provider             │
                                            │       │               │
                                            │       ▼               │
                                            │ ToolCallback[]        │
                                            │ (和本地 @Tool 合并)    │
                                            └──────────────────────┘
```

关键代码：

```java
// McpToolCallbackAutoConfiguration 已经自动将 MCP Client 转为 ToolCallback。
// Runtime 侧不需要额外代码——只需要把 MCP 的 ToolCallback 合并到 Agent 的工具池。
//
// OpenJiuwenMcpAutoConfiguration.mcpToolCallbacks() 负责收集。
// AgentToolBridge.mergeToolCallbacks() 负责合并。
```

### 2.3 VectorStore：Agent Memory 如何用 Spring AI 的 VectorStore

#### 双层记忆架构

```
┌─────────────────────────────────────────────────────────┐
│                    MemoryStore（统一接口）                 │
│                                                         │
│  ┌─────────────────────────┐  ┌──────────────────────┐ │
│  │ ShortTermMemory          │  │ LongTermMemory        │ │
│  │ (对话历史，Session 级)    │  │ (语义搜索，跨 Session) │ │
│  │                          │  │                       │ │
│  │ 实现：InMemoryStore      │  │ 实现：VectorStoreMem  │ │
│  │ 或 RedisMemoryStore      │  │   ├ add() →          │ │
│  │                          │  │     VectorStore.add() │ │
│  │ append(sessionId, msg)   │  │   ├ search() →       │ │
│  │ load(sessionId, lastN)   │  │     VectorStore       │ │
│  │                          │  │     .similaritySearch │ │
│  └─────────────────────────┘  └──────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

#### VectorStoreMemoryStore 实现

```java
package com.openjiuwen.runtime.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI VectorStore 的长期记忆实现。
 *
 * 工作方式：
 * 1. Agent 每次完成一轮对话 → 将关键信息提取为 Document → VectorStore.add()
 * 2. Agent 开始新任务 → VectorStore.similaritySearch(query) → 检索相关记忆
 * 3. 检索到的记忆注入 systemPrompt 或作为上下文消息
 *
 * 元数据过滤：
 * - agentName：每个 Agent 只搜自己的记忆
 * - sessionId：可选，限制在特定 Session 内搜索
 * - category：记忆类型（fact / preference / procedure / experience）
 */
public class VectorStoreMemoryStore implements MemoryStore {

    private final VectorStore vectorStore;
    private final int topK;

    public VectorStoreMemoryStore(VectorStore vectorStore, int topK) {
        this.vectorStore = vectorStore;
        this.topK = topK;
    }

    @Override
    public Mono<Void> append(String sessionId, Message message) {
        // 将消息转为 Document，附带元数据
        Document doc = new Document(
            message.content(),
            Map.of(
                "sessionId", sessionId,
                "agentName", message.agentName(),
                "category", message.category(),
                "timestamp", Instant.now().toString()
            )
        );
        return Mono.fromRunnable(() -> vectorStore.add(List.of(doc)));
    }

    @Override
    public Flux<Message> search(String agentName, String query) {
        return Flux.fromIterable(
            vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(
                        "agentName == '" + agentName + "'"
                    )
                    .build()
            )
        ).map(doc -> Message.fromVectorDocument(doc));
    }
}
```

#### Agent Kernel 中的记忆注入

```java
// 在 AgentKernel.run() 中，记忆注入到 TaskContext：
public Flux<AgentEvent> run(String agentName, String input) {
    AgentDefinition def = registry.get(agentName);

    // 1. 加载短期记忆（对话历史）
    Flux<Message> history = memoryStore.load(sessionId, 20);

    // 2. 加载长期记忆（语义搜索）
    Flux<Message> relevant = memoryStore.search(agentName, input);

    // 3. 合并到 systemPrompt
    String enhancedSystemPrompt = def.systemPrompt()
        + "\n\n## 相关历史记忆\n"
        + relevant.collectList().block().stream()
            .map(Message::content)
            .collect(Collectors.joining("\n"));

    TaskContext ctx = TaskContext.builder()
            .systemPrompt(enhancedSystemPrompt)
            .messageHistory(history.collectList().block())
            .build();

    return strategy.execute(ctx);
}
```

### 2.4 Advisor 链：Runtime 事件模型与 Spring AI Advisor 共存

#### 问题分析

Spring AI 的 Advisor 是围绕 ChatClient 的拦截器链：
- `CallAdvisor.adviseCall(request, chain)` — 同步拦截
- `StreamAdvisor.adviseStream(request, chain)` — 流式拦截

Openjiuwen Runtime 的事件模型是围绕 AgentEvent 的：
- `AgentEvent`（ThinkingEvent / ToolCallEvent / ...）— Runtime 内部事件

**两者不是同一层抽象**。Advisor 在 ChatModel 调用层，AgentEvent 在 Agent 编排层。

#### 共存方案：GuardrailAdvisor + CheckpointAdvisor

```java
package com.openjiuwen.runtime.spring.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * 护栏 Advisor：在每次 ChatModel 调用前后执行安全检查。
 *
 * 这是 Spring AI Advisor 链中的拦截器，作用在 ChatModel 层面。
 * 与 GuardrailEngine（Agent 编排层面）形成双层防御。
 *
 * 层级关系：
 * ┌─────────────────────────────────────────┐
 * │ Agent 编排层（GuardrailEngine）           │  ← 检查工具调用是否允许、Token 预算
 * │   ├ ReActStrategy                       │
 * │   └ 每一步 → chatModelAdapter.call()    │
 * │        ┌──────────────────────────────┐ │
 * │        │ ChatModel 调用层（Advisor 链） │ │  ← 检查输入输出内容安全
 * │        │   ├ GuardrailAdvisor         │ │
 * │        │   ├ CheckpointAdvisor        │ │
 * │        │   └ ChatModel.call()         │ │
 * │        └──────────────────────────────┘ │
 * └─────────────────────────────────────────┘
 */
public class GuardrailAdvisor implements CallAdvisor {

    private final GuardrailEngine guardrailEngine;

    @Override
    public String getName() {
        return "openjiuwen-guardrail";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100; // 在 ChatMemory Advisor 之前
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 前置：检查用户输入是否包含敏感信息
        guardrailEngine.checkInputContent(request.prompt().getContents());

        // 执行
        ChatClientResponse response = chain.nextCall(request);

        // 后置：检查 LLM 输出是否包含敏感信息
        guardrailEngine.checkOutputContent(response.chatResponse().getResult().getOutput().getText());

        return response;
    }
}

/**
 * 检查点 Advisor：在每次 ChatModel 调用后自动保存检查点。
 */
public class CheckpointAdvisor implements CallAdvisor {

    private final CheckpointStore checkpointStore;

    @Override
    public String getName() {
        return "openjiuwen-checkpoint";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200; // 在 GuardrailAdvisor 之后
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        // 每次调用后保存检查点
        Checkpoint checkpoint = Checkpoint.builder()
                .taskId(extractTaskId(request))
                .stepIndex(extractStepIndex(request))
                .state(serializeState(request, response))
                .timestamp(Instant.now())
                .build();
        checkpointStore.save(checkpoint).subscribe();

        return response;
    }
}
```

#### 两者共存的架构图

```
开发者调用 AgentClient.invoke("order-service", "查订单")
       │
       ▼
AgentKernel.run()
  ├─ 1. GuardrailEngine.check()         ← Agent 编排层安全检查
  ├─ 2. Strategy.execute()
  │     └─ ReActStrategy 循环:
  │         ├─ ChatModelAdapter.call()
  │         │   └─ Spring AI Advisor 链:
  │         │       ├─ GuardrailAdvisor      ← ChatModel 层内容安全
  │         │       ├─ CheckpointAdvisor     ← ChatModel 层检查点
  │         │       └─ ChatModel.call()      ← 实际 LLM 调用
  │         ├─ ToolCallback.call()           ← Spring AI 工具执行
  │         └─ AgentEvent 发射               ← Runtime 事件流
  └─ 3. 返回 Flux<AgentEvent>
```

---

## 三、Spring Boot Actuator 集成

### 3.1 Health：AgentKernel 健康检查

```java
package com.openjiuwen.runtime.spring.endpoint;

import com.openjiuwen.runtime.core.AgentKernel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * AgentKernel 健康检查。
 *
 * 检查项：
 * 1. AgentKernel 是否已启动
 * 2. 已注册的 Agent 数量
 * 3. 当前运行中的任务数
 * 4. ChatModel 连接是否正常（ping 一次 LLM）
 */
@Component("openjiuwenHealthIndicator")
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "openjiuwen.actuator", name = "health-enabled", havingValue = "true", matchIfMissing = true)
public class AgentKernelHealthIndicator implements HealthIndicator {

    private final AgentKernel kernel;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // 1. 检查 Agent 注册数
        int agentCount = kernel.getRegisteredAgentCount();
        builder.withDetail("registeredAgents", agentCount);

        // 2. 检查运行中任务数
        int runningTasks = kernel.getRunningTaskCount();
        builder.withDetail("runningTasks", runningTasks);

        // 3. 检查策略注册数
        int strategyCount = kernel.getRegisteredStrategyCount();
        builder.withDetail("registeredStrategies", strategyCount);

        // 4. 如果运行中任务数接近上限，标记为 WARN
        if (runningTasks > kernel.getMaxConcurrentTasks() * 0.8) {
            builder.status("WARN");
            builder.withDetail("warning", "Task utilization above 80%");
        }

        return builder.build();
    }
}
```

Actuator 输出示例：

```json
// GET /actuator/health
{
  "status": "UP",
  "components": {
    "openjiuwen": {
      "status": "UP",
      "details": {
        "registeredAgents": 5,
        "runningTasks": 23,
        "registeredStrategies": 5,
        "maxConcurrentTasks": 10000
      }
    }
  }
}
```

### 3.2 Metrics：Token 消耗、执行时间、成功率

```java
package com.openjiuwen.runtime.spring.endpoint;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Agent 指标收集器。
 *
 * 使用 Micrometer（Spring Boot Actuator 内置）记录指标。
 * 指标命名遵循 Micrometer 规范：前缀 openjiuwen.agent。
 *
 * 所有指标都带 agent 标签，可以按 Agent 粒度查看。
 */
@Component("openjiuwenAgentMetrics")
public class AgentMetrics {

    // === 计数器 ===
    private final Counter taskTotalCounter;
    private final Counter taskSuccessCounter;
    private final Counter taskFailedCounter;
    private final Counter toolCallCounter;

    // === Timer ===
    private final Timer taskDurationTimer;

    // === Gauge（由 Micrometer 自动从 AtomicLong 采集）===
    private final AtomicLong activeTasks;

    public AgentMetrics(MeterRegistry registry) {
        // 任务总数
        this.taskTotalCounter = Counter.builder("openjiuwen.agent.tasks.total")
                .description("Total number of agent tasks")
                .tag("type", "all")
                .register(registry);

        // 成功数
        this.taskSuccessCounter = Counter.builder("openjiuwen.agent.tasks.total")
                .description("Successfully completed agent tasks")
                .tag("result", "success")
                .register(registry);

        // 失败数
        this.taskFailedCounter = Counter.builder("openjiuwen.agent.tasks.total")
                .description("Failed agent tasks")
                .tag("result", "failed")
                .register(registry);

        // 工具调用数
        this.toolCallCounter = Counter.builder("openjiuwen.agent.tool.calls")
                .description("Total tool call count")
                .register(registry);

        // 任务执行时间
        this.taskDurationTimer = Timer.builder("openjiuwen.agent.tasks.duration")
                .description("Agent task execution duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // 活跃任务数
        this.activeTasks = registry.gauge("openjiuwen.agent.tasks.active",
                new AtomicLong(0));
    }

    /** 记录任务开始 */
    public void onTaskStart(String agentName) {
        taskTotalCounter.increment();
        activeTasks.incrementAndGet();
    }

    /** 记录任务完成 */
    public void onTaskComplete(String agentName, Duration duration, boolean success) {
        activeTasks.decrementAndGet();
        if (success) {
            taskSuccessCounter.increment();
        } else {
            taskFailedCounter.increment();
        }
        taskDurationTimer.record(duration);
    }

    /** 记录工具调用 */
    public void onToolCall(String agentName, String toolName, boolean success) {
        toolCallCounter.increment();
    }
}
```

#### Prometheus 抓取示例

```
# HELP openjiuwen_agent_tasks_total Total number of agent tasks
# TYPE openjiuwen_agent_tasks_total counter
openjiuwen_agent_tasks_total{result="success"} 1523.0
openjiuwen_agent_tasks_total{result="failed"} 12.0

# HELP openjiuwen_agent_tasks_duration Agent task execution duration
# TYPE openjiuwen_agent_tasks_duration summary
openjiuwen_agent_tasks_duration{quantile="0.5"} 1.23
openjiuwen_agent_tasks_duration{quantile="0.95"} 5.67
openjiuwen_agent_tasks_duration{quantile="0.99"} 12.34

# HELP openjiuwen_agent_tasks_active Active tasks
# TYPE openjiuwen_agent_tasks_active gauge
openjiuwen_agent_tasks_active 23.0

# HELP openjiuwen_agent_tool_calls Total tool call count
# TYPE openjiuwen_agent_tool_calls counter
openjiuwen_agent_tool_calls{agent="order-service",tool="checkOrder"} 456.0
openjiuwen_agent_tool_calls{agent="order-service",tool="refund"} 78.0
```

### 3.3 自定义端点

#### 3.3.1 /actuator/agents（Agent 列表）

```java
package com.openjiuwen.runtime.spring.endpoint;

import com.openjiuwen.runtime.core.AgentKernel;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "agents")
@ConditionalOnProperty(prefix = "openjiuwen.actuator", name = "endpoints-enabled", havingValue = "true", matchIfMissing = true)
public class AgentListEndpoint {

    private final AgentKernel kernel;

    @ReadOperation
    public Map<String, Object> listAgents() {
        return Map.of(
            "agents", kernel.getRegisteredAgents().stream()
                .map(def -> Map.of(
                    "name", def.name(),
                    "description", def.description(),
                    "strategy", def.strategy(),
                    "model", def.model(),
                    "toolCount", def.tools().length
                ))
                .toList(),
            "total", kernel.getRegisteredAgentCount()
        );
    }

    @ReadOperation
    public Map<String, Object> getAgent(@Selector String agentName) {
        AgentDefinition def = kernel.getAgent(agentName);
        return Map.of(
            "name", def.name(),
            "description", def.description(),
            "strategy", def.strategy(),
            "model", def.model(),
            "tools", Arrays.stream(def.tools())
                .map(cb -> cb.getToolDefinition().name())
                .toList(),
            "maxIterations", def.maxIterations()
        );
    }
}
```

#### 3.3.2 /actuator/tasks（运行状态）

```java
package com.openjiuwen.runtime.spring.endpoint;

import com.openjiuwen.runtime.core.AgentKernel;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "tasks")
@ConditionalOnProperty(prefix = "openjiuwen.actuator", name = "endpoints-enabled", havingValue = "true", matchIfMissing = true)
public class AgentTaskEndpoint {

    private final AgentKernel kernel;

    @ReadOperation
    public Map<String, Object> listTasks() {
        return Map.of(
            "running", kernel.getRunningTasks().stream()
                .map(task -> Map.of(
                    "taskId", task.taskId(),
                    "agentName", task.agentName(),
                    "status", task.status().name(),
                    "duration", task.duration().toString(),
                    "stepCount", task.stepCount()
                ))
                .toList(),
            "activeCount", kernel.getRunningTaskCount(),
            "maxConcurrent", kernel.getMaxConcurrentTasks()
        );
    }

    @ReadOperation
    public Map<String, Object> getTask(@Selector String taskId) {
        TaskInfo task = kernel.getTask(taskId);
        return Map.of(
            "taskId", task.taskId(),
            "agentName", task.agentName(),
            "status", task.status().name(),
            "strategy", task.strategy(),
            "steps", task.steps().stream()
                .map(step -> Map.of(
                    "index", step.index(),
                    "type", step.type().name(),
                    "duration", step.duration().toString(),
                    "tokenUsage", step.tokenUsage().toString()
                ))
                .toList(),
            "totalTokenUsage", task.totalTokenUsage().toString(),
            "duration", task.duration().toString()
        );
    }
}
```

#### Actuator 配置

```yaml
# application.yml 中的 Actuator 配置
management:
  endpoints:
    web:
      exposure:
        include: health,info,agents,tasks,prometheus,metrics
  endpoint:
    health:
      show-details: when-authorized    # 有权限时显示详情
    agents:
      enabled: true
    tasks:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
```

---

## 四、核心接口与 Spring Bean 的映射

### 4.1 完整映射表

| Runtime 组件 | Spring 角色 | Bean 名称 | 条件装配 | 可替换 |
|---|---|---|---|---|
| **AgentKernel** | @Component（由 AutoConfig 创建） | `openjiuwenAgentKernel` | `@ConditionalOnClass(ChatModel)` | 是（但不建议） |
| **AgentRegistry** | @Component（由 AutoConfig 创建） | `openjiuwenAgentRegistry` | 始终注册 | 是 |
| **StrategyRegistry** | @Component（由 AutoConfig 创建） | `openjiuwenStrategyRegistry` | 始终注册 | 是 |
| **AgentClient** | @Component（由 AutoConfig 创建） | `openjiuwenAgentClient` | `@ConditionalOnMissingBean` | 是 |
| **ReActStrategy** | @Component | `reactStrategy` | `@ConditionalOnMissingBean` | 是 |
| **DeepStrategy** | @Component | `deepStrategy` | `@ConditionalOnMissingBean` | 是 |
| **WorkflowStrategy** | @Component | `workflowStrategy` | `@ConditionalOnMissingBean` | 是 |
| **PlanExecuteVerifyStrategy** | @Component | `planExecuteVerifyStrategy` | `@ConditionalOnMissingBean` | 是 |
| **AdaptiveStrategy** | @Component | `adaptiveStrategy` | `@ConditionalOnMissingBean` | 是 |
| **GuardrailEngine** | @Component | `openjiuwenGuardrailEngine` | `@ConditionalOnMissingBean` | 是 |
| **MemoryStore** | @Component | `openjiuwenMemoryStore` | `@ConditionalOnMissingBean` + type=in-memory | 是 |
| **VectorStoreMemoryStore** | @Component | `openjiuwenVectorMemoryStore` | `@ConditionalOnClass(VectorStore)` | 是 |
| **CheckpointStore** | @Component | `openjiuwenCheckpointStore` | `@ConditionalOnMissingBean` + type=in-memory | 是 |
| **RedisCheckpointStore** | @Component | `openjiuwenRedisCheckpointStore` | `@ConditionalOnClass(RedisConnectionFactory)` + type=redis | 是 |
| **JdbcCheckpointStore** | @Component | `openjiuwenJdbcCheckpointStore` | `@ConditionalOnClass(DataSource)` + type=jdbc | 是 |
| **ChatModelAdapter** | @Component（由 AutoConfig 创建） | `openjiuwenChatModelAdapter` | `@ConditionalOnMissingBean` | 是 |
| **AgentMetrics** | @Component | `openjiuwenAgentMetrics` | `@ConditionalOnClass(MeterRegistry)` | 否 |
| **AgentKernelHealthIndicator** | @Component | `openjiuwenHealthIndicator` | `@ConditionalOnClass(HealthIndicator)` | 否 |
| **AgentListEndpoint** | @Endpoint | N/A | `@ConditionalOnProperty` | 否 |
| **AgentTaskEndpoint** | @Endpoint | N/A | `@ConditionalOnProperty` | 否 |
| **GuardrailAdvisor** | CallAdvisor | N/A | 始终注册 | 是 |
| **CheckpointAdvisor** | CallAdvisor | N/A | `@ConditionalOnProperty` | 是 |

### 4.2 Bean 依赖关系图

```
┌─────────────────────────────────────────────────────────────────┐
│                        Spring ApplicationContext                 │
│                                                                 │
│  ChatModel (Spring AI 提供)                                     │
│       │                                                         │
│       ▼                                                         │
│  ChatModelAdapter ──────────────────────────────┐               │
│       │                                          │               │
│       ▼                                          ▼               │
│  AgentKernel ←── AgentRegistry ←── @Agent Beans │               │
│       │               │                          │               │
│       │               └── AgentToolBridge ←─── ToolCallback[]   │
│       │                                    │                     │
│       │                                    └── MCP ToolCallbacks │
│       │                                                (来自     │
│       │                                McpToolCallbackAutoConfig)│
│       │                                                          │
│       ├── StrategyRegistry ←── ExecutionStrategy Beans           │
│       │         (react / deep / workflow / plan-execute / adaptive)│
│       │                                                          │
│       ├── MemoryStore ←── InMemory 或 VectorStoreMemoryStore     │
│       │                         (条件装配)                        │
│       │                                                          │
│       ├── CheckpointStore ←── InMemory / Redis / JDBC            │
│       │                           (条件装配)                      │
│       │                                                          │
│       └── GuardrailEngine                                        │
│                                                                 │
│  AgentClient ←── LocalAgentClient(内核)                         │
│                                                                 │
│  Actuator: HealthIndicator + AgentMetrics + AgentListEndpoint    │
│                         + AgentTaskEndpoint                      │
│                                                                 │
│  Advisor Chain: GuardrailAdvisor → CheckpointAdvisor → ChatModel │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 开发者替换 Bean 的方式

```java
// 方式 1：用 @Bean 覆盖（推荐）
@Configuration
public class MyAgentConfig {

    @Bean
    public MemoryStore memoryStore(RedisTemplate<String, String> redis) {
        return new RedisMemoryStore(redis); // 用 Redis 替代 InMemory
    }

    @Bean
    public ExecutionStrategy customStrategy() {
        return new MyCustomStrategy(); // 注册自定义策略
    }
}

// 方式 2：用 application.yml 配置（简单场景）
// openjiuwen.checkpoint.type=redis
// openjiuwen.memory.type=vector
// openjiuwen.agent.defaults.max-iterations=20

// 方式 3：用 @Primary 注解（多实现共存时）
@Primary
@Bean
public CheckpointStore primaryCheckpointStore(DataSource ds) {
    return new JdbcCheckpointStore(ds);
}
```

---

## 五、完整的 application.yml 示例

```yaml
# =============================================================================
# Openjiuwen-Java Runtime 配置示例
# Spring Boot 3.3+ / Java 21
# =============================================================================

spring:
  application:
    name: my-agent-service
  ai:
    # Spring AI 模型配置（Openjiuwen 直接使用，不重复配置）
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com
      chat:
        options:
          model: gpt-4o
          temperature: 0.7
    # 可选：DashScope（阿里云）
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-max
    # 可选：Ollama（本地模型）
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3
    # 可选：DeepSeek
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-chat

    # MCP Client 配置（连接远程 MCP Server）
    mcp:
      client:
        type: SYNC
        sse:
          connections:
            # 连接企业 Java 8 系统的 SDK 内嵌 MCP Server
            enterprise-tools:
              url: http://enterprise-app:8080/mcp/sse
        stdio:
          servers-configuration: classpath:mcp-servers.json

    # VectorStore 配置（长期记忆）
    vectorstore:
      pgvector:
        enabled: true
        connection-string: ${DATABASE_URL}
        initialize-schema: true

  # Actuator 配置
  management:
    endpoints:
      web:
        exposure:
          include: health,info,agents,tasks,prometheus,metrics
    endpoint:
      health:
        show-details: when-authorized
    metrics:
      tags:
        application: ${spring.application.name}
      export:
        prometheus:
          enabled: true

  # Spring Data 配置（CheckpointStore JDBC 后端）
  datasource:
    url: ${DATABASE_URL}
    driver-class-name: org.postgresql.Driver

  # Redis 配置（CheckpointStore Redis 后端）
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

# =============================================================================
# Openjiuwen Runtime 配置
# =============================================================================
openjiuwen:
  # 全局开关
  enabled: true

  # 默认模型（空 = 用 Spring AI 默认模型）
  default-model: ""

  # 并发与超时
  max-concurrent-tasks: 10000
  default-timeout: 5m

  # 默认执行策略（react / deep / workflow / plan-execute-verify / adaptive）
  default-strategy: react

  # Agent 扫描
  agent-scan-enabled: true
  base-packages:
    - com.mycompany.agents

  # ---------------------------------------------------------------------------
  # Agent 级配置
  # ---------------------------------------------------------------------------
  agent:
    # 所有 Agent 的默认值
    defaults:
      max-iterations: 10
      timeout: 5m

    # 按 Agent name 单独配置（覆盖注解和默认值）
    agents:
      # 订单服务 Agent
      order-service:
        model: deepseek-chat           # 用 DeepSeek 模型（省钱）
        max-iterations: 15
        timeout: 3m
        strategy: react

      # 审批 Agent（长流程，用 Deep Agent）
      approval-agent:
        model: gpt-4o                  # 用 GPT-4o（复杂推理）
        max-iterations: 50
        timeout: 30m
        strategy: deep

      # 客服 Agent（高并发，快速响应）
      customer-service:
        model: qwen-max               # 用通义千问（中文优化）
        max-iterations: 8
        timeout: 1m
        strategy: react

      # 数据分析 Agent（多步骤，用自适应策略）
      data-analyst:
        model: gpt-4o
        max-iterations: 30
        timeout: 15m
        strategy: adaptive             # Runtime 自动选择策略

  # ---------------------------------------------------------------------------
  # 记忆系统
  # ---------------------------------------------------------------------------
  memory:
    type: in-memory                    # in-memory / redis / vector
    max-sessions: 10000
    session-ttl: 1h

    # 长期记忆（语义搜索）
    long-term:
      enabled: true                    # 启用（需要 VectorStore Bean）
      top-k: 5                         # 搜索返回条数

  # ---------------------------------------------------------------------------
  # 检查点
  # ---------------------------------------------------------------------------
  checkpoint:
    type: redis                        # in-memory / redis / jdbc
    ttl: 24h
    save-every-step: true              # 生产环境：每步保存
    # jdbc:
    #   table-name: openjiuwen_checkpoints

  # ---------------------------------------------------------------------------
  # 安全护栏
  # ---------------------------------------------------------------------------
  guardrail:
    max-iterations: 50                 # 硬限制（不可被 @Agent 覆盖）
    max-token-budget: 100000           # 单任务 Token 预算
    max-sub-agent-depth: 3             # 子 Agent 递归深度
    human-approval-required: false     # 是否要求人工确认
    output-sanitization-enabled: true  # 输出内容审查
    tool-whitelist:                    # 工具白名单（空 = 允许所有）
      - checkOrder
      - refund
      - changeAddress
    sensitive-patterns:                # 敏感信息检测正则
      - "1[3-9]\\d{9}"               # 手机号
      - "\\d{17}[\\dXx]"             # 身份证号

  # ---------------------------------------------------------------------------
  # MCP 集成
  # ---------------------------------------------------------------------------
  mcp:
    enabled: true                      # 启用 MCP 工具发现
    tool-callbacks-enabled: true       # 将 MCP 工具转为 ToolCallback

  # ---------------------------------------------------------------------------
  # Actuator 端点
  # ---------------------------------------------------------------------------
  actuator:
    health-enabled: true               # 健康检查
    endpoints-enabled: true            # 自定义端点（agents / tasks）

# =============================================================================
# 日志配置
# =============================================================================
logging:
  level:
    com.openjiuwen: DEBUG
    org.springframework.ai: INFO
```

---

## 六、苏格拉底式自我诘问

### 诘问 1：ChatModelAdapter 是否引入了不必要的抽象层？

**问题**：为什么不直接让 Strategy 持有 ChatModel，而要在中间插一个 ChatModelAdapter？每一层抽象都有成本——维护成本、调试成本、理解成本。

**自我辩护**：ChatModelAdapter 做了五件事（错误处理、Token 统计、护栏注入、多模型路由、Streaming 适配）。如果让每个 Strategy 自己做这些，代码会重复五次。

**反驳**：这五件事是不是都可以用 Spring AI 的 Advisor 链做到？Token 统计用 Micrometer Observation（Spring AI 内置），护栏用 GuardrailAdvisor，多模型路由用 Spring AI 的 ChatModel 多 Bean + @Qualifier。如果都能用 Advisor 做，ChatModelAdapter 就是多余的。

**判定**：**ChatModelAdapter 需要瘦身**。它应该只保留两件事：多模型路由（Advisor 做不了）和 Streaming 适配（策略模式）。Token 统计和护栏检查移到 Advisor 链。如果只有这两件事，也许不需要单独的类——直接在 AgentKernel 里做路由就行。**这是一个需要验证的设计假设**。

---

### 诘问 2：AgentBeanPostProcessor vs ClassPathScanningCandidateComponentProvider——扫描时机对不对？

**问题**：用 BeanPostProcessor 扫描 @Agent 意味着只在 Spring Bean 上工作。如果开发者的 Agent 类不是 Spring Bean（比如通过 AgentClient.submit() 动态注册的临时 Agent），怎么办？

**自我辩护**：v2 设计明确说了"开发者写 @Agent 类就像写 @Service"——必须是 Spring Bean 才能被 IoC 容器管理、注入依赖、获得 AOP 增强。

**反驳**：Deep Agent 的子 Agent（SubAgentSpawner）是临时生成的。子 Agent 不需要被 Spring 管理，但它的工具需要被调用。如果子 Agent 的工具来自父 Agent 的 Spring Bean 里的 @Tool 方法，没问题。但如果子 Agent 需要独立的工具集呢？

**判定**：**需要区分两种 Agent**：
1. **注册型 Agent**（@Agent + @Component）→ BeanPostProcessor 扫描，注册到 AgentRegistry
2. **临时型 Agent**（SubAgentSpawner 动态创建）→ 不走 Spring 容器，由 SubAgentSpawner 直接构造

这不是架构缺陷，但需要在接口设计上明确这个区分。AgentRegistry 应该支持动态注册/注销。

---

### 诘问 3：GuardrailEngine（编排层）+ GuardrailAdvisor（ChatModel 层）双层防御是否过度设计？

**问题**：两个安全层做不同的事。GuardrailEngine 检查"工具调用是否允许、Token 预算、递归深度"；GuardrailAdvisor 检查"LLM 输入输出内容是否包含敏感信息"。但这两件事为什么不能放在同一层？

**自我辩护**：因为它们的检查粒度不同。GuardrailEngine 在 Agent 编排层，能看到整个 TaskContext（当前计划、已执行步骤、剩余预算）；GuardrailAdvisor 在 ChatModel 调用层，只能看到单次 Prompt。后者适合做内容安全，前者适合做行为安全。

**反驳**：企业环境真的需要两层吗？如果 GuardrailAdvisor 的内容检查是必需的，那它不应该挂在 Advisor 链上——因为 Advisor 是可选的、可排序的、可被开发者禁用的。内容安全应该是不可绕过的。

**判定**：**GuardrailAdvisor 不应该是 Advisor**。内容安全检查应该硬编码在 ChatModelAdapter 内部（或 AgentKernel 内部），不是可插拔的 Advisor。GuardrailEngine 做"行为安全"（预算、白名单），硬编码在 AgentKernel 内部。两层都在 Runtime 的核心路径上，不在 Advisor 链上。Advisor 链只放"可选增强"（检查点、日志、缓存），不放安全。**修正方案**：GuardrailAdvisor 改名为 AuditLogAdvisor（只做审计日志），安全检查移到 AgentKernel 核心路径。

---

### 诘问 4：application.yml 的 openjiuwen.agent.agents 按名字配置，与 @Agent 注解的配置冲突时谁优先？

**问题**：开发者可以在三个地方配置同一个 Agent：@Agent 注解、application.yml 的 defaults、application.yml 的 agents.{name}。三处配置冲突时的优先级是什么？

**自我辩护**：标准 Spring Boot 做法——yml 覆盖注解，specific 覆盖 defaults。优先级：agents.{name} > defaults > @Agent 注解。

**反驳**：这和 Spring AI 的行为不一致。Spring AI 的 ChatModel 配置是 yml 覆盖代码。但 @Agent 注解的值（name、strategy、maxIterations）是编译期固定的。如果 yml 说 strategy=deep，注解说 strategy=react，运行时用哪个？更重要的是——如果开发者把 @Agent(name="order-service") 改名为 @Agent(name="order-helper")，但忘了改 yml，会发生什么？

**判定**：**需要明确的三级合并策略**，并在文档中写死：

```
优先级：yml.agents.{name} > yml.defaults > @Agent 注解 > 代码默认值

具体规则：
1. Agent 注册以 @Agent(name=xxx) 为准——yml 中没有对应的 agents.{name} 不影响注册
2. 每个配置项独立合并——yml 可以只覆盖 model，不覆盖 maxIterations
3. 如果 yml.agents.order-service.model=deepseek-chat，但 @Agent(name="order-helper")，
   yml 配置不生效（名字不匹配），输出 WARN 日志
4. 启动时打印配置合并结果，让开发者看到每个 Agent 的最终配置
```

---

### 诘问 5：为什么自建 GuardrailEngine 而不是用 Spring AI 内置的 Guardrails？

**问题**：Spring AI 1.1+ 已经有 Guardrails 支持（内容审查、输入输出检查）。为什么还要自建 GuardrailEngine？

**自我辩护**：Spring AI 的 Guardrails 是围绕 ChatClient 的内容级别检查（输入文本是否有害、输出是否合规）。Openjiuwen 的 GuardrailEngine 是 Agent Runtime 级别的行为控制——Token 预算、工具白名单、递归深度限制、人工审批门控。两者不在同一个抽象层。

**反驳**：如果把 Spring AI 的 Guardrails 用在 ChatModel 层（内容安全），自建的 GuardrailEngine 用在编排层（行为安全），两层不冲突——就像诘问 3 讨论的那样。但既然诘问 3 已经判定内容安全应该硬编码在核心路径上，那 Spring AI 的 Guardrails 就不是"依赖"而是"可选增强"。

**判定**：**双轨并行**：
1. Spring AI Guardrails：如果开发者在 classpath 上引入了，Runtime 不阻止、不替代，让它在 Advisor 链上工作
2. 自建 GuardrailEngine：始终在 AgentKernel 核心路径上，做 Runtime 级别的行为控制
3. 不依赖 Spring AI Guardrails——没有它 Runtime 也能正常运行安全检查

这是"压榨 Spring AI 能力但不绑定"的体现。

---

## 附录：AutoConfiguration.imports 文件

```properties
# src/main/resources/META-INF/spring/
# org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenAutoConfiguration
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenAgentAutoConfiguration
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenStrategyAutoConfiguration
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenMemoryAutoConfiguration
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenGuardrailAutoConfiguration
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenCheckpointAutoConfiguration
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenMcpAutoConfiguration
com.openjiuwen.runtime.spring.autoconfigure.OpenJiuwenActuatorAutoConfiguration
```

## 附录：pom.xml 骨架

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.6</version>
    </parent>

    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>Openjiuwen Spring Boot Starter</name>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>1.1.0</spring-ai.version>
    </properties>

    <dependencies>
        <!-- Openjiuwen 核心接口 -->
        <dependency>
            <groupId>com.openjiuwen</groupId>
            <artifactId>openjiuwen-runtime-core</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Openjiuwen 执行策略 -->
        <dependency>
            <groupId>com.openjiuwen</groupId>
            <artifactId>openjiuwen-runtime-strategies</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Spring Boot（IoC + AutoConfiguration） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- Spring Boot Actuator（可选） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Spring AI ChatModel（可选，但几乎总是需要） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-model</artifactId>
            <version>${spring-ai.version}</version>
            <optional>true</optional>
        </dependency>

        <!-- Spring AI MCP Client（可选） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-mcp</artifactId>
            <version>${spring-ai.version}</version>
            <optional>true</optional>
        </dependency>

        <!-- Spring AI VectorStore（可选，长期记忆） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-vector-store</artifactId>
            <version>${spring-ai.version}</version>
            <optional>true</optional>
        </dependency>

        <!-- Reactor（Flux/Mono） -->
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
        </dependency>

        <!-- Micrometer（指标，Spring Boot 内置） -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Redis（可选，CheckpointStore / MemoryStore） -->
        <dependency>
            <groupId>org.springframework.data</groupId>
            <artifactId>spring-data-redis</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- JDBC（可选，CheckpointStore） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- 配置属性元数据生成 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

---

_最后更新：2026-06-07_
_前置文档：[v2 架构](./2026-06-06-openjiuwen-java-architecture-v2.md) | [Deep Agent 架构](./2026-06-06-openjiuwen-java-deep-agent-architecture.md) | [Beta LLM 自主编排](./2026-06-07-openjiuwen-beta-llm-autonomous-orchestration.md)_
_参考源码：Spring AI 1.1.x（ChatModel / ToolCallback / Advisor / MCP / VectorStore）| Spring AI Alibaba（AgentScopeFlowAutoConfiguration / A2aServerAutoConfiguration / McpToolCallbackAutoConfiguration）_
