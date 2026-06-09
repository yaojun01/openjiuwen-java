# openjiuwen-java 架构设计 v3

> 2026-06-06 v3 | Plan-Execute-Verify：Deep Agent 的正确执行模型
> 目标：简洁、直接、可迭代，与企业 Java 体系有机融合

---

## 一、设计目标

```
行业 Java 开发者，用 Spring Boot 写一个 Deep Agent，
应该像写一个 @Service 一样自然。
```

三个约束：
1. **不铲掉现有系统** — 扔进去就能跑，不改 Java 版本，不换框架
2. **可迭代** — 今天跑叶子任务，明天加规划，后天加验证，不需要重构
3. **简洁** — 开发者只关心三件事：Agent 是谁、能用什么工具、怎么跑

---

## 二、第一性原理：Deep Agent 的执行模型

### ReAct 不适合 Deep Agent

ReAct 的本质是扁平的顺序循环：`observe → think → act → observe → think → act → ...`

Deep Agent 的本质是：**把一个复杂目标，分解成有依赖关系的子任务网络，执行，验证，必要时局部重做。**

| Deep Agent 需要 | ReAct 怎么处理 | 问题 |
|----------------|---------------|------|
| 任务分解 | hack 一个 "plan tool" | plan tool 什么都不做，只是让 LLM 写想法 |
| 依赖关系 | 靠 LLM 自己记住 | 上下文窗口塞不下长链依赖 |
| 并行执行 | 不能 | 结构性限制，ReAct 是线性链 |
| 结果验证 | 再转一圈让 LLM 检查 | 没有结构化验证，靠 LLM 自觉 |
| 局部重做 | 不能 | 要么全跑，要么重来 |

**结论**：ReAct 适合单轮问答 + 1-3 次工具调用。Deep Agent 的执行模型应该从第一性原理重新设计。

### Plan-Execute-Verify：正确的模型

Deep Agent 做的事，本质上是三个不同的阶段：

```
1. 规划（Planning）  → 把目标分解成子任务图（DAG）
2. 执行（Execution） → 按依赖关系执行子任务，可并行
3. 验证（Verification）→ 检查结果质量，失败则局部重做
```

三个阶段需要的"大脑"不一样：

| 阶段 | 需要什么 | 谁来做 |
|------|---------|--------|
| 规划 | 理解目标、拆解任务、识别依赖 | LLM |
| 执行 | 调工具、跑代码、查数据 | 工具系统（确定性） |
| 验证 | 对比目标和结果、判断质量 | LLM |

### 执行流程

```
        ┌─────────┐
        │   目标   │
        └────┬────┘
             │
        ┌────▼────┐
        │  Plan    │ ← LLM 分析目标，输出 TaskGraph（DAG）
        └────┬────┘
             │
        ┌────▼──────────────────────┐
        │  Execute                   │
        │                            │
        │  Layer 0:  [A]  [B]  [C]   │ ← 无依赖，全部并行
        │              ↓       ↓     │
        │  Layer 1:  [D]      [E]    │ ← 依赖 A/B 的结果
        │               ↘   ↙       │
        │  Layer 2:     [F]          │ ← 依赖 D/E 的结果
        │                            │
        │  叶子节点: 工具调用 / LLM调用 │
        │  复杂节点: 递归 Plan-Execute │
        └────┬──────────────────────┘
             │
        ┌────▼────┐
        │  Verify  │ ← LLM：结果满足目标吗？
        └────┬────┘
             │
        ┌────▼────┐
        │  满足？  │
        └─┬────┬──┘
         是    否
          │     └──→ 标记失败节点 → 只重新规划失败部分 → 局部重做
        完成
```

### ReAct 的角色变了

```
之前：ReAct 是 Deep Agent 的基础 → DeepStrategy 复用 ReActStrategy
现在：Plan-Execute-Verify 是 Deep Agent 的原生模型
      ReAct 降级为叶子节点的执行器——它擅长的：几轮工具调用的简单任务

层次关系：

  PlanExecuteVerify（编排层：规划、调度、验证）
       │
       ├── 叶子任务：ReActLoop（几轮工具调用）
       ├── 叶子任务：SingleLLMCall（一次 LLM 调用，不调工具）
       └── 复杂子任务：递归 PlanExecuteVerify
```

---

## 三、三个 Artifact

```
openjiuwen-java-core        ← 接口 + 注解 + 模型，纯 Java 21，零 Spring 依赖
openjiuwen-java-runtime     ← Spring Boot 执行引擎，依赖 core
openjiuwen-java-sdk         ← Java 8 桥接层，让老系统调用 runtime
```

### core 和 runtime 的分工

```
core 定义"是什么"：
  - Agent 长什么样（@Agent 注解）
  - Tool 长什么样（@Tool 注解）
  - 执行结果长什么样（record）
  - 任务图长什么样（TaskGraph / TaskNode / TaskEdge）
  - 事件长什么样（sealed interface）
  - Store 接口长什么样

runtime 实现"怎么跑"：
  - 怎么规划（Planner：LLM 生成 TaskGraph）
  - 怎么执行（Executor：拓扑排序 + 并行/串行）
  - 怎么验证（Verifier：LLM 判断结果质量）
  - 叶子任务怎么跑（ReActLoop / SingleLLMCall）
  - 怎么存检查点
  - 怎么连 MCP
```

**core 不依赖 Spring。runtime 依赖 Spring Boot。**

---

## 四、开发者视角

### 4.1 定义 Agent

```java
@Agent(
    name = "order-service",
    description = "订单服务助手",
    model = "deepseek-chat",
    strategy = "deep"           // "react" | "deep" | "workflow"
)
public class OrderServiceAgent {

    @SystemPrompt
    public String prompt() {
        return """
            你是订单服务助手。
            可以查询订单状态、处理退款、修改收货地址。
            涉及金额操作时，必须先确认。
            """;
    }

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

**开发者需要关心的：就这些。** 定义 Agent 名字、系统提示、工具方法。

### 4.2 调用 Agent

```java
@Autowired
private AgentClient client;

// 同步（简单问答）
AgentResult result = client.invoke("order-service", "订单12345到哪了");

// 流式（对话场景）
client.stream("order-service", message, new AgentEventHandler() {
    @Override public void onThinking(String thought)      { /* 思考过程 */ }
    @Override public void onPlan(TaskGraph plan)           { /* 规划出来了 */ }
    @Override public void onTaskStart(TaskNode task)       { /* 子任务开始 */ }
    @Override public void onTaskComplete(TaskNode task)    { /* 子任务完成 */ }
    @Override public void onToolCall(String tool, Map<String, Object> args) { /* 工具调用 */ }
    @Override public void onVerify(VerifyResult vr)        { /* 验证结果 */ }
    @Override public void onComplete(String output)        { /* 最终回答 */ }
    @Override public void onError(Exception e)             { /* 出错 */ }
});

// 异步长流程（审批等）
String taskId = client.submit("order-service", "处理退款 #12345");
// ... 做别的事 ...
AgentResult result = client.await(taskId, Duration.ofMinutes(5));

// 回调
client.submit("order-service", "处理退款", task -> {
    if (task.isPaused()) {
        notifyHuman(task.pauseReason());
    }
});
```

### 4.3 Java 8 老系统（SDK）

```java
AgentClient client = AgentClient.builder()
    .runtimeUrl("http://agent-runtime:8080")
    .apiKey("your-key")
    .build();

AgentResult result = client.invoke("order-service", "订单12345到哪了");
```

---

## 五、core：接口 + 注解 + 模型

### 5.1 注解

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Agent {
    String name();
    String description() default "";
    String model() default "";
    int maxIterations() default 10;
    String strategy() default "deep";     // deep | react | workflow
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemPrompt {
    String value() default "";
    String file() default "";
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    String description();
}

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Param {
    String description() default "";
    boolean required() default true;
}
```

### 5.2 任务图

```java
/**
 * 规划阶段的产出：一个有向无环图。
 * 节点是子任务，边是依赖关系。
 */
public record TaskGraph(
    String goal,
    List<TaskNode> nodes,
    List<TaskEdge> edges
) {

    /**
     * 拓扑排序，生成执行层。
     * 同一层内的节点无依赖关系，可以并行执行。
     */
    public List<List<TaskNode>> executionLayers() {
        // Layer 0: 无前置依赖
        // Layer 1: 只依赖 Layer 0
        // Layer N: 依赖 Layer 0..N-1
        // ...
    }
}

public record TaskNode(
    String id,
    String description,
    TaskType type,               // TOOL_CALL / LLM_CALL / SUB_AGENT
    Map<String, String> inputs,  // 可能引用上游节点输出：如 "${node-A.output}"
    String expectedOutput
) {}

public enum TaskType {
    TOOL_CALL,      // 调一个工具（确定性执行）
    LLM_CALL,       // 调 LLM（需要推理）
    SUB_AGENT       // 递归：又是一个完整的 Plan-Execute-Verify
}

public record TaskEdge(
    String from,
    String to,
    String dataRef                // from 的什么数据传给 to
) {}
```

### 5.3 执行结果和事件

```java
public record AgentResult(
    String taskId,
    TaskStatus status,            // COMPLETED / FAILED / PAUSED
    String output,
    TaskGraph plan,               // 这次执行的规划（可能为 null）
    List<TaskResult> taskResults, // 每个子任务的结果
    TokenUsage tokenUsage,
    Duration duration
) {}

public enum TaskStatus {
    CREATED, PLANNING, EXECUTING, VERIFYING, PAUSED, COMPLETED, FAILED, CANCELLED
}

public record TaskResult(
    String nodeId,
    TaskStatus status,
    Object output,
    Duration duration
) {}

public record TokenUsage(int inputTokens, int outputTokens, String model) {}

/**
 * 统一事件。不管底层用什么策略，事件格式一致。
 */
public sealed interface AgentEvent
    permits ThinkingEvent, PlanEvent, TaskStartEvent, TaskCompleteEvent,
            ToolCallEvent, ToolResultEvent, VerifyEvent, CompleteEvent, ErrorEvent {
    String taskId();
    Instant timestamp();
}

public record ThinkingEvent(String taskId, Instant timestamp, String content)
    implements AgentEvent {}

public record PlanEvent(String taskId, Instant timestamp, TaskGraph plan)
    implements AgentEvent {}

public record TaskStartEvent(String taskId, Instant timestamp, String nodeId, String description)
    implements AgentEvent {}

public record TaskCompleteEvent(String taskId, Instant timestamp, String nodeId, Object result)
    implements AgentEvent {}

public record ToolCallEvent(String taskId, Instant timestamp,
                            String toolName, Map<String, Object> args)
    implements AgentEvent {}

public record ToolResultEvent(String taskId, Instant timestamp,
                              String toolName, Object result)
    implements AgentEvent {}

public record VerifyEvent(String taskId, Instant timestamp,
                          boolean passed, String feedback)
    implements AgentEvent {}

public record CompleteEvent(String taskId, Instant timestamp, AgentResult result)
    implements AgentEvent {}

public record ErrorEvent(String taskId, Instant timestamp, String error)
    implements AgentEvent {}
```

### 5.4 执行策略接口

```java
/**
 * 执行策略。
 * "deep" = PlanExecuteVerifyStrategy
 * "react" = ReActStrategy（叶子级，适合简单任务）
 * "workflow" = WorkflowStrategy（确定性流程）
 */
public interface ExecutionStrategy {
    String name();
    Flux<AgentEvent> execute(TaskContext context);
    Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint);
}

/**
 * 任务上下文。每次执行新建，不共享。Agent 本身无状态。
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
    VirtualFileSystem vfs
) {}
```

### 5.5 Store 接口

```java
public interface MemoryStore {
    Mono<Void> append(String sessionId, Message message);
    Flux<Message> load(String sessionId, int lastN);
    Mono<Void> clear(String sessionId);
}

public interface CheckpointStore {
    Mono<Void> save(Checkpoint checkpoint);
    Mono<Checkpoint> loadLatest(String taskId);
    Flux<Checkpoint> list(String taskId);
}

public interface VirtualFileSystem {
    Mono<Void> write(String path, String content);
    Mono<String> read(String path);
    Flux<String> list(String dir);
    Mono<Void> delete(String path);
}

public record Checkpoint(
    String checkpointId,
    String taskId,
    String phase,                // PLANNING / EXECUTING / VERIFYING
    int stepIndex,
    String stateJson,
    Instant timestamp
) {}
```

### 5.6 core 的依赖

```xml
<dependencies>
    <dependency>com.fasterxml.jackson.core:jackson-databind</dependency>
    <dependency>io.projectreactor:reactor-core</dependency>
</dependencies>
<java.version>21</java.version>
```

---

## 六、runtime：Spring Boot 执行引擎

### 6.1 模块结构

```
openjiuwen-java-runtime/
└── com.openjiuwen.java.runtime/
    │
    ├── config/
    │   └── OpenJiuwenAutoConfiguration     ← Spring Boot 自动配置
    │
    ├── AgentRegistry                        ← @Agent 扫描注册
    ├── AgentRunner                          ← 执行引擎入口（无状态，线程安全）
    ├── LocalAgentClient                     ← 同 JVM 调用入口
    │
    ├── planner/
    │   └── Planner                          ← LLM 生成 TaskGraph
    │
    ├── executor/
    │   ├── TaskGraphExecutor                ← 拓扑排序 + 调度
    │   ├── ToolCallExecutor                 ← TOOL_CALL 节点
    │   ├── LLMCallExecutor                  ← LLM_CALL 节点
    │   └── SubAgentExecutor                 ← SUB_AGENT 节点（递归）
    │
    ├── verifier/
    │   └── Verifier                         ← LLM 验证结果
    │
    ├── strategy/
    │   ├── PlanExecuteVerifyStrategy        ← Deep Agent 主策略
    │   ├── ReActStrategy                    ← 叶子任务执行（独立使用时 = 简单 Agent）
    │   └── WorkflowStrategy                 ← 确定性工作流
    │
    ├── store/
    │   ├── InMemoryMemoryStore
    │   ├── InMemoryCheckpointStore
    │   ├── RedisCheckpointStore
    │   └── JdbcCheckpointStore
    │
    ├── vfs/
    │   ├── InMemoryVFS
    │   ├── LocalStorageVFS
    │   └── S3VFS
    │
    ├── mcp/
    │   └── McpClientBridge                  ← 调远程工具
    │
    ├── api/
    │   └── AgentController                  ← REST API（给 SDK 调）
    │
    └── observability/
        └── AgentMetrics
```

### 6.2 自动配置

```java
@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@EnableConfigurationProperties(OpenJiuwenProperties.class)
public class OpenJiuwenAutoConfiguration {

    @Bean
    public AgentRegistry agentRegistry(ApplicationContext ctx) {
        return new AgentRegistry(ctx.getBeansWithAnnotation(Agent.class));
    }

    @Bean
    public AgentRunner agentRunner(
        ChatModel chatModel,
        AgentRegistry registry,
        MemoryStore memory,
        CheckpointStore checkpoints,
        VirtualFileSystem vfs
    ) {
        return new AgentRunner(chatModel, registry, memory, checkpoints, vfs);
    }

    @Bean
    public AgentClient agentClient(AgentRunner runner) {
        return new LocalAgentClient(runner);
    }

    // 默认实现（开发者可以覆盖）
    @Bean @ConditionalOnMissingBean
    public MemoryStore memoryStore() { return new InMemoryMemoryStore(); }

    @Bean @ConditionalOnMissingBean
    public CheckpointStore checkpointStore() { return new InMemoryCheckpointStore(); }

    @Bean @ConditionalOnMissingBean
    public VirtualFileSystem virtualFileSystem() { return new InMemoryVFS(); }
}
```

### 6.3 AgentRunner：执行引擎入口

```java
/**
 * Agent 执行引擎。无状态，线程安全。
 * 每次执行创建新的 TaskContext，可并发跑万级 Task。
 */
public class AgentRunner {

    private final ChatModel chatModel;
    private final AgentRegistry registry;
    private final Map<String, ExecutionStrategy> strategies;

    public Flux<AgentEvent> run(String agentName, String input) {
        AgentDefinition def = registry.get(agentName);
        TaskContext ctx = buildContext(def, input);
        ExecutionStrategy strategy = strategies.get(def.strategy());
        return strategy.execute(ctx);
    }
}
```

### 6.4 PlanExecuteVerifyStrategy：Deep Agent 核心

```java
/**
 * Deep Agent 的执行模型：Plan → Execute → Verify。
 * 不是一个循环，是三个独立的阶段。
 */
public class PlanExecuteVerifyStrategy implements ExecutionStrategy {

    private final Planner planner;
    private final TaskGraphExecutor executor;
    private final Verifier verifier;

    @Override
    public String name() { return "deep"; }

    @Override
    public Flux<AgentEvent> execute(TaskContext ctx) {
        return Flux.create(sink -> {

            // ========== Phase 1: Plan ==========
            sink.next(new ThinkingEvent(ctx.taskId(), now(),
                "分析目标，制定执行计划..."));

            TaskGraph graph = planner.plan(ctx);
            sink.next(new PlanEvent(ctx.taskId(), now(), graph));

            // 保存检查点：规划完成
            saveCheckpoint(ctx, "PLANNING", graph);

            // ========== Phase 2: Execute ==========
            Map<String, Object> results = new LinkedHashMap<>();

            for (List<TaskNode> layer : graph.executionLayers()) {

                // 同一层内的节点可以并行
                List<TaskNode> parallelTasks = layer;

                Map<TaskNode, CompletableFuture<Object>> futures = new LinkedHashMap<>();
                for (TaskNode task : parallelTasks) {
                    sink.next(new TaskStartEvent(ctx.taskId(), now(),
                        task.id(), task.description()));

                    futures.put(task, supplyAsync(() -> executeNode(ctx, task, results)));
                }

                // 等待本层全部完成
                for (var entry : futures.entrySet()) {
                    Object result = entry.getValue().join();
                    results.put(entry.getKey().id(), result);

                    sink.next(new TaskCompleteEvent(ctx.taskId(), now(),
                        entry.getKey().id(), result));
                }

                // 每层完成后保存检查点
                saveCheckpoint(ctx, "EXECUTING", results);
            }

            // ========== Phase 3: Verify ==========
            String finalOutput = assembleOutput(results);
            VerifyResult verify = verifier.verify(ctx, finalOutput);

            sink.next(new VerifyEvent(ctx.taskId(), now(),
                verify.passed(), verify.feedback()));

            if (verify.passed()) {
                // 验证通过 → 完成
                sink.next(new CompleteEvent(ctx.taskId(), now(),
                    buildResult(ctx, finalOutput, graph, results)));
                sink.complete();
            } else {
                // 验证失败 → 局部重做
                Set<String> failedNodes = verify.failedNodes();
                if (failedNodes.isEmpty() || verify.getRetries() >= MAX_RETRIES) {
                    // 重试次数用尽 → 返回失败
                    sink.next(new ErrorEvent(ctx.taskId(), now(),
                        "验证失败，已达最大重试次数: " + verify.feedback()));
                    sink.complete();
                } else {
                    // 局部 replan：只重新规划失败节点及其下游
                    TaskGraph revisedGraph = planner.replan(ctx, graph, failedNodes, verify.feedback());
                    sink.next(new PlanEvent(ctx.taskId(), now(), revisedGraph));
                    // 递归执行修订后的图（只执行修订部分）
                    // ...
                }
            }
        });
    }

    /**
     * 执行单个节点。
     */
    private Object executeNode(TaskContext ctx, TaskNode task,
                               Map<String, Object> completedResults) {
        return switch (task.type()) {
            case TOOL_CALL -> executeToolCall(ctx, task, completedResults);
            case LLM_CALL  -> executeLLMCall(ctx, task, completedResults);
            case SUB_AGENT -> executeSubAgent(ctx, task, completedResults);
        };
    }

    private Object executeToolCall(TaskContext ctx, TaskNode task,
                                   Map<String, Object> completedResults) {
        // 解析 inputs（可能引用上游输出）
        Map<String, Object> resolvedArgs = resolveInputs(task.inputs(), completedResults);
        // 调用工具
        return toolRegistry.execute(task.description(), resolvedArgs);
    }

    private Object executeLLMCall(TaskContext ctx, TaskNode task,
                                   Map<String, Object> completedResults) {
        // 一次 LLM 调用，不需要循环
        String prompt = resolveTemplate(task.description(), completedResults);
        return chatModel.call(prompt);
    }

    private Object executeSubAgent(TaskContext ctx, TaskNode task,
                                    Map<String, Object> completedResults) {
        // 递归：创建新的 TaskContext，跑一个新的 Plan-Execute-Verify
        TaskContext subCtx = ctx.forSubTask(task);
        return planExecuteVerify(subCtx).blockLast(); // 阻塞等待子 Agent 完成
    }
}
```

### 6.5 Planner：规划器

```java
/**
 * 规划器。LLM 分析目标，输出 TaskGraph。
 */
public class Planner {

    private final ChatModel chatModel;

    public TaskGraph plan(TaskContext ctx) {
        String prompt = """
            你是一个任务规划专家。请分析以下目标，将其分解为子任务。

            目标：%s

            可用工具：%s

            请输出 JSON 格式的任务图：
            {
              "nodes": [
                {"id": "A", "description": "...", "type": "TOOL_CALL|LLM_CALL|SUB_AGENT",
                 "inputs": {}, "expectedOutput": "..."}
              ],
              "edges": [
                {"from": "A", "to": "B", "dataRef": "A.output"}
              ]
            }

            规则：
            1. 每个节点应该是明确的、可独立执行的任务
            2. 没有依赖关系的节点不要创建边
            3. 简单的工具调用用 TOOL_CALL，需要推理的用 LLM_CALL，需要再分解的用 SUB_AGENT
            """.formatted(ctx.userInput(), toolDescriptions(ctx));

        String response = chatModel.call(prompt);
        return parseTaskGraph(response);
    }

    /**
     * 局部重新规划：只重新规划失败的节点及其下游。
     */
    public TaskGraph replan(TaskContext ctx, TaskGraph original,
                            Set<String> failedNodes, String feedback) {
        String prompt = """
            以下任务图中，部分节点执行失败。请重新规划失败部分。

            原始目标：%s
            失败节点：%s
            失败原因：%s
            已成功完成的节点及其结果：%s

            请只输出需要重新执行的节点和边。
            """.formatted(ctx.userInput(), failedNodes, feedback, successfulResults(original, failedNodes));

        String response = chatModel.call(prompt);
        return parseTaskGraph(response);
    }
}
```

### 6.6 TaskGraphExecutor：按拓扑排序执行

```java
/**
 * 任务图执行器。
 * 核心能力：拓扑排序 + 同层并行 + 检查点。
 */
public class TaskGraphExecutor {

    /**
     * 执行一个 TaskGraph。
     * 按 executionLayers() 逐层执行，同层并行。
     */
    public Flux<TaskResult> execute(TaskGraph graph, TaskContext ctx) {
        List<List<TaskNode>> layers = graph.executionLayers();
        Map<String, Object> completed = new ConcurrentHashMap<>();

        return Flux.create(sink -> {
            for (List<TaskNode> layer : layers) {
                // 同层并行
                List<CompletableFuture<TaskResult>> futures = layer.stream()
                    .map(node -> supplyAsync(() -> {
                        Object result = executeNode(ctx, node, completed);
                        completed.put(node.id(), result);
                        return new TaskResult(node.id(), TaskStatus.COMPLETED, result, null);
                    }))
                    .toList();

                // 等待本层全部完成
                futures.forEach(f -> sink.next(f.join()));

                // 每层完成后检查点
                ctx.checkpoints().save(new Checkpoint(
                    UUID.randomUUID().toString(),
                    ctx.taskId(), "EXECUTING", layers.indexOf(layer),
                    toJson(completed), Instant.now()
                )).subscribe();
            }
            sink.complete();
        });
    }
}
```

### 6.7 Verifier：验证器

```java
/**
 * 验证器。LLM 判断执行结果是否满足原始目标。
 */
public class Verifier {

    private final ChatModel chatModel;

    public VerifyResult verify(TaskContext ctx, String output) {
        String prompt = """
            请验证以下任务执行结果是否满足原始目标。

            原始目标：%s
            执行结果：%s

            请判断：
            1. 结果是否完整回答了原始目标？
            2. 结果中是否有明显错误？
            3. 结果质量是否可接受？

            输出 JSON：
            {"passed": true/false, "feedback": "反馈说明",
             "failedNodes": ["失败的节点ID（如有）"]}
            """.formatted(ctx.userInput(), output);

        String response = chatModel.call(prompt);
        return parseVerifyResult(response);
    }
}

public record VerifyResult(
    boolean passed,
    String feedback,
    Set<String> failedNodes,
    int retries
) {}
```

### 6.8 ReActStrategy：叶子任务执行

```java
/**
 * ReAct 循环。
 * 在 v3 中，它是一个独立的简单策略，也是 Deep Agent 叶子节点的执行方式。
 *
 * 适合场景：单轮问答 + 1-3 次工具调用。
 * 独立使用时 strategy="react"。
 */
public class ReActStrategy implements ExecutionStrategy {

    @Override
    public String name() { return "react"; }

    @Override
    public Flux<AgentEvent> execute(TaskContext ctx) {
        return Flux.create(sink -> {
            List<Message> messages = initMessages(ctx);
            int iteration = 0;

            while (iteration < ctx.maxIterations()) {
                ChatResponse response = chatModel.call(messages);

                if (response.hasToolCalls()) {
                    for (ToolCall call : response.toolCalls()) {
                        Object result = executeTool(ctx, call);
                        messages.add(toolResultMessage(call, result));
                    }
                } else {
                    sink.next(new CompleteEvent(ctx.taskId(), now(),
                        buildResult(ctx, response.content())));
                    sink.complete();
                    return;
                }

                saveCheckpoint(ctx, iteration, messages);
                iteration++;
            }

            sink.next(new CompleteEvent(ctx.taskId(), now(), maxIterationResult(ctx)));
            sink.complete();
        });
    }
}
```

### 6.9 runtime 的依赖

```xml
<dependencies>
    <dependency>com.openjiuwen:openjiuwen-java-core</dependency>
    <dependency>org.springframework.boot:spring-boot-starter</dependency>
    <dependency>org.springframework.boot:spring-boot-starter-web</dependency>
    <dependency>org.springframework.ai:spring-ai-openai-spring-boot-starter</dependency>
    <dependency>org.springframework.ai:spring-ai-mcp</dependency>
    <dependency>io.projectreactor:reactor-core</dependency>
</dependencies>
<java.version>21</java.version>
```

---

## 七、sdk：Java 8 桥接

SDK 只做一件事：**让 Java 8 系统通过 HTTP 调用 runtime**。

### 7.1 sdk-api

```java
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
    default void onPlan(List<Map<String, Object>> plan) {}
    default void onTaskStart(String nodeId, String description) {}
    default void onTaskComplete(String nodeId, Object result) {}
    default void onToolCall(String tool, Map<String, Object> args) {}
    default void onToolResult(String tool, Object result) {}
    default void onVerify(boolean passed, String feedback) {}
    default void onComplete(String output) {}
    default void onError(Exception e) {}
}
```

### 7.2 sdk-remote

```java
public class RemoteAgentClient implements AgentClient {

    private final String baseUrl;
    private final String apiKey;
    private final LightweightMcpServer mcpServer;    // 暴露 @Tool 给 runtime

    @Override
    public AgentResult invoke(String agentName, String input) {
        // POST /api/v1/agents/{agentName}/invoke
        String json = httpPost(baseUrl + "/api/v1/agents/" + agentName + "/invoke",
            Map.of("input", input));
        return parseResult(json);
    }

    @Override
    public void stream(String agentName, String input, AgentEventHandler handler) {
        // SSE: GET /api/v1/agents/{agentName}/stream?input=xxx
        httpSseGet(baseUrl + "/api/v1/agents/" + agentName + "/stream",
            Map.of("input", input), event -> {
                switch (event.type) {
                    case "thinking":     handler.onThinking(event.data); break;
                    case "plan":         handler.onPlan(event.plan); break;
                    case "task_start":   handler.onTaskStart(event.nodeId, event.description); break;
                    case "task_complete": handler.onTaskComplete(event.nodeId, event.result); break;
                    case "tool_call":    handler.onToolCall(event.toolName, event.args); break;
                    case "tool_result":  handler.onToolResult(event.toolName, event.result); break;
                    case "verify":       handler.onVerify(event.passed, event.feedback); break;
                    case "complete":     handler.onComplete(event.data); break;
                    case "error":        handler.onError(new AgentException(event.data)); break;
                }
            });
    }
}
```

### 7.3 SDK 的依赖

```xml
<dependencies>
    <dependency>com.fasterxml.jackson.core:jackson-databind</dependency>
</dependencies>
<java.version>1.8</java.version>
```

---

## 八、MCP：工具跨 JVM

```
企业 JVM（Java 8）                      Runtime JVM（Java 21）
┌────────────────────┐                 ┌────────────────────┐
│ @Tool 方法          │  ← MCP over ──→ │ MCP Client          │
│  ├ checkOrder()    │     HTTP         │                     │
│  ├ refund()        │                 │ PlanExecuteVerify    │
│  └ changeAddress() │                 │  └ ExecuteNode      │
│                     │                 │     └ 调用工具        │
│ MCP Server          │                 │                     │
│  ├ Servlet 3.0 async│                 │                     │
│  └ 线程池            │                 │                     │
└────────────────────┘                 └────────────────────┘
```

SDK 的 MCP Server 并发处理：
- **Servlet 3.0 async** — 不阻塞 HTTP 线程
- **业务线程池** — core = CPU 核数，max = CPU × 4
- **CallerRunsPolicy** — 线程池满了自动背压

---

## 九、检查点

```
执行过程中的检查点：

CP0: 初始状态（用户输入）
CP1: 规划完成（TaskGraph 已生成）
CP2: Layer 0 执行完成（并行节点结果）
CP3: Layer 1 执行完成
CP4: Layer 2 执行完成
CP5: 验证完成

任意时刻崩溃 → 加载最近的 CP → 从断点继续
```

---

## 十、架构总图

```
┌───────────────────────────────────────────────────────────────────────┐
│                                                                       │
│  企业 Java 8 系统                企业 Java 21 系统                     │
│  ┌─────────────────────┐       ┌──────────────────────┐              │
│  │ Spring Boot 1.x/2.x │       │ Spring Boot 3.x/4.x  │              │
│  │                      │       │                       │              │
│  │  业务代码            │       │  业务代码             │              │
│  │  @Agent + @Tool      │       │  @Agent + @Tool       │              │
│  │                      │       │                       │              │
│  │  openjiuwen-java-sdk │       │  openjiuwen-java-     │              │
│  │   ├ AgentClient      │       │  runtime (embedded)   │              │
│  │   └ MCP Server       │       │                       │              │
│  └──────────┬───────────┘       └───────────────────────┘              │
│             │ HTTP / MCP                                               │
└─────────────┼─────────────────────────────────────────────────────────┘
              │
              ▼
┌───────────────────────────────────────────────────────────────────────┐
│  openjiuwen-java-runtime（Java 21 + Spring Boot）                      │
│                                                                       │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │  PlanExecuteVerifyStrategy（Deep Agent 编排层）                    │ │
│  │                                                                  │ │
│  │   ┌──────────┐    ┌──────────────┐    ┌──────────┐              │ │
│  │   │ Planner  │ →  │ GraphExecutor│ →  │ Verifier │              │ │
│  │   │(LLM规划) │    │(拓扑+并行)    │    │(LLM验证) │              │ │
│  │   └──────────┘    └──────┬───────┘    └──────────┘              │ │
│  │                         │                                        │ │
│  │              ┌──────────┼──────────┐                             │ │
│  │              │          │          │                             │ │
│  │         ┌────▼───┐ ┌───▼────┐ ┌───▼──────┐                     │ │
│  │         │ToolExec│ │LLMExec │ │SubAgent  │                     │ │
│  │         │(工具)   │ │(推理)  │ │(递归PEV) │                     │ │
│  │         └────────┘ └────────┘ └──────────┘                     │ │
│  └──────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌───────────────────┐  ┌───────────────────┐  ┌──────────────────┐ │
│  │ ReActStrategy     │  │ WorkflowStrategy   │  │ AgentRunner      │ │
│  │(简单任务/独立使用) │  │(确定性流程)         │  │(入口,无状态)      │ │
│  └───────────────────┘  └───────────────────┘  └──────────────────┘ │
│                                                                       │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐ ┌──────────────┐ │
│  │ChatModel│ │MemoryStore│ │Checkpoint│ │  VFS   │ │ MCP Client   │ │
│  │(SpringAI│ │          │ │  Store   │ │        │ │(调远程工具)   │ │
│  └─────────┘ └──────────┘ └──────────┘ └────────┘ └──────────────┘ │
│                                                                       │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │  REST API  /api/v1/agents/{name}/invoke | stream | submit       │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 十一、迭代路线

每一步都能跑，不需要全部做完才交付：

```
v0.1  ReActStrategy + AgentRunner + AgentRegistry
      → 能跑简单 Agent，能调工具
      → 验证：一个 @Agent + 几个 @Tool，跑通 ReAct 循环

v0.2  sdk-api + sdk-remote
      → Java 8 系统能调 Agent
      → 验证：Java 8 main() 调通 runtime

v0.3  MCP Server in SDK
      → 工具注册在企业 JVM 里
      → 验证：runtime 调到 Java 8 的 @Tool 方法

v0.4  Planner + TaskGraph
      → LLM 能把目标分解成任务图
      → 验证：给一个 5 步任务，LLM 正确输出 DAG

v0.5  TaskGraphExecutor（拓扑排序 + 并行）
      → 任务图按依赖顺序执行，同层并行
      → 验证：3 个无依赖任务并行跑，2 个有依赖的串行跑

v0.6  Verifier + 局部 Replan
      → 执行完能验证，失败能局部重做
      → 验证：故意让一个节点失败，系统只重跑失败部分

v0.7  SubAgentExecutor（递归）
      → 复杂节点可以递归 Plan-Execute-Verify
      → 验证：一个任务分解成 3 个子任务，其中一个再分解成 2 个

v0.8  VFS + Checkpoint 持久化
      → Agent 有工作空间，崩溃能恢复
      → 验证：杀进程，重启后从断点继续

v0.9  WorkflowStrategy
      → 确定性工作流（YAML 定义）
      → 验证：用 YAML 定义一个审批流，跑通

v1.0  spring-boot-starter + 文档 + 示例
      → 开发者 5 分钟上手
```

---

## 十二、文件结构

```
openjiuwen-java/
│
├── openjiuwen-java-core/
│   └── com.openjiuwen.java.core/
│       ├── annotation/
│       │   ├── Agent.java
│       │   ├── SystemPrompt.java
│       │   ├── Tool.java
│       │   └── Param.java
│       ├── model/
│       │   ├── AgentResult.java
│       │   ├── TaskGraph.java
│       │   ├── TaskNode.java
│       │   ├── TaskEdge.java
│       │   ├── TaskResult.java
│       │   ├── TaskStatus.java
│       │   ├── VerifyResult.java
│       │   ├── TokenUsage.java
│       │   └── Checkpoint.java
│       ├── event/
│       │   ├── AgentEvent.java             ← sealed interface
│       │   ├── ThinkingEvent.java
│       │   ├── PlanEvent.java
│       │   ├── TaskStartEvent.java
│       │   ├── TaskCompleteEvent.java
│       │   ├── ToolCallEvent.java
│       │   ├── ToolResultEvent.java
│       │   ├── VerifyEvent.java
│       │   ├── CompleteEvent.java
│       │   └── ErrorEvent.java
│       ├── strategy/
│       │   ├── ExecutionStrategy.java
│       │   └── TaskContext.java
│       └── store/
│           ├── MemoryStore.java
│           ├── CheckpointStore.java
│           └── VirtualFileSystem.java
│
├── openjiuwen-java-runtime/
│   └── com.openjiuwen.java.runtime/
│       ├── config/
│       │   └── OpenJiuwenAutoConfiguration.java
│       ├── AgentRegistry.java
│       ├── AgentRunner.java
│       ├── LocalAgentClient.java
│       ├── planner/
│       │   └── Planner.java
│       ├── executor/
│       │   ├── TaskGraphExecutor.java
│       │   ├── ToolCallExecutor.java
│       │   ├── LLMCallExecutor.java
│       │   └── SubAgentExecutor.java
│       ├── verifier/
│       │   └── Verifier.java
│       ├── strategy/
│       │   ├── PlanExecuteVerifyStrategy.java
│       │   ├── ReActStrategy.java
│       │   └── WorkflowStrategy.java
│       ├── store/
│       │   ├── InMemoryMemoryStore.java
│       │   ├── InMemoryCheckpointStore.java
│       │   ├── RedisCheckpointStore.java
│       │   └── JdbcCheckpointStore.java
│       ├── vfs/
│       │   ├── InMemoryVFS.java
│       │   ├── LocalStorageVFS.java
│       │   └── S3VFS.java
│       ├── mcp/
│       │   └── McpClientBridge.java
│       ├── api/
│       │   └── AgentController.java
│       └── observability/
│           └── AgentMetrics.java
│
├── openjiuwen-java-sdk/
│   ├── openjiuwen-java-sdk-api/
│   │   └── com.openjiuwen.java.sdk/
│   │       ├── AgentClient.java
│   │       ├── AgentEventHandler.java
│   │       ├── AgentResult.java
│   │       └── TaskStatus.java
│   └── openjiuwen-java-sdk-remote/
│       └── com.openjiuwen.java.sdk.remote/
│           ├── RemoteAgentClient.java
│           ├── LightweightMcpServer.java
│           └── ConnectionManager.java
│
└── openjiuwen-java-examples/
    ├── example-simple/
    ├── example-deep/
    └── example-sdk-remote/
```

---

## 十三、设计原则

| 原则 | 体现 |
|------|------|
| **开发者只关心 @Agent + @Tool** | 三个注解定义完事，runtime 全自动 |
| **Plan-Execute-Verify 不是 ReAct** | Deep Agent 有自己的执行模型，ReAct 是叶子执行器 |
| **Agent 无状态** | AgentRunner 不持有状态，状态全在 TaskContext + Store |
| **策略可插拔** | `strategy="deep"` / `"react"` / `"workflow"` 一行切换 |
| **检查点每一步** | 规划完、每层执行完、验证完，都有检查点 |
| **局部重做** | 验证失败只重跑失败节点，不从头来 |
| **SDK 只有 Jackson** | Java 8 加一个依赖就能调 Agent |
| **Spring Boot 原生** | 用 Spring AI 的 ChatModel / MCP，不造轮子 |
| **迭代友好** | v0.1 跑 ReAct，v0.4 加规划，v0.6 加验证，不重构 |

---

_最后更新：2026-06-06 v3_
