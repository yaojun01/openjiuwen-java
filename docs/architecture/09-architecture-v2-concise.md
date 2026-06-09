# openjiuwen-java 架构设计

> 2026-06-06 v2 | 基于 Spring Boot，为企业 Java 体系设计
> 目标：简洁、直接、可迭代

---

## 一、设计目标

```
行业 Java 开发者，用 Spring Boot 写一个 Deep Agent，
应该像写一个 @Service 一样自然。
```

三个约束：
1. **不铲掉现有系统** — 扔进去就能跑，不改 Java 版本，不换框架
2. **可迭代** — 今天能跑 ReAct，明天加 Planning，后天加 SubAgent，不需要重构
3. **简洁** — 开发者只关心三件事：Agent 是谁、能用什么工具、怎么跑

---

## 二、三个 Artifact

就三个东西，不更多：

```
openjiuwen-java-core        ← 接口 + 注解 + 模型，纯 Java 21，零 Spring 依赖
openjiuwen-java-runtime     ← Spring Boot 执行引擎，依赖 core
openjiuwen-java-sdk         ← Java 8 桥接层，让老系统调用 runtime
```

### core 和 runtime 的分工

```
core 定义"是什么"：
  - Agent 长什么样（接口）
  - Tool 长什么样（注解）
  - 执行结果长什么样（record）
  - 事件长什么样（sealed interface）

runtime 实现"怎么跑"：
  - ReAct 循环怎么转
  - Deep Agent 怎么规划、怎么派子任务
  - 检查点怎么存、怎么恢复
  - Spring AI 的 ChatModel 怎么调
  - MCP 怎么连
```

**core 不依赖 Spring。runtime 依赖 Spring Boot。** 这样做的原因：
- 行业开发者可以只引 core 写 Agent 定义，在 IDE 里编译检查
- runtime 是可替换的——未来可以写 Quarkus 版的 runtime
- SDK 只依赖 core 的类型

---

## 三、开发者视角：写一个 Deep Agent

### 3.1 定义 Agent

```java
// 就像写一个 @Service
@Agent(
    name = "order-service",
    description = "订单服务助手，处理查单、退单、改地址等",
    model = "deepseek-chat"               // 用什么模型
)
public class OrderServiceAgent {

    // Agent 的系统提示——直接写在注解里，或者引用文件
    @SystemPrompt
    public String systemPrompt() {
        return """
            你是订单服务助手。
            可以查询订单状态、处理退款、修改收货地址。
            涉及金额操作时，必须先确认。
            """;
    }

    // 工具：标注在方法上，和 Spring 的 @Bean 一样直觉
    @Tool(description = "查询订单状态")
    public OrderStatus checkOrder(
        @Param(description = "订单号") String orderId
    ) {
        return orderService.getStatus(orderId);
    }

    @Tool(description = "申请退款")
    public RefundResult refund(
        @Param(description = "订单号") String orderId,
        @Param(description = "退款原因") String reason
    ) {
        return orderService.refund(orderId, reason);
    }

    @Tool(description = "修改收货地址")
    public AddressResult changeAddress(
        @Param(description = "订单号") String orderId,
        @Param(description = "新地址") String newAddress
    ) {
        return orderService.changeAddress(orderId, newAddress);
    }
}
```

**开发者需要关心的：就这些。** 定义 Agent 名字、系统提示、工具方法。其余的 runtime 全管。

### 3.2 调用 Agent

```java
// 方式 1：注入 AgentClient（同一个 Spring Boot 应用内）
@Autowired
private AgentClient agentClient;

public void handleUserMessage(String userId, String message) {
    AgentResult result = agentClient.invoke("order-service", message);
    System.out.println(result.output());
}

// 方式 2：流式（前端对话场景）
agentClient.stream("order-service", message, new AgentEventHandler() {
    @Override public void onThinking(String thought) { /* 显示思考过程 */ }
    @Override public void onToolCall(String tool, Map<String, Object> args) { /* 显示工具调用 */ }
    @Override public void onToolResult(String tool, Object result) { /* 显示工具结果 */ }
    @Override public void onComplete(String output) { /* 显示最终回答 */ }
    @Override public void onError(Exception e) { /* 处理错误 */ }
});

// 方式 3：异步长流程（审批等）
String taskId = agentClient.submit("order-service", "处理退款申请 #12345");
// ... 做别的事 ...
AgentResult result = agentClient.await(taskId, Duration.ofMinutes(5));

// 或者注册回调
agentClient.submit("order-service", "处理退款", task -> {
    if (task.status() == TaskStatus.PAUSED) {
        // Agent 暂停了（等人工确认）
        notifyHuman(task.pauseReason());
    }
});
```

### 3.3 Java 8 老系统调用（SDK）

```java
// 企业 Java 8 系统，通过 SDK 调用
AgentClient client = AgentClient.builder()
    .runtimeUrl("http://agent-runtime:8080")
    .apiKey("your-key")
    .build();

AgentResult result = client.invoke("order-service", "订单12345到哪了");
```

**就这些。** 老系统不需要知道 Agent 内部怎么跑的。

---

## 四、core：接口 + 注解 + 模型

### 4.1 Agent 定义

```java
/**
 * 标注一个类为 Agent。
 * runtime 会自动扫描并注册。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Agent {
    String name();
    String description() default "";
    String model() default "";          // 空 = 用 runtime 默认模型
    int maxIterations() default 10;
    String strategy() default "react";  // react / deep / workflow
}

/**
 * 系统提示。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemPrompt {
    String value() default "";          // 直接写提示
    String file() default "";           // 或者引用文件（classpath:prompts/xxx.md）
}

/**
 * 工具方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    String description();
}

/**
 * 工具参数。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Param {
    String description() default "";
    boolean required() default true;
}
```

### 4.2 执行模型

```java
/**
 * Agent 执行结果。
 */
public record AgentResult(
    String taskId,
    TaskStatus status,          // COMPLETED / FAILED / PAUSED
    String output,
    List<ToolCall> toolCalls,   // 这次执行调了哪些工具
    TokenUsage tokenUsage,
    Duration duration
) {}

public enum TaskStatus {
    CREATED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
}

public record ToolCall(
    String toolName,
    Map<String, Object> arguments,
    Object result,
    Duration duration
) {}

public record TokenUsage(
    int inputTokens,
    int outputTokens,
    String model
) {}

/**
 * Agent 事件（流式推送用）。
 */
public sealed interface AgentEvent
    permits ThinkingEvent, ToolCallEvent, ToolResultEvent,
            PlanEvent, SubTaskEvent, CompleteEvent, ErrorEvent {

    String taskId();
    Instant timestamp();
}

public record ThinkingEvent(String taskId, Instant timestamp, String content)
    implements AgentEvent {}

public record ToolCallEvent(String taskId, Instant timestamp,
                            String toolName, Map<String, Object> args)
    implements AgentEvent {}

public record ToolResultEvent(String taskId, Instant timestamp,
                              String toolName, Object result)
    implements AgentEvent {}

public record PlanEvent(String taskId, Instant timestamp,
                        String planId, List<String> steps, int currentStep)
    implements AgentEvent {}

public record SubTaskEvent(String taskId, Instant timestamp,
                           String subTaskId, String description, SubTaskStatus status)
    implements AgentEvent {}

public record CompleteEvent(String taskId, Instant timestamp, AgentResult result)
    implements AgentEvent {}

public record ErrorEvent(String taskId, Instant timestamp, String error)
    implements AgentEvent {}
```

### 4.3 执行策略接口

```java
/**
 * 执行策略。runtime 内置几个，开发者可以扩展。
 */
public interface ExecutionStrategy {

    /** 策略名 */
    String name();

    /** 执行 */
    Flux<AgentEvent> execute(TaskContext context);

    /** 从检查点恢复 */
    Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint);
}

/**
 * 任务上下文。每次执行创建一个新的，不共享。
 * Agent 本身无状态——状态全在 TaskContext 里。
 */
public record TaskContext(
    String taskId,
    String agentName,
    String userInput,
    String systemPrompt,
    String model,
    List<ToolDefinition> tools,
    MemoryStore memory,
    CheckpointStore checkpoints,
    VirtualFileSystem vfs        // Deep Agent 专用
) {}

/**
 * 检查点。
 */
public record Checkpoint(
    String checkpointId,
    String taskId,
    int stepIndex,
    String stateJson,            // 序列化的执行状态
    Instant timestamp
) {}
```

### 4.4 Store 接口

```java
/**
 * 记忆存储。实现可以是内存、Redis、数据库。
 */
public interface MemoryStore {
    Mono<Void> append(String sessionId, Message message);
    Flux<Message> load(String sessionId, int lastN);
    Mono<Void> clear(String sessionId);
}

/**
 * 检查点存储。
 */
public interface CheckpointStore {
    Mono<Void> save(Checkpoint checkpoint);
    Mono<Checkpoint> loadLatest(String taskId);
    Flux<Checkpoint> list(String taskId);
}

/**
 * 虚拟文件系统。Deep Agent 用它管理工作空间。
 */
public interface VirtualFileSystem {
    Mono<Void> write(String path, String content);
    Mono<String> read(String path);
    Flux<String> list(String dir);
    Mono<Void> delete(String path);
}
```

### 4.5 core 的 pom.xml 依赖

```xml
<!-- openjiuwen-java-core: 零 Spring 依赖 -->
<dependencies>
    <dependency>com.fasterxml.jackson.core:jackson-databind</dependency>
    <dependency>io.projectreactor:reactor-core</dependency>   <!-- Flux/Mono -->
    <!-- 就这两个。不多。 -->
</dependencies>
<java.version>21</java.version>
```

---

## 五、runtime：Spring Boot 执行引擎

### 5.1 自动配置

```java
/**
 * Spring Boot 自动配置。
 * 开发者只需要加 spring-boot-starter，不需要手动配任何东西。
 */
@AutoConfiguration
@ConditionalOnClass(ChatModel.class)   // 有 Spring AI 就生效
@EnableConfigurationProperties(OpenJiuwenProperties.class)
public class OpenJiuwenAutoConfiguration {

    @Bean
    public AgentRegistry agentRegistry(ApplicationContext ctx) {
        // 扫描所有 @Agent 注解的类，自动注册
        return new AgentRegistry(ctx.getBeansWithAnnotation(Agent.class));
    }

    @Bean
    public AgentRunner agentRunner(
        ChatModel chatModel,
        AgentRegistry registry,
        MemoryStore memory,
        CheckpointStore checkpoints
    ) {
        return new AgentRunner(chatModel, registry, memory, checkpoints);
    }

    @Bean
    public AgentClient agentClient(AgentRunner runner) {
        return new LocalAgentClient(runner);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    public MemoryStore inMemoryStore() {
        return new InMemoryMemoryStore();     // 默认内存，生产换 Redis
    }

    @Bean
    @ConditionalOnMissingBean(CheckpointStore.class)
    public CheckpointStore inMemoryCheckpointStore() {
        return new InMemoryCheckpointStore(); // 默认内存，生产换 Redis
    }
}
```

### 5.2 AgentRunner：执行引擎的核心

```java
/**
 * Agent 执行引擎。
 * 不持有状态。每次执行创建一个新的 TaskContext。
 * 线程安全——可以并发跑 10000 个 Task。
 */
public class AgentRunner {

    private final ChatModel chatModel;
    private final AgentRegistry registry;
    private final Map<String, ExecutionStrategy> strategies;

    public Flux<AgentEvent> run(String agentName, String input) {
        // 1. 从 registry 找到 Agent 定义
        AgentDefinition def = registry.get(agentName);

        // 2. 构建 TaskContext
        TaskContext ctx = new TaskContext(
            UUID.randomUUID().toString(),
            agentName, input,
            def.systemPrompt(),
            def.model(),
            def.toolDefinitions(),
            memoryStore,
            checkpointStore,
            vfs
        );

        // 3. 选择执行策略
        ExecutionStrategy strategy = strategies.get(def.strategy());

        // 4. 在虚拟线程里跑
        return strategy.execute(ctx)
            .subscribeOn(Schedulers.boundedElastic());  // 虚拟线程友好
    }
}
```

### 5.3 内置策略

#### ReActStrategy（最小可用）

```java
/**
 * ReAct 循环。最小实现。
 */
public class ReActStrategy implements ExecutionStrategy {

    @Override
    public String name() { return "react"; }

    @Override
    public Flux<AgentEvent> execute(TaskContext ctx) {
        return Flux.create(sink -> {
            int iteration = 0;
            List<Message> messages = initMessages(ctx);

            while (iteration < ctx.maxIterations()) {
                // 1. 调 LLM
                ChatResponse response = chatModel.call(messages);
                sink.next(new ThinkingEvent(ctx.taskId(), now(), response.content()));

                // 2. 有工具调用？
                if (response.hasToolCalls()) {
                    for (ToolCall call : response.toolCalls()) {
                        sink.next(new ToolCallEvent(ctx.taskId(), now(), call.name(), call.args()));
                        Object result = executeTool(ctx, call);
                        sink.next(new ToolResultEvent(ctx.taskId(), now(), call.name(), result));
                        messages.add(toolResultMessage(call, result));
                    }
                } else {
                    // 3. 没有工具调用 → 完成
                    sink.next(new CompleteEvent(ctx.taskId(), now(), buildResult(ctx, response)));
                    sink.complete();
                    return;
                }

                // 4. 保存检查点
                checkpointStore.save(buildCheckpoint(ctx, iteration, messages));

                iteration++;
            }

            // 超过最大迭代次数
            sink.next(new CompleteEvent(ctx.taskId(), now(), maxIterationResult(ctx)));
            sink.complete();
        });
    }
}
```

#### DeepStrategy（在 ReAct 基础上加三件东西）

```java
/**
 * Deep Agent = ReAct + Planning + SubAgent + VFS
 * 不是重写，是在 ReAct 上面叠能力。
 */
public class DeepStrategy implements ExecutionStrategy {

    private final ReActStrategy react;        // 复用 ReAct
    private final SubAgentSpawner spawner;
    private final VirtualFileSystem vfs;

    @Override
    public String name() { return "deep"; }

    @Override
    public Flux<AgentEvent> execute(TaskContext ctx) {
        return Flux.create(sink -> {
            // Phase 1: 规划
            // 让 LLM 调用 plan() 内置工具，生成步骤列表
            // plan() 不做任何事——它只是让 LLM 把想法写进 VFS
            Flux<AgentEvent> planPhase = react.execute(ctx.withExtraTools(List.of(
                new PlanTool(vfs),             // P2: 规划工具
                new NoteTool(vfs)              // 笔记工具
            )));

            // Phase 2: 按计划执行
            // 读 VFS 里的计划，每个步骤：
            //   - 简单步骤 → react.run()（复用 ReAct）
            //   - 复杂步骤 → spawner.spawn()（生成子 Agent）
            // 每步完成 → 更新 VFS 里的计划状态 → 保存检查点

            // Phase 3: 验证
            // 所有步骤完成 → 再跑一次 react 让 LLM 检查结果
            // 不满意 → 更新计划 → 重跑问题步骤
        });
    }
}
```

**关键设计**：DeepStrategy 不是"另一个 ReAct"，而是**把 ReAct 当组件用**。规划阶段是一次 ReAct 调用，每个子步骤也是一次 ReAct 调用。代码复用，不是重复。

### 5.4 SubAgentSpawner

```java
/**
 * 子 Agent 生成器。
 * 给主 Agent 一个 spawn() 工具，让它按需生成。
 */
public class SubAgentSpawner {

    private final AgentRunner runner;
    private final VirtualFileSystem vfs;

    /**
     * 生成子 Agent 工具。
     * 这个工具会被注册到主 Agent 的工具列表里。
     * LLM 自己决定什么时候调用。
     */
    public ToolDefinition spawnTool(String parentTaskId) {
        return ToolDefinition.builder()
            .name("spawn_sub_agent")
            .description("生成一个子 Agent 执行独立子任务。子 Agent 有独立的上下文窗口。")
            .parameter("task_description", String.class, "子任务描述", true)
            .parameter("tools", String.class, "子 Agent 可以使用的工具列表（逗号分隔）", false)
            .build(args -> {
                String desc = args.get("task_description").toString();
                String taskFile = "subtasks/" + UUID.randomUUID() + ".md";

                // 在 VFS 里写子任务描述
                vfs.write(taskFile, desc);

                // 跑子 Agent（复用 AgentRunner）
                AgentResult subResult = runner.run("sub-agent", desc).blockLast();

                // 子 Agent 结果写回 VFS
                vfs.write("results/" + taskFile, subResult.output());

                return "子任务完成，结果已写入 " + taskFile;
            });
    }
}
```

### 5.5 runtime 的 pom.xml 依赖

```xml
<!-- openjiuwen-java-runtime -->
<dependencies>
    <dependency>com.openjiuwen:openjiuwen-java-core</dependency>
    <dependency>org.springframework.boot:spring-boot-starter</dependency>
    <dependency>org.springframework.ai:spring-ai-openai-spring-boot-starter</dependency>
    <dependency>org.springframework.ai:spring-ai-mcp</dependency>
    <dependency>io.projectreactor:reactor-core</dependency>
</dependencies>
<java.version>21</java.version>
```

---

## 六、sdk：Java 8 桥接

SDK 只做一件事：**让 Java 8 系统通过 HTTP 调用 runtime**。

### 6.1 sdk-api（纯接口，Java 8）

```java
// SDK 只有这些类型——和 core 的事件模型不同（因为 Java 8 没有 sealed/record）
// SDK 用普通 class + 接口，序列化成 JSON 后和 runtime 的事件模型一一对应

public interface AgentClient {
    AgentResult invoke(String agentName, String input);
    CompletableFuture<AgentResult> invokeAsync(String agentName, String input);
    void stream(String agentName, String input, AgentEventHandler handler);
    String submit(String agentName, String input);
    TaskStatus getStatus(String taskId);
    AgentResult await(String taskId, Duration timeout);
    void resume(String taskId, String input);

    static AgentClientBuilder builder() { return new AgentClientBuilder(); }
}

public interface AgentEventHandler {
    default void onThinking(String thought) {}
    default void onToolCall(String tool, Map<String, Object> args) {}
    default void onToolResult(String tool, Object result) {}
    default void onComplete(String output) {}
    default void onError(Exception e) {}
}
```

### 6.2 sdk-remote（HTTP 客户端，Java 8）

```java
/**
 * Remote 模式 AgentClient。
 * HTTP + SSE + 轻量 MCP Server。
 */
public class RemoteAgentClient implements AgentClient {

    private final HttpClient httpClient;          // Java 8 HttpUrlConnection
    private final String baseUrl;
    private final String apiKey;
    private final LightweightMcpServer mcpServer; // 暴露 @Tool 方法给 runtime

    @Override
    public AgentResult invoke(String agentName, String input) {
        // POST /api/v1/agents/{agentName}/invoke
        // Body: { "input": "xxx" }
        // Response: { "taskId": "...", "status": "COMPLETED", "output": "..." }
        String json = httpClient.post(baseUrl + "/api/v1/agents/" + agentName + "/invoke",
            Map.of("input", input));
        return parseResult(json);
    }

    @Override
    public void stream(String agentName, String input, AgentEventHandler handler) {
        // GET /api/v1/agents/{agentName}/stream?input=xxx
        // SSE 事件流 → 转换为 handler 回调
        httpClient.sseGet(baseUrl + "/api/v1/agents/" + agentName + "/stream",
            Map.of("input", input),
            event -> {
                switch (event.type) {
                    case "thinking": handler.onThinking(event.data); break;
                    case "tool_call": handler.onToolCall(event.toolName, event.args); break;
                    case "tool_result": handler.onToolResult(event.toolName, event.result); break;
                    case "complete": handler.onComplete(event.data); break;
                    case "error": handler.onError(new AgentException(event.data)); break;
                }
            });
    }
}
```

### 6.3 sdk 的 pom.xml 依赖

```xml
<!-- openjiuwen-java-sdk: Java 8 -->
<dependencies>
    <dependency>com.fasterxml.jackson.core:jackson-databind</dependency>
    <!-- 就这一个。不多。 -->
</dependencies>
<java.version>1.8</java.version>
```

---

## 七、MCP 协议：工具跨 JVM 调用

SDK 内嵌一个轻量 MCP Server，让 runtime 能调用企业 JVM 里的 @Tool 方法。

```
企业 JVM（Java 8）                      Runtime JVM（Java 21）
┌────────────────────┐                 ┌────────────────────┐
│ SDK                 │                 │ Runtime             │
│                     │                 │                     │
│ @Tool 方法          │  ← MCP over ──→ │ MCP Client          │
│  ├ checkOrder()    │     HTTP         │  └ 调用工具          │
│  ├ refund()        │                 │                     │
│  └ changeAddress() │                 │ AgentRunner          │
│                     │                 │  └ ReAct 循环       │
│ LightweightMcpServer│                 │                     │
│  ├ Servlet 3.0 async│                 │                     │
│  └ 线程池            │                 │                     │
└────────────────────┘                 └────────────────────┘
```

**MCP Server 的并发处理**：
- Servlet 3.0 async（Tomcat/Jetty 都支持，Java 7+ 就有）
- 业务线程池：core = CPU 核数，max = CPU × 4
- 背压策略：CallerRunsPolicy（线程池满了 → 让调用方线程自己执行）

---

## 八、检查点：长流程不丢失

```
Agent 执行过程中的每一步：

Step 0: 用户输入 → 检查点 0（初始状态）
Step 1: LLM 推理 → 检查点 1（对话历史 + 推理结果）
Step 2: 工具调用 checkOrder → 检查点 2（工具结果加入历史）
Step 3: LLM 推理 → 检查点 3
Step 4: 工具调用 refund → 检查点 4
  ┃
  ┃ ← 进程崩溃 / 重启
  ┃
恢复：加载检查点 4 → 从 Step 4 之后继续
```

检查点存储可插拔：
- `InMemoryCheckpointStore` → 开发调试（默认）
- `RedisCheckpointStore` → 生产（亚毫秒读写）
- `JdbcCheckpointStore` → 审计（持久化，可查询历史）

---

## 九、迭代路线

不需要一次全做完。每一步都能跑：

```
v0.1: core + runtime + ReActStrategy
      → 能跑 Agent，能调工具
      → 验证：一个 @Agent 类 + 几个 @Tool 方法，跑通 ReAct 循环

v0.2: sdk-api + sdk-remote
      → Java 8 系统能调 Agent
      → 验证：一个 Java 8 的 main() 方法调通 runtime

v0.3: MCP Server in SDK
      → 工具可以注册在企业 JVM 里
      → 验证：runtime 调到 Java 8 系统里的 @Tool 方法

v0.4: DeepStrategy（先加 Planning）
      → Agent 能做计划，能更新计划
      → 验证：给一个 5 步任务，Agent 分解成 5 步依次执行

v0.5: SubAgentSpawner
      → Agent 能生成子 Agent
      → 验证：主 Agent 把子任务分给子 Agent，子 Agent 完成后主 Agent 汇总

v0.6: VirtualFileSystem
      → Agent 有持久化的工作空间
      → 验证：Agent 把中间结果写入 VFS，重启后能读回来

v0.7: CheckpointStore（Redis + JDBC）
      → Agent 能从断点恢复
      → 验证：杀掉 runtime 进程，重启后 Agent 从上次断点继续

v0.8: WorkflowStrategy + PlannerExecutorStrategy
      → 确定性工作流 + 规划-执行分离
      → 验证：用 YAML 定义一个审批工作流，跑通

v0.9: AgentEval
      → Agent 质量评估
      → 验证：跑 100 个测试用例，输出准确率/延迟/Token 消耗报告

v1.0: spring-boot-starter + 文档 + 示例
      → 开发者 5 分钟上手
```

---

## 十、文件结构

```
openjiuwen-java/
│
├── openjiuwen-java-core/              ← 纯接口 + 注解 + 模型
│   └── src/main/java/com/openjiuwen/java/core/
│       ├── @Agent                         ← Agent 定义注解
│       ├── @SystemPrompt                  ← 系统提示注解
│       ├── @Tool / @Param                 ← 工具注解
│       ├── AgentResult                    ← 执行结果 (record)
│       ├── AgentEvent                     ← 事件 (sealed interface)
│       ├── TaskStatus                     ← 任务状态 (enum)
│       ├── ExecutionStrategy              ← 策略接口
│       ├── TaskContext                    ← 上下文 (record)
│       ├── Checkpoint                     ← 检查点 (record)
│       ├── MemoryStore                    ← 记忆接口
│       ├── CheckpointStore                ← 检查点接口
│       └── VirtualFileSystem              ← VFS 接口
│
├── openjiuwen-java-runtime/           ← Spring Boot 执行引擎
│   └── src/main/java/com/openjiuwen/java/runtime/
│       ├── config/
│       │   └── OpenJiuwenAutoConfiguration   ← 自动配置
│       ├── AgentRegistry                      ← @Agent 扫描注册
│       ├── AgentRunner                        ← 执行引擎（无状态）
│       ├── AgentClient (LocalAgentClient)     ← 同 JVM 调用
│       ├── strategy/
│       │   ├── ReActStrategy                  ← v0.1
│       │   ├── DeepStrategy                   ← v0.4
│       │   └── WorkflowStrategy               ← v0.8
│       ├── deep/
│       │   ├── PlanTool                       ← 规划工具
│       │   ├── SubAgentSpawner                ← 子 Agent
│       │   └── NoteTool                       ← 笔记工具
│       ├── store/
│       │   ├── InMemoryMemoryStore
│       │   ├── InMemoryCheckpointStore
│       │   ├── RedisCheckpointStore
│       │   └── JdbcCheckpointStore
│       ├── vfs/
│       │   ├── InMemoryVFS
│       │   ├── LocalStorageVFS
│       │   └── S3VFS
│       ├── mcp/
│       │   └── McpClientBridge                ← MCP 工具调用
│       ├── api/                               ← REST API（给 SDK 调）
│       │   └── AgentController
│       └── observability/
│           └── AgentMetrics
│
├── openjiuwen-java-sdk/               ← Java 8 桥接
│   ├── openjiuwen-java-sdk-api/
│   │   └── src/main/java/com/openjiuwen/java/sdk/
│   │       ├── AgentClient (interface)
│   │       ├── AgentEventHandler
│   │       ├── AgentResult (class, Java 8)
│   │       └── TaskStatus (enum)
│   │
│   └── openjiuwen-java-sdk-remote/
│       └── src/main/java/com/openjiuwen/java/sdk/remote/
│           ├── RemoteAgentClient              ← HTTP 客户端
│           ├── LightweightMcpServer            ← MCP Server
│           └── ConnectionManager               ← 连接池 + 健康检查
│
└── openjiuwen-java-examples/          ← 示例项目
    ├── example-simple/                       ← 最简单的 Agent
    ├── example-enterprise-sdk/               ← Java 8 调用
    └── example-deep-agent/                   ← Deep Agent 示例
```

---

## 十一、架构总图

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  企业 Java 8 系统                企业 Java 21 系统                    │
│  ┌─────────────────────┐       ┌─────────────────────┐              │
│  │ Spring Boot 1.x/2.x │       │ Spring Boot 3.x/4.x │              │
│  │                      │       │                      │              │
│  │  业务代码            │       │  业务代码            │              │
│  │   ├ OrderService    │       │   ├ OrderService    │              │
│  │   ├ RefundService   │       │   └ RefundService   │              │
│  │                      │       │                      │              │
│  │  @Agent + @Tool      │       │  @Agent + @Tool      │              │
│  │  (在业务 JVM 里)     │       │  (在业务 JVM 里)     │              │
│  │                      │       │                      │              │
│  │  openjiuwen-java-sdk │       │  openjiuwen-java-    │              │
│  │   ├ RemoteAgentClient│       │  runtime (embedded)  │              │
│  │   └ MCP Server       │       │   ├ AgentRunner      │              │
│  └──────────┬───────────┘       │   └ DeepStrategy    │              │
│             │                    └─────────────────────┘              │
│             │ HTTP / MCP                                              │
└─────────────┼────────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────────────────────┐
│  openjiuwen-java-runtime（Java 21 + Spring Boot）                     │
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────────┐  │
│  │ ReActStrategy    │  │ DeepStrategy     │  │ WorkflowStrategy  │  │
│  │ (简单问答/单步)   │  │ (长程任务/多步)   │  │ (确定性流程)      │  │
│  └──────────────────┘  └──────────────────┘  └───────────────────┘  │
│          │                      │                       │             │
│          └──────────────────────┼───────────────────────┘             │
│                                 │                                     │
│  ┌──────────────────────────────▼──────────────────────────────────┐ │
│  │                     AgentRunner（无状态）                         │ │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌──────────────┐ │ │
│  │  │ChatModel│ │Memory  │ │Check-  │ │VFS     │ │MCP Client    │ │ │
│  │  │(SpringAI)│ │Store   │ │point  │ │       │ │(调远程工具)   │ │ │
│  │  └────────┘ └────────┘ │Store   │ └────────┘ └──────────────┘ │ │
│  │                        └────────┘                              │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  REST API (/api/v1/agents/{name}/invoke|stream|submit)          │ │
│  │  ← SDK 通过这个 API 调用 runtime                                │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 十二、核心设计原则总结

| 原则 | 体现 |
|------|------|
| **开发者只关心 @Agent + @Tool** | 三个注解定义完事，runtime 全自动 |
| **Agent 无状态** | AgentRunner 不持有状态，状态全在 TaskContext + Store 里 |
| **策略可插拔** | `strategy="react"` 改成 `strategy="deep"` 就行 |
| **Deep = ReAct + 三件套** | Planning / SubAgent / VFS 是叠加在 ReAct 上面的，不是重写 |
| **检查点每一步** | 中断不丢进度，恢复从断点继续 |
| **SDK 只有 Jackson** | Java 8 系统加一个依赖就能调 Agent |
| **Spring Boot 原生** | 不造 Spring 的轮子，用 Spring AI 的 ChatModel / VectorStore / MCP |
| **迭代友好** | v0.1 就能跑，每个版本加一个能力，不重构 |

---

_最后更新：2026-06-06 v2_
