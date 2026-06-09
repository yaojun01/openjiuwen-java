# openjiuwen 企业 SDK 架构推演：风险消除循环

> 2026-06-05 | 从 Java 21 Runtime 到 Java 8+ 企业 SDK 的兼容性推演
> 方法：迭代式风险消除——每一轮识别 MEDIUM+ 风险，重新设计消除，直到全部降为 LOW

---

## 零、核心矛盾

```
Runtime 用 Java 21（虚拟线程 / sealed interface / record / pattern matching）
          ↕  张力
企业存量系统 Java 8/11/17（Spring Boot 1.x / 2.x / 3.x）
```

**本质问题**：SDK 是一个"桥梁"——它的一端插在企业 Java 8 的老系统里，另一端连着 Java 21 的 Runtime。桥梁本身用什么材料造？怎么保证两端都能接上？

---

## 第 0 轮：直接用 Runtime JAR 当 SDK

**设计**：企业系统直接 import Runtime 的 Maven 依赖

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| R1 | Java 8/11 应用无法加载 | **HIGH** | sealed interface / record 是编译期绑定，旧 JVM 的 ClassLoader 直接拒绝 |
| R2 | Java 17 应用无法用虚拟线程 | **HIGH** | `Executors.newVirtualThreadPerTaskExecutor()` 在 17 上不存在 |
| R3 | Spring Boot 2.x 不兼容 Spring AI | **MEDIUM** | Spring AI 1.x 要求 Spring Boot 3.2+ |
| R4 | Maven 依赖冲突 | **MEDIUM** | Runtime 依赖 Spring AI + Reactor，企业系统可能用不同版本 |

**结论**：❌ 不可行。直接把 Runtime JAR 塞给企业系统是死路。

---

## 第 1 轮：薄壳 HTTP 客户端

**设计**：SDK 只是一个 HTTP client，通过 REST 调用 Runtime 服务

```
企业系统（Java 8）                 Runtime 服务（Java 21）
┌──────────────┐  ──HTTP POST──→  ┌──────────────┐
│  SDK JAR      │                  │  Runtime      │
│  (纯 HTTP)    │  ←JSON response  │  (Agent 逻辑)  │
│  零 Spring    │                  │               │
└──────────────┘                  └──────────────┘
```

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| ~~R1~~ | ~~Java 8/11 无法加载~~ | ✅ 消除 | SDK 只有 HTTP client，纯 Java 8 |
| ~~R3~~ | ~~Spring Boot 版本冲突~~ | ✅ 消除 | SDK 零 Spring 依赖 |
| ~~R4~~ | ~~Maven 依赖冲突~~ | ✅ 消除 | SDK 零传递依赖 |
| R5 | **无法注册业务工具** | **HIGH** | 工具在业务 JVM 里（访问业务数据库/服务），Runtime 在另一个 JVM 里怎么调？ |
| R6 | 部署复杂度 | **MEDIUM** | 企业需要额外部署 Runtime 服务 |

**R5 是致命的**：Agent 的价值在于调用业务工具（查订单、审批流程、调用内部 API）。如果工具定义在业务 JVM 里，Runtime 在另一个 JVM 里，工具调用怎么跨 JVM？

**结论**：❌ 薄壳不够。必须解决工具跨 JVM 调用的问题。

---

## 第 2 轮：引入 MCP 协议解决工具跨 JVM

**设计**：SDK 内嵌一个轻量 MCP Server，暴露业务系统的 @Tool 方法。Runtime 作为 MCP Client 调用这些工具。

```
企业系统（Java 8）                         Runtime 服务（Java 21）
┌──────────────────────────┐            ┌──────────────────┐
│  SDK                       │            │  Runtime          │
│                            │            │                   │
│  ┌────────────┐            │            │  ┌──────────────┐ │
│  │ @Tool 方法  │ ←─内部调用── │            │  │ MCP Client   │ │
│  │ (业务逻辑)  │            │            │  │              │ │
│  └──────┬─────┘            │            │  └──────┬───────┘ │
│         │                  │            │         │         │
│  ┌──────▼─────┐            │ ←MCP协议──→ │  ┌──────▼───────┐ │
│  │ MCP Server  │            │            │  │ ReActLoop    │ │
│  │ (SDK内嵌)   │            │            │  │ (推理+编排)   │ │
│  └────────────┘            │            │  └──────────────┘ │
│                            │            │                   │
│  ┌────────────┐            │            │                   │
│  │ AgentClient │───REST──→  │            │                   │
│  │ .invoke()   │            │            │                   │
│  └────────────┘            │            │                   │
└──────────────────────────┘            └──────────────────┘
```

**工具调用流程**：
1. 开发者在业务系统里写 `@Tool` 方法
2. SDK 扫描 `@Tool` → 注册到内嵌 MCP Server
3. Agent 调用 `AgentClient.invoke("查一下订单状态")`
4. Runtime 收到请求 → ReAct 循环开始 → 推理出需要调用 `orderStatus` 工具
5. Runtime 通过 MCP 协议调用 SDK 内嵌的 MCP Server
6. MCP Server 执行 `@Tool` 方法（在业务 JVM 内，可访问业务数据库）
7. 结果通过 MCP 返回 Runtime → Agent 继续推理

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| ~~R5~~ | ~~无法注册业务工具~~ | ✅ 消除 | MCP Server 暴露工具，Runtime 通过 MCP 调用 |
| R7 | SDK 需要内嵌 MCP Server | **MEDIUM** | MCP Server 实现有依赖（Spring AI MCP Server 要求 Java 17+） |
| R8 | 网络可靠性 | **MEDIUM** | Runtime ↔ SDK 的 MCP 调用走网络，断连怎么办 |
| ~~R6~~ | 部署复杂度 | **LOW** | 企业已经习惯部署中间件（Redis、MQ），Runtime 只是另一个中间件 |

**R7 需要解决**：Spring AI 的 MCP Server 依赖 Spring Boot 3.x + Java 17。我们要在 Java 8 里跑 MCP Server，必须自己实现。

**关键判断**：MCP 协议本质是 JSON-RPC over stdio 或 HTTP。一个最小 MCP Server 的核心代码约 500-800 行（解析 JSON-RPC、路由到 @Tool 方法、返回结果）。不需要 Spring AI 的重量级实现。

**结论**：⚠️ 方向正确，但需要自建轻量 MCP Server。继续迭代。

---

## 第 3 轮：自建轻量 MCP Server + 降级兜底

**设计**：SDK 内嵌一个零依赖的 MCP Server 实现（~600 行核心代码），同时解决网络可靠性问题。

**轻量 MCP Server 的依赖清单**：

```
SDK 的 Maven 依赖：
  ✅ com.fasterxml.jackson.core:jackson-databind（几乎所有 Java 项目已有）
  ✅ com.fasterxml.jackson.core:jackson-core
  ❌ 不依赖 Spring
  ❌ 不依赖 Spring AI
  ❌ 不依赖 Reactor
  ❌ 不依赖任何 Java 17+ API
```

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| ~~R7~~ | ~~MCP Server 依赖~~ | ✅ 消除 | 自建，只用 Jackson，Java 8 兼容 |
| R8 | 网络可靠性（Runtime ↔ SDK） | **MEDIUM** | MCP 调用走 HTTP，超时/断连 |

**R8 解决方案**：三层降级

```java
// SDK 内部的工具调用降级策略
public class ToolExecutionStrategy {

    // 第一选择：MCP 调用（正常模式）
    // Runtime 通过 MCP 调 SDK 内嵌的 MCP Server
    Object executeViaMcp(ToolCall call) { ... }

    // 降级 1：MCP 超时 → 重试 1 次（工具调用通常幂等）
    Object retryOnce(ToolCall call) { ... }

    // 降级 2：MCP 不可用 → 返回结构化错误给 Runtime
    // Agent 会收到 "[工具调用失败：orderStatus 服务不可用]"
    // Agent 可以选择换一个工具或直接回答
    Object fallbackError(ToolCall call) {
        return ToolResult.error("MCP 连接超时。工具：" + call.name());
    }
}
```

**Agent 本身就有容错能力**——LLM 收到工具调用失败的错误后，会尝试其他方式完成目标。这是 Agent 的天然优势：**工具失败不会导致系统崩溃，只会降低 Agent 的能力**。

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| ~~R8~~ | ~~网络可靠性~~ | ✅ → **LOW** | 三层降级 + Agent 天然容错。最坏情况：Agent 能力降级，系统不崩溃 |

**还有没有遗漏的 MEDIUM+ 风险？**

继续扫描：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| R9 | SDK API 没有 Java 21 特性会不会难用 | **MEDIUM** | 没有 sealed interface / record / Builder 模式不如 Lombok @Builder 方便 |
| R10 | 企业 Java 8 没有 CompletableFuture? | **LOW** | Java 8 有 CompletableFuture（Java 8 引入的） |
| R11 | YAML/XML 配置在企业环境 | **LOW** | Java 8 有 SnakeYAML；XML 解析是 JDK 内置的 |
| R12 | SSE 流式推送在 Java 8 | **LOW** | SDK 用 HttpUrlConnection 读 SSE 流，纯 Java 8 |
| R13 | 企业安全：Runtime 和 SDK 之间的认证 | **LOW** | API Key / mTLS，标准做法 |

**R9 需要解决**。

---

## 第 4 轮：Java 8+ API 人体工学设计

**问题**：不能用 sealed interface、record、pattern matching switch。API 会不会变丑？

**解决方案**：用 Java 8 的经典模式补偿

| Java 21 特性 | Java 8 替代 | 人体工学差异 |
|------------|-----------|-----------|
| `sealed interface Agent` | 普通接口 + 文档约束 + final 实现类 | 无差异（开发者不需要知道 sealed） |
| `record AgentResult(...)` | `final class AgentResult` + private 构造 + getter | 多 5 行样板，但 IDE 自动生成 |
| `switch (result.status())` | `if (result.status() == Status.COMPLETE)` | 微小，if-else 更通用 |
| `var ctx = ...` | `AgentContext ctx = ...` | 显式类型，更清晰 |
| virtual threads | SDK 不需要（并发在 Runtime 侧） | 无差异 |

**核心 API 示例（Java 8 兼容）**：

```java
// ===== 工具定义 =====
// 开发者只需要写 @Tool 注解 + 普通方法
@ToolDefinition(name = "orderStatus", description = "查询订单状态")
public ToolResult checkOrderStatus(
    @ToolParam(name = "orderId", description = "订单号") String orderId
) {
    Order order = orderService.findById(orderId);
    return ToolResult.success(order.getStatus());
}

// ===== Agent 调用 =====
// 同步（最简单）
AgentResult result = agentClient.invoke("customer-service", "我的订单到哪了");

// 异步
CompletableFuture<AgentResult> future = agentClient.invokeAsync(
    "customer-service", "我的订单到哪了");

// 流式
agentClient.stream("customer-service", "我的订单到哪了",
    new AgentEventHandler() {
        @Override
        public void onReasoning(AgentEvent event) {
            System.out.println("思考：" + event.getContent());
        }
        @Override
        public void onToolCall(AgentEvent event) {
            System.out.println("调用工具：" + event.getToolName());
        }
        @Override
        public void onComplete(AgentResult result) {
            System.out.println("完成：" + result.getOutput());
        }
    });

// ===== Agent 配置（Builder 模式，Java 8 手写 Builder）=====
AgentClient client = AgentClient.builder()
    .runtimeUrl("http://agent-runtime:8080")
    .apiKey("your-api-key")
    .connectTimeout(5000)
    .build();

AgentConfig config = AgentConfig.builder()
    .name("customer-service")
    .model("deepseek-chat")
    .maxIterations(10)
    .systemPrompt("你是客服助手")
    .tool(new CustomerServiceTools())  // 注册 @Tool 方法所在的实例
    .build();
```

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| ~~R9~~ | ~~API 难用~~ | ✅ → **LOW** | Builder + @Tool + 接口回调，标准 Java 8 模式，企业开发者熟悉 |

**还有没有遗漏的 MEDIUM+ 风险？**

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| R14 | 嵌入模式（Java 17/21 应用想一切在一个 JVM） | **MEDIUM** | 上面只设计了远程模式。Java 17/21 的应用可能不想部署独立 Runtime |
| R15 | Maven 依赖体积 | **LOW** | SDK + 轻量 MCP Server 约 200KB（Jackson 是唯一依赖） |
| R16 | 向后兼容性（SDK v1 → v2 API 变更） | **LOW** | 语义版本控制 + 接口不删方法只加方法 |

**R14 需要解决**。

---

## 第 5 轮：双模式（Embedded + Remote）

**设计**：SDK 自动检测运行模式

```java
// SDK 内部逻辑：
// 1. 检测 classpath 上有没有 Runtime 的类
// 2. 有 → embedded 模式（同一 JVM）
// 3. 没有 → remote 模式（HTTP/MCP 跨 JVM）

public final class AgentClientBuilder {

    public AgentClient build() {
        if (isEmbeddedMode()) {
            // classpath 上有 com.openjiuwen.runtime.AgentRuntime
            // → 直接调用，不走 HTTP
            return new EmbeddedAgentClient(runtimeInstance);
        } else {
            // → HTTP/MCP 远程调用
            return new RemoteAgentClient(httpClient, mcpServer);
        }
    }

    private boolean isEmbeddedMode() {
        try {
            Class.forName("com.openjiuwen.runtime.AgentRuntime");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

**两种模式的对比**：

| | Remote 模式 | Embedded 模式 |
|---|-----------|-------------|
| **Java 版本** | **8 / 11 / 17 / 21** | **21 only** |
| **Spring Boot** | **1.x / 2.x / 3.x / 4.x** | **3.3+** |
| **工具调用** | MCP 跨 JVM | 直接方法调用 |
| **延迟** | ~50ms（HTTP 开销） | ~0ms（方法调用） |
| **部署** | 需独立 Runtime 服务 | 一切在同一 JVM |
| **资源隔离** | ✅ Runtime 崩溃不影响业务 | ❌ 共享 JVM |
| **适用场景** | 生产环境、微服务架构 | 开发调试、单体应用 |

**SDK 的 Maven 模块拆分**：

```
openjiuwen-sdk/
├── openjiuwen-sdk-api/              ← 纯接口，Java 8+，零依赖
│   ├── AgentClient.java
│   ├── AgentConfig.java
│   ├── AgentResult.java
│   ├── ToolDefinition.java
│   ├── ToolResult.java
│   ├── AgentEventHandler.java
│   └── AgentEvent.java
│
├── openjiuwen-sdk-remote/           ← Remote 模式实现，Java 8+
│   ├── RemoteAgentClient.java       ← HTTP + MCP
│   ├── LightweightMcpServer.java    ← ~600 行，Java 8 兼容
│   └── ToolScanner.java             ← @Tool 注解扫描
│
├── openjiuwen-sdk-embedded/         ← Embedded 模式实现，Java 21
│   ├── EmbeddedAgentClient.java     ← 直接调用 Runtime
│   └── EmbeddedToolBridge.java      ← 本地工具注册
│
└── openjiuwen-sdk-all/              ← 胖 JAR，包含两种模式
    └── pom.xml                       ← Java 21（因为 embedded 要求 21）
```

**企业系统按需选择依赖**：

```xml
<!-- Java 8/11 企业系统：只引 remote -->
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-sdk-api</artifactId>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-sdk-remote</artifactId>
</dependency>

<!-- Java 21 新系统：引 all（自动选择 embedded 或 remote） -->
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-sdk-all</artifactId>
</dependency>
```

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| ~~R14~~ | ~~嵌入模式缺失~~ | ✅ 消除 | 双模式，自动检测 |
| R17 | 两种模式行为不一致 | **MEDIUM** | 同一个 API，底层行为不同，可能导致 bug |

---

## 第 6 轮：消除行为不一致

**R17 的根源**：Remote 模式走 HTTP/MCP，Embedded 模式走方法调用。网络延迟、序列化差异、错误处理都可能不同。

**解决方案：契约测试套件**

```java
// SDK 提供统一的契约测试，两种模式都必须通过同一套测试
public abstract class AgentClientContractTest {

    protected abstract AgentClient createClient();

    @Test
    public void invokeReturnsResult() {
        AgentClient client = createClient();
        AgentResult result = client.invoke("test-agent", "hello");
        assertEquals(AgentStatus.COMPLETED, result.status());
        assertNotNull(result.output());
    }

    @Test
    public void toolExecutionWorks() {
        // Remote 模式：MCP 调用
        // Embedded 模式：直接方法调用
        // 两者的结果必须一致
    }

    @Test
    public void streamingWorks() {
        // 两种模式的事件序列必须一致
    }

    @Test
    public void errorHandling() {
        // 两种模式的错误信息格式必须一致
    }
}

// Remote 模式的测试
public class RemoteAgentClientTest extends AgentClientContractTest { ... }

// Embedded 模式的测试
public class EmbeddedAgentClientTest extends AgentClientContractTest { ... }
```

**额外措施**：

1. **统一序列化**：两种模式用同一个 Jackson ObjectMapper 配置
2. **统一错误类型**：SDK API 层定义错误类型，底层异常统一转换
3. **统一事件模型**：AgentEvent 是同一个类，不管底层怎么传输

**风险扫描**：

| # | 风险 | 级别 | 原因 |
|---|------|------|------|
| ~~R17~~ | ~~行为不一致~~ | ✅ → **LOW** | 契约测试 + 统一序列化/错误/事件 |

---

## 第 7 轮：全面扫描

| # | 风险 | 级别 | 状态 |
|---|------|------|------|
| R1 | Java 8/11 无法加载 | ~~HIGH~~ | ✅ 已消除：SDK API Java 8+ |
| R2 | Java 17 无法用虚拟线程 | ~~HIGH~~ | ✅ 已消除：虚拟线程只在 Runtime 侧 |
| R3 | Spring Boot 2.x 不兼容 | ~~MEDIUM~~ | ✅ 已消除：SDK 零 Spring 依赖 |
| R4 | Maven 依赖冲突 | ~~MEDIUM~~ | ✅ 已消除：SDK 仅依赖 Jackson |
| R5 | 无法注册业务工具 | ~~HIGH~~ | ✅ 已消除：MCP Server 暴露工具 |
| R6 | 部署复杂度 | ~~MEDIUM~~ | ✅ 已降为 LOW：标准中间件模式 |
| R7 | MCP Server 依赖 | ~~MEDIUM~~ | ✅ 已消除：自建轻量实现 |
| R8 | 网络可靠性 | ~~MEDIUM~~ | ✅ 已降为 LOW：三层降级 + Agent 容错 |
| R9 | API 人体工学 | ~~MEDIUM~~ | ✅ 已降为 LOW：Builder + @Tool + 接口 |
| R10 | CompletableFuture | — | ✅ 一直是 LOW：Java 8 引入 |
| R11 | YAML/XML 配置 | — | ✅ 一直是 LOW：SnakeYAML + JDK 内置 |
| R12 | SSE 流式 | — | ✅ 一直是 LOW：HttpUrlConnection |
| R13 | 认证安全 | — | ✅ 一直是 LOW：API Key / mTLS |
| R14 | 嵌入模式缺失 | ~~MEDIUM~~ | ✅ 已消除：双模式 |
| R15 | 依赖体积 | — | ✅ 一直是 LOW：~200KB |
| R16 | 向后兼容 | — | ✅ 一直是 LOW：语义版本 |
| R17 | 行为不一致 | ~~MEDIUM~~ | ✅ 已降为 LOW：契约测试 |

**所有 MEDIUM+ 风险已消除。**

---

## 最终架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    企业业务系统（Java 8 / 11 / 17 / 21）                │
│                                                                       │
│   业务代码                  openjiuwen SDK                              │
│  ┌──────────┐  ┌───────────────────────────────────────────────────┐   │
│  │ 订单服务  │  │                                                   │   │
│  │ 库存服务  │  │  @Tool 方法 ──→ ToolScanner ──→ MCP Server       │   │
│  │ 审批服务  │  │       (扫描注解)        (暴露工具，Java 8+)      │   │
│  │ ...      │  │                                                   │   │
│  └──────────┘  │  AgentClient ──→ ┌─────────────┐                 │   │
│                │                  │ Remote 模式  │──HTTP──→ Runtime │   │
│                │                  │ (Java 8+)   │                  │   │
│                │                  ├─────────────┤                  │   │
│                │                  │ Embedded 模式│──方法调用→ Runtime│   │
│                │                  │ (Java 21)   │                  │   │
│                │                  └─────────────┘                  │   │
│                │                                                   │   │
│                │  AgentEventHandler ←── 事件流 ←── Runtime         │   │
│                │  (onReasoning / onToolCall / onComplete)           │   │
│                └───────────────────────────────────────────────────┘   │
│                              │                                          │
│                              │ MCP (工具调用) / REST (Agent 调用)       │
│                              │ SSE (事件流)                            │
└──────────────────────────────┼──────────────────────────────────────────┘
                               │
                ┌──────────────▼──────────────────────────────────────┐
                │              openjiuwen Runtime（Java 21）            │
                │                                                       │
                │   ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
                │   │ ReActLoop│  │DeepLoop  │  │ WorkflowLoop     │  │
                │   └──────────┘  └──────────┘  └──────────────────┘  │
                │   ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
                │   │ Phase    │  │Middleware│  │ StateNode (MCTS) │  │
                │   └──────────┘  └──────────┘  └──────────────────┘  │
                │   ┌──────────────────────────────────────────────┐  │
                │   │  Spring AI ChatModel + 20+ Provider          │  │
                │   │  MCP Client → 调用 SDK 暴露的业务工具         │  │
                │   │  ChatMemory + VectorStore                    │  │
                │   └──────────────────────────────────────────────┘  │
                └───────────────────────────────────────────────────────┘
```

## SDK Maven 坐标与兼容性矩阵

| Maven 模块 | Java 8 | Java 11 | Java 17 | Java 21 | Spring Boot |
|-----------|--------|---------|---------|---------|-------------|
| `openjiuwen-sdk-api` | ✅ | ✅ | ✅ | ✅ | 1.x/2.x/3.x/4.x |
| `openjiuwen-sdk-remote` | ✅ | ✅ | ✅ | ✅ | 1.x/2.x/3.x/4.x |
| `openjiuwen-sdk-embedded` | ❌ | ❌ | ❌ | ✅ | 3.3+ |
| `openjiuwen-sdk-all` | ❌ | ❌ | ❌ | ✅ | 3.3+ |

## SDK 核心接口（5 个，Java 8+）

### 接口 1：AgentClient（入口）

```java
/**
 * Agent 客户端。企业开发者的唯一入口。
 * 底层自动选择 Remote 或 Embedded 模式。
 */
public interface AgentClient {

    /** 同步调用 */
    AgentResult invoke(String agentName, String input);

    /** 异步调用（CompletableFuture，Java 8 引入） */
    CompletableFuture<AgentResult> invokeAsync(String agentName, String input);

    /** 流式调用 */
    void stream(String agentName, String input, AgentEventHandler handler);

    /** 创建 Builder */
    static AgentClientBuilder builder() { return new AgentClientBuilder(); }
}
```

### 接口 2：AgentEventHandler（事件回调）

```java
/**
 * Agent 执行事件回调。
 * 所有方法有默认空实现——开发者只需覆写关心的。
 */
public interface AgentEventHandler {

    /** Agent 开始推理 */
    default void onReasoning(AgentEvent event) {}

    /** Agent 调用工具 */
    default void onToolCall(AgentEvent event) {}

    /** 工具返回结果 */
    default void onToolResult(AgentEvent event) {}

    /** Agent 完成 */
    default void onComplete(AgentResult result) {}

    /** Agent 出错 */
    default void onError(AgentException error) {}
}
```

### 接口 3：ToolProvider（工具注册）

```java
/**
 * 工具提供者。开发者实现此接口，或使用 @Tool 注解自动注册。
 */
public interface ToolProvider {

    /** 工具名称 */
    String name();

    /** 工具描述（给 LLM 看） */
    String description();

    /** 参数 JSON Schema */
    String parameterSchema();

    /** 执行工具 */
    ToolResult execute(Map<String, Object> parameters);
}
```

### 接口 4：ToolDefinition（工具注解）

```java
/**
 * 声明式工具定义。标注在方法上，SDK 自动扫描注册。
 * Java 8 注解，运行时保留。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolDefinition {
    String name();
    String description();
}
```

### 接口 5：AgentConfig（配置）

```java
/**
 * Agent 配置。Builder 模式，Java 8 手写。
 */
public final class AgentConfig {

    private final String name;
    private final String model;
    private final int maxIterations;
    private final String systemPrompt;
    private final List<Object> toolInstances; // @Tool 方法所在的实例
    private final Map<String, String> metadata;

    private AgentConfig(Builder builder) { ... }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        // 标准 Java 8 Builder 模式
        public Builder name(String name) { ... }
        public Builder model(String model) { ... }
        public Builder maxIterations(int max) { ... }
        public Builder systemPrompt(String prompt) { ... }
        public Builder tool(Object toolInstance) { ... }
        public AgentConfig build() { ... }
    }
}
```

## 最终风险清单（全部 LOW）

| # | 风险 | 级别 | 缓解措施 |
|---|------|------|---------|
| R6 | 部署复杂度 | LOW | Docker Compose / Helm chart + 自动发现 |
| R8 | 网络可靠性 | LOW | 三层降级 + Agent 天然容错 |
| R9 | API 人体工学 | LOW | Builder + @Tool + 接口回调，标准 Java 8 模式 |
| R15 | 依赖体积 | LOW | SDK ~200KB（仅 Jackson） |
| R16 | 向后兼容 | LOW | 语义版本控制 + 接口只加不删 |
| R17 | 行为不一致 | LOW | 契约测试套件 |

**零 MEDIUM / HIGH 风险。**

---

_最后更新：2026-06-05_
