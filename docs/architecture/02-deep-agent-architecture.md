# openjiuwen-java Deep Agent 架构推演：超越 AgentScope-Java

> 2026-06-06 | 从 SDK 兼容性到 Java Deep Agent 框架的架构迭代
> 方法：竞争基准 + 风险消除 + 差异化定位
> 输入：AgentScope-Java v1.0.12 能力清单 + Java AI Agent 生态调研（2026H1）+ 上一轮 SDK 架构未解决问题

---

## 零、从哪里来：上一轮留下的五个问题

上一轮架构推演（[2026-06-05](./2026-06-05-openjiuwen-enterprise-sdk-architecture.md)）解决了 SDK ↔ Runtime 的 Java 版本兼容性，但留下了五个悬而未决的问题：

| # | 问题 | 本原 | 为什么重要 |
|---|------|------|-----------|
| Q1 | MCP Server 并发模型 | Java 8 没有虚拟线程，SDK 内嵌的 MCP Server 怎么处理并发 | 100 并发 Agent 同时调工具时，线程池设计决定生死 |
| Q2 | SDK → Runtime 连接管理 | 长连接？短连接？连接池？心跳？ | 企业网络环境复杂，连接管理不当 = 超时雪崩 |
| Q3 | Agent 状态管理 | invoke() 返回后，Agent 如果还在后台跑（审批流程等），怎么查询/回调 | 企业场景大量异步流程，不是所有事都能同步等 |
| Q4 | 多 Runtime 实例 | 集群部署时 SDK 怎么做服务发现和负载均衡 | 生产环境不可能是单节点 |
| Q5 | @Tool 参数校验 | parameterSchema 是 JSON Schema 字符串，SDK 端校验还是完全信任 Runtime | 安全边界问题：谁守第一道门 |

这五个问题指向一个更大的命题：**openjiuwen-java 不能只是一个 SDK + Runtime 的桥接层，它必须是一个完整的 Java Agent 框架**。

---

## 一、竞争基准：AgentScope-Java 能力清单

> 数据来源：[官方文档](https://java.agentscope.io) + [GitHub Releases](https://github.com/agentscope-ai/agentscope-java/releases) v1.0.12
> 可信度：[官方] [当前有效]

### AgentScope-Java 做对了什么

| 维度 | 能力 | 评价 |
|------|------|------|
| **ReAct 循环** | 可配置 max iterations，安全中断，优雅取消 | ✅ 成熟 |
| **工具系统** | @Tool + @ToolParam 注解，自动 JSON Schema 提取，工具分组，动态启停 | ✅ 企业级 |
| **Hook 系统** | 12+ 事件类型，优先级排序，HITL（人在回路） | ✅ 精细控制 |
| **记忆** | 短期（InMemory）+ 长期（Mem0, ReMe），自动上下文压缩 | ✅ 双层设计 |
| **多 Agent** | Supervisor 模式，子 Agent（v1.1），A2A 协议，Fanout Pipeline | ✅ 覆盖主流模式 |
| **模型支持** | DashScope 原生 + OpenAI + Anthropic + Gemini + Ollama + DeepSeek + GLM | ✅ 7+ 提供商 |
| **生产特性** | 安全沙箱，OpenTelemetry，GraalVM Native Image，调度，在线训练 | ✅ 企业完备 |
| **响应式** | Project Reactor (Mono/Flux) 全链路非阻塞 | ✅ 高性能 |
| **生态集成** | Spring Boot 4.x, Quarkus, Micronaut starters | ✅ 多框架 |

### AgentScope-Java 的结构性弱点

| # | 弱点 | 根因 | 机会 |
|---|------|------|------|
| W1 | **Agent 实例有状态且非线程安全** | 每个 Agent 持有自己的 Memory + Toolkit，并发需要对象池 | 线程安全的 Agent 设计可以大幅降低运维复杂度 |
| W2 | **DashScope-first 偏向** | 阿里云生态优先，其他提供商是"二等公民" | 提供商中立设计赢得非阿里云客户 |
| W3 | **中国生态封闭** | 文档/社区/渠道以中文为主，西方开发者触达弱 | 国际化优先设计 |
| W4 | **无 Deep Agent 能力** | 只有 ReAct 循环，没有长程规划/子 Agent 协同/虚拟文件系统 | **Java 生态没有 GA 的 Deep Agent 实现——这是最大的空白** |
| W5 | **无检查点/持久化工作流** | Agent 执行中断后无法从断点恢复 | 企业长流程场景的刚需 |
| W6 | **版本间 Breaking Change** | v1.0.5 Session 存储不兼容；快速迭代带来升级风险 | 严格的向后兼容承诺 |
| W7 | **无 Agent 评估框架** | Java 生态没有任何 Agent 质量评估工具 | 内建评估能力是差异化利器 |

### 关键发现：Java Deep Agent 的真空地带

调研结果：

> **截至 2026 年中，Java 生态没有 GA 级别的 Deep Agent 实现。**
> - LangChain4j 的 Deep Agent 在 GitHub Issue [#4855](https://github.com/langchain4j/langchain4j/issues/4855) 上是 Roadmap 状态
> - LangGraph4j 有社区 Demo 但不是核心项目
> - Embabel 用 GOAP 规划（游戏 AI 路线），不是 LLM 驱动的 Deep Agent
> - Koog 有检查点但没有 Deep Agent 的四支柱

**这是 openjiuwen-java 的核心差异化机会：第一个 GA 级 Java Deep Agent 框架。**

---

## 二、Deep Agent 四支柱（LangChain 定义，Claude Code 验证）

> 来源：[LangChain Blog: Deep Agents](https://www.langchain.com/blog/deep-agents) + Claude Code 系统提示词逆向分析
> 可信度：[技术媒体] [当前有效]

| 支柱 | 含义 | 为什么重要 |
|------|------|-----------|
| **P1: 详尽的系统提示** | 长、复杂的系统提示，包含指令和 few-shot 示例 | 直接决定 Agent 行为质量；Claude Code 的系统提示被分析为"异常长且详细" |
| **P2: 规划工具** | 一个"什么都不做"的 Todo 列表工具，本质是上下文工程 | 不是让 Agent 真的管待办事项——是让 LLM 在每次推理时"看到"当前进度，保持聚焦 |
| **P3: 子 Agent** | 为主任务生成专注的子 Agent，每个处理独立子任务 | 实现上下文隔离：主 Agent 不需要把所有信息塞进一个窗口 |
| **P4: 文件系统** | 虚拟文件系统作为 Agent 间共享工作区、笔记、记忆的介质 | 长时间运行的 Agent 管理大量上下文的关键：内存放不下就写文件 |

---

## 三、openjiuwen-java 设计哲学

在展开架构之前，先明确设计哲学——这决定了所有的技术取舍。

### 哲学 1：Deep Agent First，不是 ReAct First

```
AgentScope-Java 的起点：ReAct 循环 → 在循环上加能力
openjiuwen-java 的起点：Deep Agent 四支柱 → ReAct 是其中一个执行策略
```

**含义**：
- openjiuwen-java 的核心抽象是 **Task**（有规划、有状态、有检查点的任务），不是 **Loop**（无限循环直到结束）
- ReActAgent 是 Task 的一种执行策略，不是唯一的执行策略
- DeepLoop、WorkflowLoop、PlannerExecutor 是不同的执行策略，但共享同一个 Task 抽象

### 哲学 2：线程安全 → 虚拟线程原生

```
AgentScope-Java：Agent 实例有状态，非线程安全 → 需要 Agent 池
openjiuwen-java：Agent 无状态（状态外部化）+ 虚拟线程 → 一个 Agent 实例服务万级并发
```

**含义**：
- Agent 的 Memory、Session、Context 不在 Agent 对象内部，而是通过外部 Store 注入
- Agent 本身是一个纯函数：`f(input, context) → output`
- Java 21 虚拟线程让"一个请求一个虚拟线程"的成本趋近于零
- 企业的 Java 8 系统通过 SDK Remote 模式使用，不需要关心虚拟线程

### 哲学 3：检查点优先 → 长流程不丢失

```
AgentScope-Java：Agent 中断 → 丢失进度
openjiuwen-java：Agent 每一步都可检查点 → 中断后从断点恢复
```

**含义**：
- 每个 Agent Step（推理/工具调用/观察）都是一个可序列化的检查点
- 检查点存储：内存（开发）→ Redis（生产）→ MySQL（审计）
- 这解决了 Q3（状态管理）和长流程场景

### 哲学 4：提供商中立 → 第一天就平等对待

```
AgentScope-Java：DashScope 原生，其他提供商需要额外依赖
openjiuwen-java：Spring AI ChatModel 抽象，所有提供商通过 Spring AI 接入
```

**含义**：
- openjiuwen-java Runtime 基于 Spring AI，天然获得 20+ 提供商支持
- 不绑定任何特定云服务商
- 企业的模型选择策略（多模型路由、A/B 测试、降级）直接用 Spring AI 的能力

---

## 四、架构迭代：从 SDK 桥接到 Deep Agent 框架

### 第 0 轮：上一轮的最终架构（起点）

```
企业系统（Java 8）          SDK（Java 8+）         Runtime（Java 21）
┌──────────┐    ┌──────────────────┐    ┌──────────────────┐
│ 业务代码  │    │ AgentClient      │    │ ReActLoop        │
│ @Tool    │←──→│ MCP Server       │←──→│ MCP Client       │
│          │    │ (Remote/Embedded)│    │ Spring AI        │
└──────────┘    └──────────────────┘    └──────────────────┘
```

**问题**：Runtime 只是"一个 ReActLoop + MCP Client"。它不是一个 Agent 框架。

### 第 1 轮：Runtime 升级为 Agent 框架

**设计**：Runtime 不再是一个简单的推理引擎，而是一个完整的 Agent 运行时，支持多种执行策略。

```
┌─────────────────────────────────────────────────────────────────────┐
│                    openjiuwen-java Agent Runtime                     │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │                     Agent Runtime Core                          ││
│  │                                                                 ││
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────────┐ ││
│  │  │ ReAct    │ │Deep      │ │Workflow  │ │PlannerExecutor   │ ││
│  │  │ Strategy │ │Strategy  │ │Strategy  │ │Strategy           │ ││
│  │  │(简单问答) │ │(长程任务) │ │(确定性流) │ │(复杂多步任务)      │ ││
│  │  └──────────┘ └──────────┘ └──────────┘ └───────────────────┘ ││
│  │          ↕ 公共抽象：Task / Step / Checkpoint / Event          ││
│  │                                                                 ││
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────────┐ ││
│  │  │Planning  │ │Sub-Agent │ │Virtual   │ │Structured        │ ││
│  │  │Tool      │ │Spawner   │ │FileSystem│ │Output            │ ││
│  │  │(P2: 规划) │ │(P3: 子Agent)│ │(P4: 文件) │ │(自纠错解析)       │ ││
│  │  └──────────┘ └──────────┘ └──────────┘ └───────────────────┘ ││
│  │                                                                 ││
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────────┐ ││
│  │  │Memory    │ │Checkpoint│ │Tool      │ │Event              │ ││
│  │  │Store     │ │Store     │ │Registry  │ │Bus                │ ││
│  │  └──────────┘ └──────────┘ └──────────┘ └───────────────────┘ ││
│  └─────────────────────────────────────────────────────────────────┘│
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │                     Spring AI Integration                       ││
│  │  ChatModel (20+ providers) │ MCP Client/Server │ Advisors      ││
│  │  ChatMemory │ VectorStore │ Structured Output                   ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

**关键抽象**：

```java
// ===== 核心抽象：Task（不是 Loop）=====
/**
 * Agent 任务。Deep Agent 的执行单元。
 * 一个 Task 有规划、有状态、有检查点。
 */
public sealed interface AgentTask
    permits SimpleTask, DeepTask, WorkflowTask, PlannerTask {

    /** 任务 ID（全局唯一，用于检查点恢复） */
    String taskId();

    /** 当前状态 */
    TaskStatus status();  // CREATED → PLANNING → EXECUTING → PAUSED → COMPLETED / FAILED

    /** 执行策略 */
    ExecutionStrategy strategy();

    /** 检查点（每一步都可以恢复） */
    List<Checkpoint> checkpoints();

    /** 事件流（实时推送推理过程） */
    Flux<AgentEvent> events();
}

// ===== 执行策略（不是只有 ReAct）=====
/**
 * 执行策略。不同的任务类型用不同的策略。
 */
public interface ExecutionStrategy {

    /** 策略名称 */
    String name();

    /** 执行一个 Task */
    Flux<AgentEvent> execute(AgentTask task, AgentContext context);

    /** 从检查点恢复 */
    Flux<AgentEvent> resume(AgentTask task, Checkpoint checkpoint);
}

// ===== 检查点 =========
/**
 * 检查点。Agent 每一步的状态快照。
 * 存储到 Store 后可以在任意时刻恢复。
 */
public record Checkpoint(
    String checkpointId,
    String taskId,
    int stepIndex,
    StepType stepType,       // REASONING / TOOL_CALL / TOOL_RESULT / OBSERVATION
    String content,          // 这一步的内容（JSON 序列化）
    Map<String, String> metadata,
    Instant timestamp
) implements Serializable {}
```

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| R1 | sealed interface 要求 Java 17+ | **HIGH** | Runtime 本身就是 Java 21，这不是问题——但需要确认 SDK 侧不依赖这些类型 |
| R2 | 检查点序列化复杂度 | **MEDIUM** | 工具调用的参数和结果需要完整序列化 |
| R3 | 多策略导致行为不一致 | **MEDIUM** | 不同的 Strategy 产生不同的事件流格式 |

**R1 不是问题**：Runtime 就是 Java 21。SDK（Java 8）不直接使用这些类型——它通过 HTTP/JSON 与 Runtime 交互。

**R2 和 R3 需要继续解决。**

---

### 第 2 轮：解决 Q1（MCP Server 并发）+ R2（检查点序列化）

#### Q1：SDK 内嵌 MCP Server 的并发模型

**问题回顾**：SDK 在 Java 8 环境里跑 MCP Server，100 并发 Agent 同时调工具，怎么处理？

**设计方案**：

```
SDK MCP Server 并发模型（Java 8+）

                    ┌──────────────────────┐
                    │  MCP Server           │
                    │  (SDK 内嵌)            │
                    │                       │
HTTP 请求 ──────→  │  ┌──────────────────┐ │
(Runtime 调工具)    │  │ Servlet Handler  │ │
                    │  │ (Servlet 3.0+    │ │
                    │  │  async supported)│ │
                    │  └───────┬──────────┘ │
                    │          │             │
                    │  ┌───────▼──────────┐ │
                    │  │ ToolExecutorPool │ │
                    │  │                  │ │
                    │  │ core = CPU 核数   │ │
                    │  │ max  = CPU × 4    │ │
                    │  │ queue = 1000     │ │
                    │  │                  │ │
                    │  │ 拒绝策略：        │ │
                    │  │ CallerRunsPolicy │ │
                    │  └───────┬──────────┘ │
                    │          │             │
                    │  ┌───────▼──────────┐ │
                    │  │ @Tool 方法执行    │ │
                    │  │ (业务 JVM 内)     │ │
                    │  └──────────────────┘ │
                    └──────────────────────┘
```

**关键设计决策**：

```java
// SDK 的 MCP Server 并发配置
public final class McpServerConfig {

    // 基础：Servlet 3.0 async（Java 7+ 就有）
    // 不需要虚拟线程，不需要 Reactor，不需要 Netty
    // Spring Boot 1.x/2.x 的 Tomcat/Jetty 都支持 Servlet 3.0 async

    private int corePoolSize;    // 默认：Runtime.getRuntime().availableProcessors()
    private int maxPoolSize;     // 默认：core × 4
    private int queueCapacity;   // 默认：1000
    private RejectedExecutionHandler rejectedHandler; // 默认：CallerRunsPolicy

    // CallerRunsPolicy 的含义：
    // 线程池满了 → 让调用线程（Tomcat worker thread）自己执行
    // 效果：自动背压——Runtime 发过来的请求被自然限流
    // 不是拒绝，不是报错，是"你自己跑"
}
```

**为什么不用更复杂的模型**：
- Java 8 没有 `CompletableFuture` 等异步原语？❌ Java 8 有 `CompletableFuture`
- 但 `@Tool` 方法是业务开发者写的同步代码——他们不想写异步
- 所以最简单的模型是：线程池 + 同步执行 + CallerRunsPolicy 背压
- Servlet 3.0 async 保证 HTTP 线程不被阻塞——线程池里的线程跑工具，HTTP 线程释放回去

#### R2：检查点序列化

**设计**：统一序列化策略

```java
// Runtime 侧（Java 21）
public interface CheckpointStore {

    /** 保存检查点 */
    Mono<Void> save(Checkpoint checkpoint);

    /** 加载最近的检查点 */
    Mono<Checkpoint> loadLatest(String taskId);

    /** 列出所有检查点 */
    Flux<Checkpoint> list(String taskId);
}

// 三种实现：
// 1. InMemoryCheckpointStore  → 开发调试
// 2. RedisCheckpointStore     → 生产（亚毫秒读写）
// 3. JdbcCheckpointStore      → 审计（持久化，可查询）

// 检查点的内容统一用 JSON：
// - LLM 的 ChatResponse → Jackson 序列化
// - 工具调用参数/结果 → Jackson 序列化
// - 内部状态 → record 直接序列化
// 所有类型都是 record 或 sealed interface → Jackson 原生支持
```

**风险扫描**：

| # | 风险 | 级别 | 状态 |
|---|------|------|------|
| ~~Q1~~ | ~~MCP Server 并发~~ | ✅ → **LOW** | Servlet 3.0 async + 线程池 + CallerRunsPolicy 背压 |
| ~~R2~~ | ~~检查点序列化~~ | ✅ → **LOW** | record/sealed interface + Jackson 原生支持 |

---

### 第 3 轮：解决 Q2（连接管理）+ Q4（服务发现）+ R3（行为一致性）

#### Q2：SDK → Runtime 连接管理

**问题**：Remote 模式下 AgentClient 怎么管理到 Runtime 的连接？

**设计方案**：

```java
// SDK 的连接管理（Java 8+）
public final class ConnectionManager {

    // === 连接池设计 ===
    // HTTP/1.1 + Keep-Alive（Java 8 的 HttpUrlConnection 天然支持）
    // 不是 HTTP/2（Java 8 不支持），不是 gRPC（需要额外依赖）

    private final List<RuntimeEndpoint> endpoints;  // Runtime 实例列表
    private final LoadBalancer loadBalancer;          // 负载均衡
    private final HealthChecker healthChecker;        // 健康检查

    // === 负载均衡策略 ===
    public sealed interface LoadBalancer
        permits RoundRobinBalancer, RandomBalancer, WeightedBalancer {

        RuntimeEndpoint select(List<RuntimeEndpoint> healthy);
    }

    // === 健康检查 ===
    // 每 30 秒 GET /actuator/health
    // 连续 3 次失败 → 标记为不健康，从负载均衡中移除
    // 恢复成功 → 重新加入

    // === 重试策略 ===
    // 失败 → 重试 1 次（换一个 Runtime 实例）
    // 再失败 → 返回错误给调用方
}

// === 心跳 / 长连接 ===
// 不需要 TCP 心跳。用 HTTP Keep-Alive + 健康检查就够了。
// 原因：Agent 调用不是高频持续连接，而是"请求-响应"模式
// SSE 流式推送：用 HttpUrlConnection 读 SSE 流，保持连接直到流结束
```

**为什么不用更复杂的连接管理**：
- SDK 是 Java 8，不能用 HTTP/2 Client（Java 11+）或 Reactor Netty
- Agent 调用的频率通常是"秒级到分钟级"，不是"毫秒级高频"
- HTTP Keep-Alive + 连接池已经足够处理延迟和吞吐需求
- 如果企业需要更高级的连接管理 → 升级到 Embedded 模式（Java 21）

#### Q4：多 Runtime 实例的服务发现

**设计方案**：

```java
// === 服务发现：三级方案 ===

// Level 1：静态配置（最简单，适合小规模）
AgentClient client = AgentClient.builder()
    .runtimeUrls("http://runtime-1:8080", "http://runtime-2:8080", "http://runtime-3:8080")
    .build();

// Level 2：Spring Cloud / Nacos 服务发现（中等规模，Java 11+）
AgentClient client = AgentClient.builder()
    .discovery(new NacosDiscovery("openjiuwen-runtime"))
    .build();

// Level 3：Kubernetes Service（大规模，任何 Java 版本）
// SDK 只需要知道 Kubernetes Service 的 DNS 名：
AgentClient client = AgentClient.builder()
    .runtimeUrl("http://openjiuwen-runtime:8080")  // K8s Service 自动负载均衡
    .build();
```

**关键判断**：SDK 不需要自己实现复杂的服务发现。企业已经有基础设施：
- K8s 环境 → 用 K8s Service
- Spring Cloud 环境 → 用 Nacos/Eureka
- 传统环境 → 静态配置

SDK 提供一个 `EndpointProvider` 接口，企业可以插任何发现机制：

```java
public interface EndpointProvider {
    /** 返回可用的 Runtime 端点列表 */
    List<String> getEndpoints();

    /** 端点变更时的回调（可选） */
    default void onEndpointsChanged(Consumer<List<String>> listener) {}
}
```

#### R3：多策略行为一致性

**设计方案**：统一事件模型

```java
/**
 * 统一的 Agent 事件。不管底层用什么 Strategy，事件格式一致。
 */
public record AgentEvent(
    String taskId,
    EventType type,           // REASONING / TOOL_CALL / TOOL_RESULT / PLANNING / SUB_TASK / COMPLETE / ERROR
    String content,           // JSON 格式的事件内容
    Map<String, String> metadata,
    Instant timestamp
) {}

// 不同的 Strategy 产生的事件类型相同，但 metadata 不同：
// - ReActStrategy: metadata 中有 iterationCount
// - DeepStrategy: metadata 中有 planId, subTaskId
// - WorkflowStrategy: metadata 中有 nodeId, edgeId
//
// 前端/SDK 只需要处理 EventType，不需要知道底层策略
```

**风险扫描**：

| # | 风险 | 级别 | 状态 |
|---|------|------|------|
| ~~Q2~~ | ~~连接管理~~ | ✅ → **LOW** | HTTP Keep-Alive + 健康检查 + 负载均衡 |
| ~~Q4~~ | ~~服务发现~~ | ✅ → **LOW** | EndpointProvider 接口 + 三级方案 |
| ~~R3~~ | ~~行为不一致~~ | ✅ → **LOW** | 统一 AgentEvent 模型 + 契约测试 |

---

### 第 4 轮：解决 Q3（Agent 状态管理）+ Q5（参数校验）

#### Q3：Agent 后续状态管理

**问题**：invoke() 返回后，Agent 如果还在后台跑，怎么查询/回调？

**设计方案**：三层状态管理

```java
// === 第一层：同步调用（简单场景）===
AgentResult result = agentClient.invoke("customer-service", "查订单状态");
// Agent 跑完才返回。适合简单问答。

// === 第二层：异步 + Future（中等场景）===
CompletableFuture<AgentResult> future = agentClient.invokeAsync("approval", "提交审批");
// 调用方可以继续做别的事，需要时 future.get()

// === 第三层：异步 + 回调（复杂场景，企业长流程）===
String taskId = agentClient.submit("approval", "提交三级审批", new AgentCallback() {
    @Override
    public void onEvent(AgentEvent event) {
        // 实时接收事件（SSE 推送）
    }

    @Override
    public void onComplete(AgentResult result) {
        // Agent 完成时回调
    }

    @Override
    public void onPause(PauseReason reason) {
        // Agent 暂停时回调（等待人工审批、等待外部系统）
        // reason 可能是：WAITING_FOR_HUMAN_INPUT, WAITING_FOR_EXTERNAL_SYSTEM
    }
});

// 之后可以查询状态
TaskStatus status = agentClient.getTaskStatus(taskId);

// 如果 Agent 暂停了（等待人工输入），可以恢复
agentClient.resumeTask(taskId, humanInput);
```

**关键设计**：

```java
// SDK 侧的状态查询（Java 8+）
public interface AgentClient {

    // ... 之前的 invoke/invokeAsync/stream ...

    /** 提交异步任务（后台执行，不阻塞调用方） */
    String submit(String agentName, String input, AgentCallback callback);

    /** 查询任务状态 */
    TaskStatus getTaskStatus(String taskId);

    /** 恢复暂停的任务（人工审批后） */
    void resumeTask(String taskId, String input);

    /** 取消正在执行的任务 */
    void cancelTask(String taskId);
}

// Runtime 侧的任务状态机
// CREATED → QUEUED → RUNNING → PAUSED（等待人工）→ RUNNING → COMPLETED
//                     ↘ FAILED
//                     ↘ CANCELLED
```

#### Q5：@Tool 参数校验

**设计方案**：双层校验

```
Runtime（Java 21）                         SDK（Java 8）
┌──────────────────┐                      ┌──────────────────┐
│ LLM 输出工具调用  │                      │                  │
│       │          │                      │                  │
│  ┌────▼────┐     │                      │                  │
│  │ 格式校验 │     │    JSON-RPC over     │  ┌────────────┐  │
│  │ (JSON   │     │ ──────HTTP──────→    │  │ 参数校验    │  │
│  │  Schema)│     │                      │  │ (Java 端)   │  │
│  └────┬────┘     │                      │  └──────┬─────┘  │
│       │          │                      │         │        │
│       │          │                      │  ┌──────▼─────┐  │
│       │          │                      │  │ @Tool 执行  │  │
│       │          │                      │  └────────────┘  │
└──────────────────┘                      └──────────────────┘
```

```java
// Runtime 侧（第一道门）：
// LLM 输出的工具调用参数 → JSON Schema 校验
// 校验失败 → 返回错误给 LLM，让它修正（ReAct 循环天然支持重试）

// SDK 侧（第二道门）：
// 接收到 MCP 工具调用 → Java 端参数校验
// 1. 类型校验：String/int/boolean 是否匹配
// 2. 必填校验：@ToolParam(required=true) 的参数是否存在
// 3. 自定义校验：开发者可以在 @Tool 方法里写校验逻辑

@ToolDefinition(name = "orderStatus", description = "查询订单状态")
public ToolResult checkOrderStatus(
    @ToolParam(name = "orderId", description = "订单号", required = true)
    String orderId
) {
    // 开发者自己的校验（可选）
    if (orderId == null || orderId.length() != 16) {
        return ToolResult.error("订单号格式错误：必须是16位");
    }
    // 业务逻辑...
}
```

**风险扫描**：

| # | 风险 | 级别 | 状态 |
|---|------|------|------|
| ~~Q3~~ | ~~Agent 状态管理~~ | ✅ → **LOW** | submit + callback + 检查点恢复 |
| ~~Q5~~ | ~~参数校验~~ | ✅ → **LOW** | 双层校验：Runtime JSON Schema + SDK Java 类型 |

---

### 第 5 轮：Deep Agent 核心设计

**这是 openjiuwen-java 最核心的差异化能力。**

#### DeepStrategy：四支柱的 Java 实现

```java
/**
 * Deep Agent 执行策略。
 * 实现 LangChain 定义的 Deep Agent 四支柱。
 */
public final class DeepStrategy implements ExecutionStrategy {

    // === P1: 详尽的系统提示 ===
    // 不是代码实现，而是设计原则：
    // - openjiuwen-java 提供系统提示模板引擎
    // - 模板包含：角色定义 + 能力说明 + 工具描述 + few-shot 示例
    // - 企业开发者通过 YAML/Markdown 文件定制系统提示
    // - Runtime 自动注入当前工具列表和上下文信息

    // === P2: 规划工具（PlanningTool）===
    private final PlanningTool planningTool;

    // === P3: 子 Agent 生成器（SubAgentSpawner）===
    private final SubAgentSpawner subAgentSpawner;

    // === P4: 虚拟文件系统（VirtualFileSystem）===
    private final VirtualFileSystem vfs;

    @Override
    public Flux<AgentEvent> execute(AgentTask task, AgentContext context) {
        return Flux.create(sink -> {
            // Phase 1: 规划
            // LLM 调用 PlanningTool 生成任务分解
            // PlanningTool 不是"真的规划"——它是让 LLM 把想法写下来
            // 作用：让后续每一步推理都能"看到"整体计划

            // Phase 2: 执行循环
            // 遍历计划中的每个子任务
            //   - 如果子任务需要分解 → 递归生成子 Agent
            //   - 如果子任务可以直接执行 → ReAct 循环
            //   - 每一步保存检查点

            // Phase 3: 验证
            // 所有子任务完成后，主 Agent 验证整体结果
            // 如果发现问题 → 更新计划 → 重新执行有问题的子任务
        });
    }
}
```

#### P2: PlanningTool 实现

```java
/**
 * 规划工具。让 Agent 把计划"写下来"。
 * 本质上是一个结构化的 Todo 列表——Agent 每次推理都能看到当前进度。
 */
@ToolDefinition(name = "plan", description = "创建或更新任务执行计划")
public record PlanningTool() {

    public record Plan(
        String planId,
        String goal,
        List<PlanStep> steps,
        PlanStatus status  // DRAFT / IN_PROGRESS / COMPLETED / REVISED
    ) {}

    public record PlanStep(
        int index,
        String description,
        StepStatus status,  // PENDING / IN_PROGRESS / COMPLETED / SKIPPED / FAILED
        String result,      // 这一步的结果（完成后填写）
        String assignedTo   // 分配给哪个子 Agent（可选）
    ) {}

    // 工具方法：
    // createPlan(goal) → 创建计划
    // updateStep(planId, stepIndex, status, result) → 更新步骤状态
    // revisePlan(planId, updatedSteps) → 修改计划（Agent 发现需要调整时）
}

// 关键设计：Plan 存储在 VirtualFileSystem 中
// 不是存在内存里——因为 Deep Agent 可能运行几分钟甚至几小时
// Plan 写入 vfs://plans/{planId}.json
// Agent 每次推理时自动加载当前 Plan 作为上下文
```

#### P3: SubAgentSpawner 实现

```java
/**
 * 子 Agent 生成器。为主 Agent 生成专注的子 Agent。
 * 每个子 Agent 有独立的上下文窗口——不与主 Agent 竞争 token。
 */
public interface SubAgentSpawner {

    /**
     * 生成一个子 Agent 执行子任务。
     *
     * @param parentTaskId 父任务 ID
     * @param subTaskDescription 子任务描述
     * @param tools 子 Agent 可以使用的工具（限定范围）
     * @return 子 Agent 的执行结果
     */
    Mono<AgentResult> spawn(String parentTaskId,
                            String subTaskDescription,
                            Set<String> allowedTools);

    /**
     * 生成一个子 Agent 并行执行（多个子任务同时跑）。
     */
    Flux<SubAgentEvent> spawnParallel(String parentTaskId,
                                       List<String> subTaskDescriptions,
                                       Set<String> allowedTools);
}

// 实现要点：
// 1. 子 Agent 是临时实例——用完即销毁
// 2. 子 Agent 的上下文从 VirtualFileSystem 加载（共享工作区）
// 3. 子 Agent 的结果写回 VirtualFileSystem（主 Agent 读取）
// 4. 子 Agent 的检查点独立存储（不影响主 Agent 的检查点）
```

#### P4: VirtualFileSystem 实现

```java
/**
 * 虚拟文件系统。Agent 间共享工作区、笔记、记忆的介质。
 * 解决长时运行 Agent 的上下文管理问题。
 */
public interface VirtualFileSystem {

    /** 写文件 */
    Mono<Void> write(String path, String content);

    /** 读文件 */
    Mono<String> read(String path);

    /** 列出目录 */
    Flux<VfsEntry> list(String directory);

    /** 删除文件 */
    Mono<Void> delete(String path);

    /** 文件是否存在 */
    Mono<Boolean> exists(String path);
}

// === 路径约定 ===
// vfs://plans/           → 规划文件（PlanningTool 写入）
// vfs://notes/           → Agent 笔记（推理过程中的观察、总结）
// vfs://results/         → 子 Agent 的执行结果
// vfs://context/         → 共享上下文（企业数据摘要、业务规则等）
// vfs://scratch/         → 临时工作区（Agent 执行过程中的中间文件）

// === 存储后端 ===
// 开发环境：InMemoryVFS（纯内存，重启丢失）
// 生产环境：LocalStorageVFS（映射到实际文件系统，持久化）
// 分布式环境：S3VFS / MinioVFS（对象存储，多节点共享）
```

---

### 第 6 轮：openjiuwen-java Maven 模块设计

```
openjiuwen-java/
│
├── openjiuwen-sdk-api/                    ← 纯接口，Java 8+，零依赖
│   ├── AgentClient.java                   ← 企业开发者入口
│   ├── AgentCallback.java                 ← 异步回调
│   ├── AgentResult.java                   ← 执行结果
│   ├── AgentEvent.java                    ← 事件
│   ├── TaskStatus.java                    ← 任务状态
│   ├── ToolDefinition.java               ← @Tool 注解
│   ├── ToolParam.java                    ← @ToolParam 注解
│   ├── ToolResult.java                    ← 工具执行结果
│   ├── AgentEventHandler.java             ← 流式事件回调
│   └── EndpointProvider.java              ← 服务发现接口
│
├── openjiuwen-sdk-remote/                 ← Remote 模式，Java 8+
│   ├── RemoteAgentClient.java             ← HTTP + SSE
│   ├── ConnectionManager.java             ← 连接池 + 健康检查 + 负载均衡
│   ├── LightweightMcpServer.java          ← MCP Server（Servlet 3.0 async + 线程池）
│   ├── ToolScanner.java                   ← @Tool 注解扫描
│   ├── ToolValidator.java                 ← 参数校验（Q5）
│   └── McpServerConfig.java               ← 并发配置（Q1）
│
├── openjiuwen-sdk-embedded/               ← Embedded 模式，Java 21
│   ├── EmbeddedAgentClient.java           ← 直接调用 Runtime
│   └── EmbeddedToolBridge.java            ← 本地工具注册
│
├── openjiuwen-sdk-all/                    ← 胖 JAR（自动检测模式）
│
├── openjiuwen-runtime/                    ← Agent Runtime，Java 21 + Spring Boot 3.3+
│   ├── runtime-core/                      ← 核心抽象
│   │   ├── AgentTask.java                 ← sealed interface
│   │   ├── ExecutionStrategy.java         ← 策略接口
│   │   ├── ReActStrategy.java             ← ReAct 循环
│   │   ├── DeepStrategy.java              ← Deep Agent（四支柱）
│   │   ├── WorkflowStrategy.java          ← 确定性工作流
│   │   ├── PlannerExecutorStrategy.java   ← 规划-执行分离
│   │   ├── Checkpoint.java               ← 检查点
│   │   ├── AgentEvent.java               ← 统一事件
│   │   └── AgentContext.java              ← 上下文（无状态设计）
│   │
│   ├── runtime-deep/                      ← Deep Agent 核心（核心差异化）
│   │   ├── PlanningTool.java              ← P2: 规划工具
│   │   ├── SubAgentSpawner.java           ← P3: 子 Agent
│   │   ├── VirtualFileSystem.java         ← P4: 虚拟文件系统
│   │   ├── InMemoryVFS.java              ← 内存实现
│   │   ├── LocalStorageVFS.java          ← 本地文件系统实现
│   │   └── S3VFS.java                    ← S3/Minio 实现
│   │
│   ├── runtime-checkpoint/                ← 检查点存储
│   │   ├── CheckpointStore.java           ← 接口
│   │   ├── InMemoryCheckpointStore.java   ← 开发
│   │   ├── RedisCheckpointStore.java      ← 生产
│   │   └── JdbcCheckpointStore.java       ← 审计
│   │
│   ├── runtime-memory/                    ← 记忆系统
│   │   ├── ShortTermMemory.java           ← 对话历史（Spring AI ChatMemory）
│   │   ├── LongTermMemory.java            ← 语义搜索（Spring AI VectorStore）
│   │   └── AutoContextManager.java        ← 自动上下文压缩
│   │
│   ├── runtime-tools/                     ← 内置工具
│   │   ├── StructuredOutputTool.java      ← 自纠错结构化输出
│   │   ├── RAGTool.java                  ← 检索增强生成
│   │   └── CodeExecutionTool.java         ← 安全代码执行
│   │
│   ├── runtime-observability/             ← 可观测性
│   │   ├── OpenTelemetryIntegration.java  ← 分布式追踪
│   │   ├── AgentMetrics.java              ← Agent 指标（token 消耗、延迟、成功率）
│   │   └── AuditLogger.java              ← 审计日志
│   │
│   └── runtime-spring-boot-starter/       ← Spring Boot 自动配置
│       └── OpenJiuwenAutoConfiguration.java
│
└── openjiuwen-eval/                       ← Agent 评估框架（差异化能力）
    ├── AgentEvaluator.java                ← 评估器接口
    ├── TaskBenchmark.java                 ← 任务基准测试
    ├── QualityMetrics.java               ← 质量指标
    └── RegressionTester.java              ← 回归测试（Agent 升级后质量不退化）
```

---

### 第 7 轮：最终风险扫描

| # | 风险 | 级别 | 缓解措施 |
|---|------|------|---------|
| Q1 | MCP Server 并发 | **LOW** | Servlet 3.0 async + 线程池 + CallerRunsPolicy |
| Q2 | 连接管理 | **LOW** | HTTP Keep-Alive + 健康检查 + EndpointProvider |
| Q3 | Agent 状态管理 | **LOW** | submit + callback + 检查点 + 状态机 |
| Q4 | 服务发现 | **LOW** | EndpointProvider 接口 + K8s/Nacos/静态配置 |
| Q5 | 参数校验 | **LOW** | 双层校验：Runtime JSON Schema + SDK Java 类型 |
| R1 | sealed interface Java 17+ | ✅ 不是问题 | Runtime = Java 21，SDK 不直接使用这些类型 |
| R2 | 检查点序列化 | **LOW** | record + Jackson 原生支持 |
| R3 | 策略行为不一致 | **LOW** | 统一 AgentEvent + 契约测试 |
| R4 | VirtualFileSystem 存储后端选型 | **LOW** | 可插拔：InMemory → Local → S3 |
| R5 | DeepStrategy 递归深度 | **MEDIUM** | 子 Agent 最大深度限制（默认 3 层） |
| R6 | 子 Agent 资源隔离 | **MEDIUM** | 每个 Agent 有 token 预算和超时限制 |

**R5 和 R6 是仅剩的 MEDIUM 风险，都有明确的缓解路径（配置限制），不需要继续迭代架构设计。**

**零 HIGH 风险。所有 MEDIUM+ 风险都有缓解措施。**

---

## 五、能力对比：openjiuwen-java vs AgentScope-Java

| 维度 | AgentScope-Java v1.0.12 | openjiuwen-java（设计） | 判定 |
|------|------------------------|------------------------|------|
| **执行模型** | ReAct 循环 | ReAct + Deep + Workflow + PlannerExecutor | **超越** |
| **Deep Agent** | ❌ 无 | ✅ 四支柱（规划/子Agent/VFS/长提示） | **独有** |
| **检查点/恢复** | ❌ 无（Session 是持久化，不是检查点） | ✅ 每步检查点 + 多存储后端 | **超越** |
| **Agent 线程安全** | ❌ 有状态，需要对象池 | ✅ 无状态设计，虚拟线程原生 | **超越** |
| **提供商中立** | ❌ DashScope-first | ✅ Spring AI ChatModel（20+） | **超越** |
| **工具系统** | @Tool + @ToolParam + 分组 + MCP | @Tool + @ToolParam + MCP + 双层校验 | **持平** |
| **记忆系统** | 短期 + 长期 + 自动压缩 | 短期 + 长期 + 自动压缩 + VFS 持久化 | **微超** |
| **多 Agent** | Supervisor + A2A + Fanout | Supervisor + SubAgent + A2A | **持平**（A2A 都支持） |
| **生产特性** | 沙箱 + OTel + GraalVM + 调度 | OTel + 指标 + 审计 + 检查点 | **各有侧重** |
| **SDK Java 8 兼容** | ❌ 最低 Java 17 | ✅ SDK Java 8 / Runtime Java 21 | **超越** |
| **Spring 集成** | Starter 可用 | 原生（Runtime 基于 Spring Boot） | **超越** |
| **Agent 评估** | ❌ 无 | ✅ 内建评估框架 | **独有** |
| **社区/生态** | 阿里生态，60+ 贡献者 | 从零开始 | **AgentScope 大幅领先** |

### openjiuwen-java 的三大差异化优势

1. **Java 生态唯一的 GA 级 Deep Agent** — 规划 + 子 Agent + VFS + 检查点，没有任何 Java 框架做到
2. **SDK Java 8 + Runtime Java 21 双层架构** — 不要求企业升级 Java 版本就能用 Agent
3. **Agent 无状态 + 虚拟线程** — 运维复杂度从"管理 Agent 对象池"降到"不需要管"

### openjiuwen-java 的主要劣势

1. **社区从零** — AgentScope 有阿里背书 + 60+ 贡献者 + 15 个版本迭代
2. **生产验证为零** — AgentScope 在阿里云经过实战，openjiuwen-java 还在纸上
3. **生态工具数** — AgentScope 有 20+ 扩展模块，openjiuwen-java 需要时间积累

---

## 六、吸引力方程：为什么开发者会选 openjiuwen-java？

### 对企业架构师的吸引力

```
"我的 Java 8 Spring Boot 2.x 系统，加两个 Maven 依赖，就能用上 Deep Agent。
 不升级 Java，不换框架，不动现有代码。"
```

### 对平台工程师的吸引力

```
"Agent 无状态 + 检查点恢复。我不需要管理 Agent 生命周期。
 虚拟线程处理并发。K8s 部署，水平扩展。"
```

### 对 AI 应用开发者的吸引力

```
"Deep Agent 四支柱是内置的。我不需要自己拼 LangGraph4j + 社区 Demo。
 规划工具、子 Agent、虚拟文件系统、长系统提示——开箱即用。"
```

### 演进能力

openjiuwen-java 的模块设计天然支持演进：

| 方向 | 现有模块支撑 | 演进路径 |
|------|-------------|---------|
| 更多执行策略 | `ExecutionStrategy` 接口 | MCTSStrategy / MonteCarloStrategy / BeamSearchStrategy |
| 更多存储后端 | `CheckpointStore` / `VirtualFileSystem` | CassandraVFS / PostgreSQLCheckpointStore |
| Agent 自进化 | `runtime-deep` 模块 | AgentEval + 自动微调 + 经验回放 |
| 多模态 | `AgentEvent` 统一模型 | ImageEvent / AudioEvent / VideoEvent |
| 跨语言 | MCP + A2A 协议 | Python SDK / Go SDK / TypeScript SDK |
| Agent 市场 | `ToolRegistry` | 可分享的 Agent 模板 + 工具包 |

---

## 七、下一步行动

| 优先级 | 行动 | 产出 |
|--------|------|------|
| **P0** | 实现 `runtime-core` + `ReActStrategy` | 可运行的最小 Agent Runtime |
| **P0** | 实现 `openjiuwen-sdk-api` + `openjiuwen-sdk-remote` | SDK Java 8 可用 |
| **P1** | 实现 `DeepStrategy` + `PlanningTool` + `SubAgentSpawner` | Deep Agent P2+P3 |
| **P1** | 实现 `VirtualFileSystem` + `InMemoryVFS` | Deep Agent P4 |
| **P1** | 实现 `CheckpointStore` + `InMemoryCheckpointStore` + `RedisCheckpointStore` | 检查点恢复 |
| **P2** | 实现 `WorkflowStrategy` + `PlannerExecutorStrategy` | 多策略支持 |
| **P2** | 实现 `openjiuwen-eval` | Agent 评估框架 |
| **P3** | 实现 `runtime-spring-boot-starter` | Spring Boot 自动配置 |
| **P3** | 实现 `runtime-observability` | 生产可观测性 |

---

_最后更新：2026-06-06_
_上一轮：[openjiuwen 企业 SDK 架构推演](./2026-06-05-openjiuwen-enterprise-sdk-architecture.md)_

---

## 信息来源

- [AgentScope-Java GitHub](https://github.com/agentscope-ai/agentscope-java) [官方] [当前有效]
- [AgentScope-Java 官方文档](https://java.agentscope.io) [官方] [当前有效]
- [Spring AI Alibaba vs AgentScope 定位](https://java2ai.com/en/blog/saa-agentscope-announcement) [官方] [当前有效]
- [Deep Agents - LangChain Blog](https://www.langchain.com/blog/deep-agents) [技术媒体] [当前有效]
- [Java AI Agent Frameworks 2026](https://codewiz.info/blog/java-ai-agent-frameworks-2026/) [技术媒体] [当前有效]
- [Red Hat: Java for Enterprise Agentic Apps](https://developers.redhat.com/articles/2026/01/13/case-building-enterprise-agentic-apps-java) [技术媒体] [当前有效]
- [LangChain4j Deep-Agents Roadmap #4855](https://github.com/langchain4j/langchain4j/issues/4855) [官方] [当前有效]
- [Koog 1.0 - JetBrains](https://blog.jetbrains.com/ai/2026/05/koog-1-0-is-out-stable-core-better-interop-and-multiplatform-observability/) [官方] [当前有效]
- [Autonomous Deep Agent - arXiv](https://arxiv.org/html/2502.07056v1) [原文] [当前有效]
- [AgentScope 1.0 arXiv Paper](https://arxiv.org/html/2508.16279v1) [原文] [当前有效]
