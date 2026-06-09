# openjiuwen-java v3 架构设计

> 2026-05-25 | 基于 openjiuwen-java、agentscope-java、Spring AI、SAA 四项目源码级分析
> 级别：[解读] | 可信度：[最高]
> 状态：Draft

---

## 零、决策摘要

### 核心决策

| # | 决策 | 选择 | 放弃 | 理由 |
|---|------|------|------|------|
| AD-001 | 战略定位 | 独立竞争（α 策略） | 融入 SAA / 差异化生态位 | ADR-0001 已定：华为生态分发 + Agent 演进杀手锏 + 私有化优先 |
| AD-002 | 底层框架 | **Spring Boot 3.3 + Spring AI 1.1.x** | 自建 LLM/Tool/RAG 层 | 5/24 源码调研推翻旧 AD-002：Spring AI 替代 ~245 文件，省 60% 底层工作量 |
| AD-003 | Alibaba 依赖 | **零依赖** | agentscope-java / SAA / DashScope SDK / Nacos / fastjson | 避免 API 锁定 + 商业冲突 + 私有化场景约束 |
| AD-004 | Agent 抽象 | Java 21 sealed interface，三种范式渐进交付 | 单一 Agent 类 / 全量交付 | 企业开发者渐进学习，不同阶段用不同范式 |
| AD-005 | API 风格 | 同步优先，异步虚拟线程，流式可选 | Reactive-first | 企业 Java 开发者习惯同步编程，CompletableFuture 是最熟悉的异步抽象 |
| AD-006 | 配置方式 | YAML / Java Config / XML 三种并存 | 仅 YAML / 仅代码 | 覆盖三类角色：开发者（YAML）、架构师（Java）、业务部门（XML） |
| AD-007 | Studio 定位 | 嵌入式只读可视化，零 Node.js | 独立平台 / 全功能编辑器 | 降低部署复杂度，Phase 3-4 再扩展 |

### 旧决策变更记录

| 决策 | 旧版本（5/19） | 新版本（5/25 v3） | 变更原因 |
|------|---------------|-----------------|---------|
| AD-002 | 不依赖 Spring AI，全部自建 | Spring AI 为底层依赖 | v2 源码调研：Spring AI 可直接替代 ~245 文件（22%） |
| AD-003 | 四阶段（Starter → Native → Gateway → Ecosystem） | 六阶段渐进交付 | 支持三种 Agent 范式的渐进演进 |
| 人力估算 | ~11 人月（框架无关全自建） | 5-8 人月（视 AI coding 使用情况） | Spring AI 底座 + AI coding 工具加速 |

---

## 一、现状与问题

### 1.1 openjiuwen-java 现状

| 维度 | 数据 |
|------|------|
| 代码量 | 1,090 文件 / 124,895 行 |
| 模块 | 单体 JAR，无模块拆分 |
| Spring 依赖 | **零**（纯 Java + ServiceLoader） |
| 最大单文件 | `LlmEventHandler.java` 1,240 行（ReAct 循环） |
| 独有资产 | Pregel 图引擎（158 行 BSP）、Agent 进化训练（53 文件/8,370 行） |
| 版本 | 0.1.7 |

### 1.2 核心问题

1. **ReAct 循环未分层**：推理/行动/观察全部混在 1,240 行的 LlmEventHandler 中，无 Hook 拦截点
2. **自建底层轮子**：LLM 客户端（~50 文件）、工具反射（~30 文件）、向量存储（~80 文件）全部手写，Spring AI 已有成熟实现
3. **零 Spring 集成**：企业 Java 生态中无法利用自动配置、依赖注入、可观测性等基础能力
4. **无开发者友好 API**：无 Builder、无 `@Tool` 注解、无 YAML 配置，入门门槛高
5. **Agent 接口缺失**：没有 Agent 抽象层，不同类型的 Agent（工作流/自主/进化）没有统一的接口契约

### 1.3 四框架能力矩阵（简化）

| 能力 | openjiuwen 现状 | agentscope-java | Spring AI | SAA | v3 目标 |
|------|----------------|----------------|-----------|-----|--------|
| LLM Provider | 自建 50 文件 | 6 格式化器 | **15+ 提供者** | 继承 Spring AI | **复用 Spring AI** |
| 工具系统 | 自建 30 文件 | @Tool + Toolkit | **@Tool + ToolCallback** | 继承 | **复用 Spring AI** |
| ReAct 循环 | 1240 行混合 | **reasoning()+acting() 分离** | ToolCallAdvisor | LlmNode+ToolNode | **自建，阶段分离** |
| Hook 系统 | 无 | **8 事件** | Advisor（2 层） | 4 位置 | **自建 8 事件** |
| 图引擎 | **Pregel（独有）** | 无 | 无 | StateGraph | **保留移植** |
| Agent 训练 | **53 文件（独有）** | 无 | 无 | 无 | **保留移植** |
| RAG / 向量存储 | 自建 80 文件 | 5 后端 | **22 VectorStore** | 继承 | **复用 Spring AI** |
| Memory | 自建 | 3 模式 | ChatMemory+6 DB | 图状态 | **复用+扩展** |
| SkillBox | 无 | **有** | 无 | 无 | **自建参考** |
| PlanNotebook | 无 | **有** | 无 | 无 | **自建参考** |

---

## 二、分层架构

### 2.1 七层架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                    用户代码 / 应用层                          │
│         @Tool 注解 + YAML/XML 配置 + Java Builder             │
├──────────────────────────────────────────────────────────────┤
│  Layer 7: openjiuwen-spring-boot-starter                     │
│  自动配置 / Properties / Health Check                         │
├──────────────────────────────────────────────────────────────┤
│  Layer 6: openjiuwen-studio                                  │
│  嵌入式可视化平台（REST API + 静态前端）                        │
├──────────────────────────────────────────────────────────────┤
│  Layer 5: openjiuwen-enterprise                              │
│  Token Budget / 合规审计 / 多租户隔离                          │
├──────────────────────────────────────────────────────────────┤
│  Layer 4: openjiuwen-evolution                               │
│  Agent 进化训练（LLM-as-Judge + Prompt/记忆/工具优化器）        │
├──────────────────────────────────────────────────────────────┤
│  Layer 3: openjiuwen-runtime                                 │
│  Agent 执行引擎 + Pregel 图引擎 + ReAct 循环 + 工作流编译       │
├──────────────────────────────────────────────────────────────┤
│  Layer 2: openjiuwen-core                                    │
│  纯接口 + 基础类型（sealed interface / record / lifecycle）     │
├──────────────────────────────────────────────────────────────┤
│  Layer 1: Spring Boot 3.3 + Spring AI 1.1.x                 │
│  ChatModel / @Tool / ChatMemory / VectorStore / MCP           │
├──────────────────────────────────────────────────────────────┤
│  Layer 0: Java 21 + Project Reactor                          │
│  sealed interface / record / virtual threads / pattern match  │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 依赖规则

```
core ──────→ spring-ai-core（仅用 Message/ChatModel 类型）
runtime ──→ core + spring-ai-client-chat + reactor-core
graph ────→ core + reactor-core（无 Spring AI 依赖，保持独立性）
memory ──→ core + spring-ai-core（ChatMemory 抽象）
evolution → core + runtime（需要 AgentContext）
enterprise → runtime + memory
studio ──→ core（通过 REST API + ApplicationEvent，松耦合）
starter ──→ 全部模块 + spring-boot-autoconfigure
```

**关键约束**：
- `core` 零内部实现依赖，企业开发者 90% 时间只接触此模块
- `graph` 零 Spring AI 依赖，保持可移植性
- `studio` 与 `runtime` 通过事件总线通信，不直接调用内部方法

---

## 三、Maven 模块结构

```
openjiuwen-java-v3/
├── pom.xml                              # 父 POM（Spring Boot 3.3 parent）
├── openjiuwen-bom/                      # BOM（版本管理）
│   └── pom.xml
├── openjiuwen-core/                     # 纯接口模块
│   ├── src/main/java/com/openjiuwen/core/
│   └── pom.xml                          # 依赖：spring-ai-core
├── openjiuwen-runtime/                  # 执行引擎
│   ├── src/main/java/com/openjiuwen/runtime/
│   ├── src/test/java/
│   └── pom.xml                          # 依赖：core + spring-ai-client-chat + reactor-core
├── openjiuwen-graph/                    # Pregel 图引擎（独立模块）
│   ├── src/main/java/com/openjiuwen/graph/
│   ├── src/test/java/
│   └── pom.xml                          # 依赖：core + reactor-core
├── openjiuwen-memory/                   # 记忆系统
│   ├── src/main/java/com/openjiuwen/memory/
│   └── pom.xml                          # 依赖：core + spring-ai-core
├── openjiuwen-evolution/                # Agent 进化
│   ├── src/main/java/com/openjiuwen/evolution/
│   └── pom.xml                          # 依赖：core + runtime
├── openjiuwen-enterprise/               # 企业特性
│   ├── src/main/java/com/openjiuwen/enterprise/
│   └── pom.xml                          # 依赖：runtime + memory
├── openjiuwen-studio/                   # 可视化平台
│   ├── src/main/java/com/openjiuwen/studio/
│   ├── src/main/resources/static/       # 前端静态资源
│   └── pom.xml                          # 依赖：core + spring-boot-starter-web
├── openjiuwen-spring-boot-starter/      # 自动配置
│   ├── src/main/java/com/openjiuwen/autoconfigure/
│   ├── src/main/resources/META-INF/
│   │   └── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   └── pom.xml                          # 依赖：全部模块 + spring-boot-autoconfigure
└── openjiuwen-examples/                 # 示例项目
    ├── example-workflow-agent/
    ├── example-agentic-agent/
    └── example-claw-agent/
```

### Maven 依赖清单（框架层）

```xml
<!-- 父 POM -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.x</version>
</parent>

<!-- 全局依赖（core 层） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-core</artifactId>
    <version>1.1.x</version>
</dependency>

<!-- runtime 层额外依赖 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-client-chat</artifactId>
</dependency>
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
</dependency>

<!-- 不引入 -->
<!-- ❌ agentscope-java -->
<!-- ❌ spring-ai-alibaba -->
<!-- ❌ dashscope-sdk-java -->
<!-- ❌ nacos-client -->
<!-- ❌ fastjson -->
```

---

## 四、Agent 接口设计

### 4.1 三种 Agent 范式

```java
/**
 * openjiuwen Agent 的顶层抽象。
 * 三种范式对应三种自主性级别：
 * - WorkflowAgent（低自主）：预定义流程，确定性路由
 * - AgenticAgent（中自主）：LLM 驱动推理-行动循环
 * - ClawAgent（高自主）：技能驱动 + 自进化 + Agent 网络
 */
public sealed interface Agent
    permits WorkflowAgent, AgenticAgent, ClawAgent {

    String name();
    AgentConfig config();

    // ===== 同步 API（企业开发者首选）=====
    AgentResult invoke(String input);
    AgentResult invoke(String input, AgentContext context);

    // ===== 异步 API（虚拟线程，对调用者透明）=====
    CompletableFuture<AgentResult> invokeAsync(String input);

    // ===== 流式 API（可选，显式请求）=====
    Flux<AgentEvent> stream(String input);
}
```

### 4.2 WorkflowAgent（Phase 2 交付）

```java
/**
 * 工作流 Agent：预定义步骤，确定性路由。
 * 配置方式：YAML / XML / Java Builder。
 * 适用场景：客服流程、审批流程、数据管道。
 */
public interface WorkflowAgent extends Agent {

    /** 注册工作流定义 */
    void registerWorkflow(String workflowId, WorkflowDefinition definition);

    /** 获取已注册的工作流列表 */
    List<String> listWorkflows();

    /** 指定工作流 ID 执行 */
    AgentResult invoke(String input, String workflowId);
}
```

**特点**：
- 业务部门可直接编辑 XML 配置，无需重编译
- 内部编译为 Pregel 图执行
- 支持工作流跳转和断点续传（移植旧项目核心逻辑）

### 4.3 AgenticAgent（Phase 3 交付）

```java
/**
 * 自主 Agent：ReAct 推理-行动循环。
 * LLM 自主决定调用哪些工具、何时停止。
 * 适用场景：研究助手、代码分析、自动化运维。
 */
public interface AgenticAgent extends Agent {

    /** 最大推理迭代次数 */
    int maxIterations();

    /** 注册生命周期 Hook */
    void addLifecycleHook(AgentLifecycle hook);

    /** 移除生命周期 Hook */
    void removeLifecycleHook(AgentLifecycle hook);

    /** 中断当前执行（HITL） */
    void interrupt();
}
```

**特点**：
- 推理（Reasoning）和行动（Acting）明确分离（参考 agentscope-java）
- 8 事件 Hook 生命周期
- 工具并行执行（虚拟线程）

### 4.4 ClawAgent（Phase 4 交付）

```java
/**
 * xClaw Agent：技能驱动 + 自进化 + Agent 网络。
 * 继承 AgenticAgent 全部能力，增加：
 * - SkillBox：动态加载/卸载技能
 * - PlanNotebook：多步规划管理
 * - Evolution：自动调优（后台运行）
 * 适用场景：交易分析、智能运维、持续学习助手。
 */
public interface ClawAgent extends AgenticAgent {

    SkillBox skillBox();
    PlanNotebook planNotebook();

    /** 加载/卸载技能 */
    void loadSkill(String skillName);
    void unloadSkill(String skillName);

    /** 注册为网络中的可发现 Agent（A2A/MCP） */
    void registerAsNetworkAgent();

    /** 触发一次自进化评估 */
    EvolutionReport triggerEvolution();
}
```

**特点**：
- 技能可以从文件系统/Git/数据库动态加载
- PlanNotebook 管理 LLM 生成的多步计划
- Agent 进化是 openjiuwen 独有资产（四个框架中唯一有此能力）

### 4.5 Agent 自主性光谱

```
WorkflowAgent              AgenticAgent              ClawAgent
├── 预定义流程              ├── LLM 自主推理            ├── 继承 AgenticAgent
├── 确定性路由              ├── 工具动态选择            ├── 技能驱动
├── YAML/XML 配置          ├── 8 事件 Hook            ├── 多步规划
├── 无 LLM 决策权          ├── ReAct 循环              ├── Agent 网络
└── 适合业务部门            └── 适合开发者              ├── 自进化
                                                        └── 适合 AI 平台团队

自主性：低 ──────────────────────────────────────────→ 高
复杂度：低 ──────────────────────────────────────────→ 高
交付阶段：Phase 2 ────→ Phase 3 ────→ Phase 4
```

---

## 五、核心接口定义

### 5.1 AgentContext（统一上下文）

```java
/**
 * Agent 执行的统一上下文。
 * 开发者唯一需要在 Hook 中处理的参数类型。
 * 所有状态通过此接口访问，不暴露内部实现。
 */
public interface AgentContext {
    // 会话信息
    String sessionId();
    String conversationId();
    String agentName();

    // 输入输出
    String userInput();
    void setOutput(String output);

    // 工具与记忆
    ToolRegistry toolRegistry();
    AgentMemory memory();

    // 状态与元数据
    AgentState state();
    Map<String, Object> metadata();
    void setMetadata(String key, Object value);
}
```

### 5.2 AgentLifecycle（8 事件 Hook）

```java
/**
 * Agent 生命周期 Hook。
 * 参考 agentscope-java 的 8 事件模型。
 * 优先级：0-50 系统级，51-100 高优先，101-500 正常，501+ 低优先。
 */
public interface AgentLifecycle {
    default void onBeforeInvoke(AgentContext ctx) {}
    default void onAfterInvoke(AgentContext ctx, AgentResult result) {}
    default void onBeforeReasoning(AgentContext ctx) {}
    default void onAfterReasoning(AgentContext ctx, String reasoning) {}
    default void onBeforeActing(AgentContext ctx, String toolName, Map<String, Object> args) {}
    default void onAfterActing(AgentContext ctx, String toolName, Object result) {}
    default void onError(AgentContext ctx, Exception e) {}
    default int priority() { return 100; }
}
```

**与 Spring AI Advisor 的桥接**（LifecycleAdvisor）：

```
Spring AI Advisor 链              openjiuwen 生命周期
─────────────────              ──────────────────
Advisor.beforeCall()       →   onBeforeReasoning()
Advisor.afterCall()        →   onAfterReasoning()
[工具执行]                   →   onBeforeActing() + onAfterActing()
（无对应）                   →   onBeforeInvoke() + onAfterInvoke()
```

### 5.3 ToolRegistry（工具注册中心）

```java
/**
 * 包装 Spring AI 的 ToolCallback，提供更友好的 Java API。
 */
public interface ToolRegistry {
    /** 注册带 @Tool 注解的对象（Spring AI 自动扫描） */
    void register(Object toolInstance);

    /** 按名称注册 */
    void register(String name, Object toolInstance);

    /** 注销 */
    void unregister(String name);

    /** 查找 */
    Optional<ToolInfo> getTool(String name);

    /** 列出所有 */
    List<ToolInfo> listTools();

    /** 动态添加（运行时，Claw Agent 用） */
    void addTool(ToolDefinition definition, Function<Map<String, Object>, Object> executor);
}
```

### 5.4 AgentMemory（记忆系统）

```java
/**
 * 短期记忆委托 Spring AI ChatMemory，长期记忆自建（VectorStore 语义搜索）。
 */
public interface AgentMemory {
    // 短期记忆（当前会话）
    void addMessage(String role, String content);
    List<MessageEntry> getMessages();
    void clear();

    // 长期记忆（跨会话语义搜索）
    List<MemorySearchResult> search(String query, int limit);
    void save(MemoryEntry entry);
    void forget(String entryId);
}
```

### 5.5 SkillBox（技能容器，Claw Agent 专用）

```java
/**
 * 技能容器：动态加载/卸载/搜索技能。
 * 参考 agentscope-java SkillBox 的声明式 @AgentSkill + 仓库模式。
 */
public interface SkillBox {
    /** 加载技能（从文件系统/Classpath/Git/数据库） */
    void load(String skillName);

    /** 卸载技能 */
    void unload(String skillName);

    /** 搜索可用技能 */
    List<SkillDefinition> search(String query);

    /** 获取已加载技能列表 */
    List<SkillDefinition> loadedSkills();
}
```

### 5.6 PlanNotebook（计划本，Claw Agent 专用）

```java
/**
 * 多步规划管理。
 * LLM 生成计划 → 执行步骤 → 更新状态 → 失败回退。
 */
public interface PlanNotebook {
    /** LLM 生成计划 */
    Plan createPlan(String goal);

    /** 获取当前步骤 */
    PlanStep currentStep();

    /** 推进到下一步 */
    void advance();

    /** 标记步骤失败（可回退） */
    void fail(String reason);

    /** 获取完整计划 */
    Plan getPlan();
}
```

---

## 六、Runtime 核心实现

### 6.1 AbstractAgent（Agent 基类）

```java
/**
 * 所有 Agent 实现的基类。
 * 管理：生命周期 Hook 链、Spring AI ChatClient 集成、状态管理、中断处理。
 */
public abstract class AbstractAgent implements Agent {
    protected final String name;
    protected final AgentConfig config;
    protected final ChatClient chatClient;           // Spring AI
    protected final ToolRegistry toolRegistry;
    protected final AgentMemory memory;
    protected final List<AgentLifecycle> hooks;       // 按 priority 排序
    protected final AtomicBoolean executing;          // 单线程执行保证

    @Override
    public final AgentResult invoke(String input) {
        if (!executing.compareAndSet(false, true)) {
            throw new AgentException("Agent " + name + " is already executing");
        }
        try {
            AgentContext ctx = createContext(input);
            fireBeforeInvoke(ctx);
            AgentResult result = doInvoke(ctx);        // 子类实现
            fireAfterInvoke(ctx, result);
            return result;
        } catch (Exception e) {
            fireError(createContext(input), e);
            throw new AgentException("Agent execution failed", e);
        } finally {
            executing.set(false);
        }
    }

    @Override
    public CompletableFuture<AgentResult> invokeAsync(String input) {
        return CompletableFuture.supplyAsync(
            () -> invoke(input),
            Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    protected abstract AgentResult doInvoke(AgentContext ctx);
    protected abstract AgentContext createContext(String input);
}
```

### 6.2 ReActLoop（ReAct 循环核心，~300 行）

```java
/**
 * ReAct 推理-行动循环。
 * 参考 agentscope-java ReActAgent.doCall() 的 reasoning()+acting() 分离设计。
 *
 * 循环结构：
 *   while (!done && iteration < maxIterations) {
 *     String thought = reasoning(ctx);
 *     if (hasToolCalls(thought)) {
 *       Object result = acting(ctx, toolCalls);
 *     } else {
 *       done = true;  // 无工具调用 = 最终答案
 *     }
 *     iteration++;
 *   }
 */
public class ReActLoop {
    private final ChatClient chatClient;
    private final List<AgentLifecycle> hooks;
    private final int maxIterations;

    public ReActResult execute(AgentContext ctx) {
        int iteration = 0;
        boolean done = false;

        while (!done && iteration < maxIterations) {
            // 1. Reasoning
            hooks.forEach(h -> h.onBeforeReasoning(ctx));
            String thought = callLLM(ctx);
            hooks.forEach(h -> h.onAfterReasoning(ctx, thought));

            // 2. 判断是否有工具调用
            List<ToolCall> toolCalls = parseToolCalls(thought);
            if (toolCalls.isEmpty()) {
                ctx.setOutput(thought);
                done = true;
            } else {
                // 3. Acting（虚拟线程并行）
                for (var call : toolCalls) {
                    hooks.forEach(h -> h.onBeforeActing(ctx, call.name(), call.args()));
                }
                Map<String, Object> results = executeToolsParallel(toolCalls);
                for (var entry : results.entrySet()) {
                    hooks.forEach(h -> h.onAfterActing(ctx, entry.getKey(), entry.getValue()));
                }
                // 4. Observation
                appendToolResults(ctx, results);
            }
            iteration++;
        }

        if (!done) { return summarize(ctx); }
        return ReActResult.completed(ctx);
    }

    /** 虚拟线程并行执行工具 */
    private Map<String, Object> executeToolsParallel(List<ToolCall> calls) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = calls.stream()
                .map(call -> executor.submit(() -> Map.entry(
                    call.name(),
                    toolRegistry.getTool(call.name()).orElseThrow().execute(call.args())
                )))
                .toList();
            return futures.stream().collect(Collectors.toMap(
                f -> { try { return f.get().getKey(); } catch (Exception e) { throw new RuntimeException(e); } },
                f -> { try { return f.get().getValue(); } catch (Exception e) { throw new RuntimeException(e); } }
            ));
        }
    }
}
```

### 6.3 WorkflowCompiler（YAML/XML → Pregel 图）

```java
/**
 * 将 YAML 或 XML 定义的工作流编译为 Pregel 图。
 * 三种配置方式 → 同一个编译产物 → 同一个执行引擎。
 */
public class WorkflowCompiler {
    /** 从 YAML 编译 */
    public Pregel compileFromYaml(Resource yamlResource) { ... }

    /** 从 XML 编译（业务部门友好，无需重编译） */
    public Pregel compileFromXml(Resource xmlResource) { ... }

    /** 从 Java Builder 编译 */
    public Pregel compileFromBuilder(WorkflowBuilder builder) { ... }

    // 节点类型映射
    private PregelNode toPregelNode(WorkflowNodeDef def) {
        return switch (def.type()) {
            case "intent-detection" -> new IntentDetectionNode(def, chatClient);
            case "tool-call"        -> new ToolNode(def, toolRegistry);
            case "llm"              -> new LlmNode(def, chatClient);
            case "branch"           -> new BranchNode(def);
            case "loop"             -> new LoopNode(def);
            case "human"            -> new HumanNode(def);
            case "output"           -> new OutputNode(def);
            default -> throw new AgentException("Unknown node type: " + def.type());
        };
    }
}
```

### 6.4 执行流程图

```
开发者调用 agent.invoke("查一下北京天气")
    │
    ▼
AbstractAgent.invoke()
    ├── fireBeforeInvoke(ctx)          ← Hook: onBeforeInvoke
    ├── doInvoke(ctx)                  ← 子类实现
    │   │
    │   ├── [DefaultAgenticAgent]
    │   │   └── ReActLoop.execute()
    │   │       ├── iter 1:
    │   │       │   ├── fireBeforeReasoning()     ← Hook
    │   │       │   ├── ChatClient.call()          ← Spring AI
    │   │       │   ├── fireAfterReasoning()       ← Hook
    │   │       │   ├── parseToolCalls() → [getWeather]
    │   │       │   ├── fireBeforeActing()         ← Hook
    │   │       │   ├── toolRegistry.execute()     ← 虚拟线程
    │   │       │   └── fireAfterActing()          ← Hook
    │   │       └── iter 2:
    │   │           ├── ChatClient.call()          ← 带工具结果
    │   │           └── 无 tool_calls → done=true
    │   │
    │   └── [DefaultWorkflowAgent]
    │       └── WorkflowExecutor.run()
    │           └── Pregel.run()
    │               ├── super-step 1: intent-detect
    │               ├── super-step 2: billing-handler
    │               └── super-step 3: respond
    │
    ├── fireAfterInvoke(ctx, result)   ← Hook: onAfterInvoke
    ├── publishEvent(AgentExecutionEvent) ──→ Studio 接收
    └── return AgentResult
```

---

## 七、配置方式

### 7.1 三种配置角色

| 角色 | 配置方式 | 典型场景 |
|------|---------|---------|
| **开发者** | `application.yml` | 定义默认模型、工具、Agent 参数 |
| **架构师** | Java Config（`@Bean` + Builder） | 定制 Agent 行为、注册自定义 Hook |
| **业务部门** | XML 工作流定义 | 修改流程步骤、添加新意图，无需重编译 |

### 7.2 application.yml 示例

```yaml
openjiuwen:
  agent:
    default-model: deepseek-chat        # 默认模型
    max-iterations: 10                  # ReAct 最大迭代次数
  workflow:
    location: classpath:workflows/      # 工作流定义目录
  memory:
    short-term: in-memory               # 短期记忆后端
    long-term:
      backend: pgvector                 # 长期记忆后端
      connection: jdbc:postgresql://localhost/openjiuwen
  studio:
    enabled: true                       # 开发环境开启
    base-path: /openjiuwen/studio
```

### 7.3 Java Config 示例

```java
@Configuration
public class AgentConfig {

    @Bean
    public AgenticAgent researchAgent(ChatClient chatClient, ToolRegistry tools) {
        return AgentBuilder.agentic("research-agent")
            .chatClient(chatClient)
            .tools(tools)
            .maxIterations(15)
            .systemPrompt("你是一个研究助手，擅长分析技术文档。")
            .hook(new LoggingHook())          // 自定义 Hook
            .hook(new BudgetGuardHook(1000))  // Token 预算
            .build();
    }
}
```

### 7.4 XML 工作流示例（业务部门可编辑）

```xml
<!-- workflows/customer-service.xml — 无需重编译，修改后热加载 -->
<workflow name="customer-service" start="intent" end="respond">
    <node id="intent" type="intent-detection">
        <intents>
            <intent name="billing" next="billing"/>
            <intent name="technical" next="tech"/>
            <intent name="general" next="llm"/>
        </intents>
    </node>
    <node id="billing" type="tool-call" tool="billingLookup"/>
    <node id="tech" type="tool-call" tool="knowledgeSearch"/>
    <node id="llm" type="llm"/>
    <node id="respond" type="output"/>
</workflow>
```

---

## 八、Studio 可视化平台

### 8.1 设计原则

| 原则 | 说明 |
|------|------|
| 嵌入式优先 | Spring Boot starter 引入，自动启动 |
| 零前端构建 | Thymeleaf + 静态资源（Vanilla JS + Chart.js），不需要 Node.js |
| 按需启用 | `openjiuwen.studio.enabled=true` 才激活，生产环境默认关闭 |
| 只读为主 | Studio 做"看"不做"改"，改通过代码/YAML |

### 8.2 与 Runtime 的通信

```java
// Runtime 发布事件
applicationContext.publishEvent(new AgentExecutionEvent(
    agentName, sessionId, result, steps, duration, tokensUsed
));

// Studio 监听事件
@EventListener
public void onAgentExecution(AgentExecutionEvent event) {
    traceStore.save(event);
}
```

### 8.3 REST API

```
GET  /openjiuwen/studio/api/agents              # Agent 列表
GET  /openjiuwen/studio/api/agents/{name}       # Agent 详情
POST /openjiuwen/studio/api/agents/{name}/test  # 测试 Agent
GET  /openjiuwen/studio/api/traces              # 执行追踪
GET  /openjiuwen/studio/api/traces/{id}/graph   # 追踪图（Mermaid）
GET  /openjiuwen/studio/api/workflows           # 工作流列表
GET  /openjiuwen/studio/api/evolution/history   # 进化历史
GET  /openjiuwen/studio/api/audit/logs          # 审计日志
GET  /openjiuwen/studio/api/audit/budget        # Token 预算
```

### 8.4 前端页面

| 页面 | 功能 | Phase |
|------|------|-------|
| Dashboard | Agent 概览：在线数量、调用次数、Token 趋势 | 3 |
| Execution Trace | 执行追踪：推理/行动/观察时间线 | 3 |
| Agent Detail | 单 Agent 详情：对话、工具、指标 | 3 |
| Evolution Monitor | 进化仪表盘：评分趋势、A/B 对比 | 4 |
| Budget Overview | Token 预算：四级用量 | 5 |
| Audit Log | 审计日志：调用链、决策追溯 | 5 |

---

## 九、旧代码处理策略

### 9.1 文件级处理映射

| 处理方式 | 文件数 | 占比 | 说明 |
|---------|-------|------|------|
| **删除**（Spring AI 替代） | ~245 | 22% | LLM 客户端（~50）、工具反射（~30）、向量存储直连（~80）、自建配置（~30） |
| **重写**（接口不变，实现换 Spring） | ~165 | 15% | ReAct 循环、Agent 入口、工作流管理 |
| **移植**（适配新接口，核心逻辑保留） | ~326 | 30% | Pregel 图引擎、Agent 进化训练、SPI |
| **丢弃**（dev_tools/实验性代码） | ~354 | 33% | dev_tools（33 文件）、deepagents（7 文件）、冗余测试 |

### 9.2 关键移植清单

| 旧模块 | 新位置 | 核心文件 | 处理方式 |
|--------|--------|---------|---------|
| Pregel 图引擎 | openjiuwen-graph | `Pregel.java`（158行）、`PregelLoop.java`（219行）、`Channel.java` | 直接移植 + Spring 化 |
| Agent 进化训练 | openjiuwen-evolution | `Trainer.java`（618行）、`InstructionOptimizer.java` | 移植 + 适配新 Agent 接口 |
| ReAct 循环 | openjiuwen-runtime/react | `LlmEventHandler.java`（1,240行） | **拆分重写**为 ReActLoop（~300行）+ ReasoningPhase + ActingPhase |
| 多工作流管理 | openjiuwen-runtime/workflow | 工作流跳转/断点续传逻辑 | 移植 + WorkflowCompiler |

### 9.3 Spring AI 替代清单

| 旧模块（删除） | 文件数 | Spring AI 替代 |
|---------------|-------|---------------|
| `foundation/llm/` 自建 HTTP 客户端 | ~50 | ChatModel + 15 提供者 starter |
| `foundation/tool/` 自建反射工具 | ~30 | @Tool + MethodToolCallbackProvider |
| `retrieval/` Milvus/PGVector 直连 | ~80 | VectorStore（22 实现）+ RAG Advisor |
| `foundation/prompt/` 自建模板 | ~15 | PromptTemplate |
| `extensions/mcp/` 部分 MCP | ~20 | MCP Client/Server starter |
| 自建配置加载 | ~30 | @ConfigurationProperties |
| 自建 JSON 处理 | ~20 | Jackson（Spring Boot 默认） |

---

## 十、Python 习气消除规范

> openjiuwen-java v0.1.7 是 Python 直译产物，存在大量 Python 设计模式。v3 重构时，移植模块必须消除以下 8 类 Python 习气，全面 Java 原生化。
> **原则：算法逻辑不动（语言无关），但数据结构、依赖注入、类型系统、异常处理全部 Java 原生化。**

### 10.1 Python 习气 → Java 原生对照表

| # | Python 习气 | 旧代码表现 | v3 Java 原生替代 | 影响范围 |
|---|-----------|-----------|----------------|---------|
| 1 | dict 万物 | 工具定义、配置、函数参数全是 `Map<String, Object>` | **record + sealed interface**，编译期类型安全 | Evolution / Pregel / 工作流 |
| 2 | 动态发现 | `Model.java` 用 `ServiceLoader.load(ModelClientFactory.class)` | **Spring @Component + @Autowired**，自动注入 | 全局（删除旧发现机制） |
| 3 | 上帝类 | `LlmEventHandler.java` 1,240 行、`Workflow.java` 1,291 行、`WorkflowEventHandler.java` 1,274 行、`CallbackFramework.java` 1,443 行 | **拆分**：每个 < 400 行，单一职责 | ReAct 循环 / 工作流 / 回调 |
| 4 | 关键字参数 → 多参构造器 | Agent/Tool 配置用多参构造器或 Map 传参 | **Builder 模式**：`AgentBuilder.agentic("name").maxIterations(10).build()` | 全局 |
| 5 | 字符串分派 | `"plugin".equalsIgnoreCase(invokeType)` 链式 if-else | **enum + switch 表达式** 或 sealed interface + pattern matching | Evolution / 工作流节点 |
| 6 | 异常滥用 | `catch (Exception e)` 后统一抛 RuntimeException | **异常层级**：`AgentException` / `ToolExecutionException` / `BudgetExceededException` | 全局 |
| 7 | 固定线程池 | `Executors.newFixedThreadPool(numWorkers)` | **虚拟线程** `Executors.newVirtualThreadPerTaskExecutor()` | Evolution / Pregel / 工具执行 |
| 8 | 零接口隔离 | 直接暴露内部实现类，无抽象层 | **core 模块纯接口**，runtime 模块实现，开发者只依赖接口 | 全局 |

### 11.2 移植模块改写要求

移植 ≠ 照搬。以下三个模块的算法逻辑保留，但外壳全部 Java 原生化。

#### Pregel 图引擎（~15 文件，从 openjiuwen-graph 移植）

| 改写项 | 旧代码 | 新代码 |
|--------|--------|--------|
| 数据结构 | `Map<String, Object>` 传递消息和状态 | **record**：`ChannelMessage`、`NodeResult`、`SuperStepResult` |
| 依赖发现 | 无（硬编码构建） | **Spring @Component**，`PregelBuilder` 通过 DI 获取节点 |
| 线程模型 | `Executors.newFixedThreadPool()` | **虚拟线程** `newVirtualThreadPerTaskExecutor()` |
| 节点定义 | 继承抽象类 + 字符串 type 字段 | **sealed interface PregelNode**，enum NodeType + switch |
| 配置 | 硬编码常量 | **@ConfigurationProperties** `PregelProperties` |
| 不动 | BSP 超步算法、消息传递、屏障同步 | 算法逻辑不变 |

#### Agent 进化训练（~20 文件，从 agent_evolving/ 移植）

| 改写项 | 旧代码 | 新代码 |
|--------|--------|--------|
| 工具定义 | `Map<String, Object>` 描述工具参数 | **record ToolDefinition** + **record ToolParam** |
| Trainer 配置 | 多参构造器 | **Builder 模式** `TrainerBuilder` |
| 管道分派 | `if ("plugin".equalsIgnoreCase(type))` 链 | **enum InvokeType** + switch 表达式 |
| 异常处理 | `catch (Exception e)` → RuntimeException | **异常层级** `EvolutionException extends AgentException` |
| 线程模型 | BeamSearch 用 `newFixedThreadPool` | **虚拟线程** |
| LLM 调用 | 自建 HTTP 客户端 | **Spring AI ChatClient** |
| 不动 | 评估-优化-写回循环、文本梯度优化、LLM-as-Judge 评分 | 算法逻辑不变 |

#### 工作流管理（~10 文件，移植跳转/断点续传逻辑）

| 改写项 | 旧代码 | 新代码 |
|--------|--------|--------|
| Workflow 上帝类 | `Workflow.java` 1,291 行 | **拆分**：`WorkflowCompiler`（编译）+ `WorkflowExecutor`（执行）+ `WorkflowSession`（会话） |
| 事件处理 | `WorkflowEventHandler.java` 1,274 行 | **拆分**：每个节点类型独立类（LlmNode / ToolNode / BranchNode ...） |
| 回调框架 | `CallbackFramework.java` 1,443 行 | **LifecycleAdvisor**（Spring AI Advisor 桥接）+ 8 事件 Hook |
| 状态管理 | `Map<String, Object>` 存工作流状态 | **record** + **AgentState** 接口 |
| 不动 | 工作流跳转逻辑、断点续传、会话恢复 | 核心逻辑不变 |

### 11.3 新代码 Java 21 规范

所有 v3 新写的代码（非移植）必须遵循：

| 规范 | 说明 |
|------|------|
| **sealed interface** | Agent 三范式、PregelNode、LifecycleEvent 等封闭类型 |
| **record** | 所有 DTO：AgentResult、ToolInfo、MemoryEntry、Plan、PlanStep |
| **virtual threads** | 异步执行用 `Executors.newVirtualThreadPerTaskExecutor()` |
| **pattern matching** | `switch` 表达式 + 类型模式，不用 if-else 链 |
| **Builder 模式** | 所有配置/构建入口用 Builder，不用多参构造器 |
| **Spring DI** | @Component / @Service / @Bean，不用 ServiceLoader 或 new |
| **@ConfigurationProperties** | 所有可配置项用类型安全的 Properties 类 |
| **单一职责** | 单个类不超过 400 行，超过则拆分 |

### 11.4 改写验证清单

每个 Phase 完成时，代码审查必须检查：

```
□ 无 Map<String, Object> 作为公开 API 参数（内部实现可以有，但必须注释说明）
□ 无 ServiceLoader（全部 Spring DI）
□ 无超过 400 行的类
□ 无多参构造器（用 Builder）
□ 无字符串分派 if-else 链（用 enum + switch）
□ 无 catch (Exception e) → RuntimeException（用具体异常）
□ 无 Executors.newFixedThreadPool（用虚拟线程）
□ 公开 API 只暴露接口，不暴露实现类
```

---

## 十一、分阶段交付计划

### 11.1 总览

| 阶段 | 周次 | 交付物 | Agent 范式 | 核心模块 |
|------|------|--------|-----------|---------|
| Phase 0 | 1-2 | Spring Boot 骨架 + Spring AI 通 + 接口定义 | — | core + runtime 骨架 |
| Phase 1 | 3-5 | LLM + Tool + RAG + Memory 层 | — | runtime + memory |
| Phase 2 | 6-9 | **WorkflowAgent** + Pregel 移植 + XML 配置 | Workflow | runtime + graph |
| Phase 3 | 10-14 | **AgenticAgent** + ReAct 循环 + 8 事件 Hook | Agentic | runtime + studio |
| Phase 4 | 15-19 | **ClawAgent** + SkillBox + Evolution 移植 | Claw | evolution + studio |
| Phase 5 | 20-24 | 企业特性（Token Budget + 审计 + 多租户） | 全部 | enterprise + studio |
| Phase 6 | 25-26 | 发布（Maven Central + 文档 + 示例） | 全部 | starter + examples |

### 11.2 各阶段详情

#### Phase 0：基座搭建（2 周）

| 任务 | 产出 |
|------|------|
| Spring Boot 3.3 多模块 Maven 骨架 | core / runtime / graph / starter 模块 |
| Spring AI ChatModel smoke test | OpenAI + Ollama 双 Provider 验证 |
| Agent sealed interface 定义 | Agent / WorkflowAgent / AgenticAgent / ClawAgent |
| AgentContext / AgentResult / AgentState 接口 | core 模块完整接口层 |
| 测试框架 + CI | JUnit 5 + Testcontainers + GitHub Actions |

**验证标准**：`mvn verify` 通过 + Spring AI ChatModel 收发消息成功

#### Phase 1：LLM + Tool + RAG + Memory（3 周）

| 任务 | 产出 |
|------|------|
| ToolRegistry 实现（包装 Spring AI ToolCallback） | 工具注册/查找/动态添加 |
| AgentMemory 实现（ChatMemory + 长期记忆） | 短期+长期双层记忆 |
| RAG Pipeline（复用 Spring AI） | VectorStore + RetrievalAugmentationAdvisor |
| LifecycleAdvisor 桥接 | Spring AI Advisor → 8 事件 Hook |

**验证标准**：@Tool 注册 + RAG 查询 + Memory 读写集成测试通过

#### Phase 2：WorkflowAgent + 图引擎（4 周）★ 第一个可用 Agent

| 任务 | 产出 |
|------|------|
| Pregel 图引擎移植 | openjiuwen-graph 模块（~15 文件） |
| WorkflowCompiler（YAML/XML/Builder → Pregel） | 三种配置统一编译 |
| 7 种内置节点 | LlmNode / ToolNode / BranchNode / LoopNode / IntentDetectionNode / HumanNode / OutputNode |
| DefaultWorkflowAgent 实现 | 第一个可用的 Agent 类型 |
| 工作流跳转/断点续传 | 移植旧项目核心逻辑 |
| XML 热加载 | 业务部门可编辑 |

**验证标准**：WorkflowAgent 端到端测试（含 XML 配置 + YAML 配置 + Java Builder）

#### Phase 3：AgenticAgent + Hook + Studio v1（5 周）★ 最重阶段

| 任务 | 产出 |
|------|------|
| ReActLoop 核心实现 | reasoning() + acting() 分离 |
| DefaultAgenticAgent 实现 | 第二种 Agent 类型 |
| 8 事件 Hook 完整实现 | 参考 agentscope-java |
| PlanNotebook 实现 | 多步规划管理 |
| AgentTool（Agent-as-Tool） | 多 Agent 编排基础 |
| 多 Agent 编排器 | Sequential / Parallel / Loop / Routing |
| Studio v1（Dashboard + Trace + Agent List） | 嵌入式可视化基础 |

**验证标准**：AgenticAgent 自主解决多步问题 + Hook 日志验证 + Studio 可视化

#### Phase 4：ClawAgent + Evolution（5 周）

| 任务 | 产出 |
|------|------|
| DefaultClawAgent 实现 | 第三种 Agent 类型 |
| SkillBox 实现（4 种加载器） | Annotated / YAML / Filesystem / Classpath |
| Agent 进化训练移植 | Trainer + Evaluator + 3 个 Optimizer |
| A2A/MCP Agent 网络注册 | Agent 发现与协作 |
| Studio v2（Evolution Monitor） | 进化仪表盘 |

**验证标准**：ClawAgent 技能加载 + Evolution 改进可量化（评分提升）

#### Phase 5：企业特性（5 周）

| 任务 | 产出 |
|------|------|
| Token Budget 四层管控（Org → Team → Agent → User） | 用量追踪 + 限额执行 |
| 合规审计（全链路记录 + 决策链追溯） | 审计日志 API |
| 多租户隔离 | 命名空间 + 资源隔离 |
| Studio v3（Budget + Audit） | 企业管理页面 |
| 检查点持久化（JDBC / Redis） | 状态恢复 |

**验证标准**：预算限制 + 审计追溯 + 多租户隔离集成测试

#### Phase 6：发布（2 周）

| 任务 | 产出 |
|------|------|
| openjiuwen-spring-boot-starter 完善 | 自动配置 + Properties 元数据 |
| 3 个完整示例项目 | 金融报告 / 设备巡检 / 多 Agent 协作 |
| API 文档 + 架构文档 | Javadoc + README + Architecture Guide |
| Maven Central 发布 | 公开可用 |

**验证标准**：全新项目 `spring init && add starter && run` 可运行

### 11.3 关键路径

```
Phase 0-1（5 周）：验证 Spring AI 集成可行性
    ↓ 必须成功，否则回退到全自建方案
Phase 2（4 周）：交付第一个可用 Agent（WorkflowAgent）
    ↓ 企业验证点：业务部门能用 XML 配工作流
Phase 3（5 周）：交付核心差异化（AgenticAgent + Hook + Studio）
    ↓ 关键里程碑：可用于生产
Phase 4（5 周）：交付独有能力（ClawAgent + Evolution）
    ↓ 护城河建立
Phase 5-6（7 周）：企业特性 + 发布
```

### 11.4 并行机会

```
Week 6-9:  Phase 2 主线                    |  graph 模块独立移植（可提前到 Phase 1 并行）
Week 10-14: Phase 3 Agent Runtime          |  Studio v1 可独立开发
Week 15-19: Phase 4 Evolution 移植         |  SkillBox 可独立开发
```

---

## 十二、人力估算

### 11.1 三种估算场景

| 估算维度 | 纯编码工时 | AI coding 团队 | 传统团队 |
|---------|-----------|--------------|---------|
| Phase 0: 基座 | 8d | 0.3 人月 | 0.5 人月 |
| Phase 1: LLM+Tool+RAG | 12d | 0.4 人月 | 0.7 人月 |
| Phase 2: WorkflowAgent | 20d | 0.8 人月 | 1.2 人月 |
| Phase 3: AgenticAgent + Studio | 35d | 1.5 人月 | 2.0 人月 |
| Phase 4: ClawAgent + Evolution | 25d | 1.2 人月 | 1.5 人月 |
| Phase 5: 企业特性 | 20d | 0.8 人月 | 1.2 人月 |
| Phase 6: 发布 | 10d | 0.4 人月 | 0.5 人月 |
| **纯编码合计** | **130d** | **5.4 人月** | **7.6 人月** |

### 12.2 项目周期估算（含评审联调）

| | AI coding 团队 | 传统团队 |
|---|-------------|---------|
| 纯编码 | 5.4 人月 | 7.6 人月 |
| 设计评审/架构讨论（×1.15） | 0.8 人月 | 1.1 人月 |
| 集成联调/debug（×1.2） | 1.1 人月 | 1.5 人月 |
| 文档/CI/发布（×1.1） | 0.5 人月 | 0.8 人月 |
| **项目周期合计** | **~8 人月** | **~11 人月** |

### 12.3 日历时间

| 团队配置 | AI coding | 传统 |
|---------|----------|------|
| 2 人并行 | 4-5 个月 | 6-7 个月 |
| 3 人并行 | 3-4 个月 | 4-5 个月 |

### 12.4 AI coding 加速因子说明

| 任务类型 | AI coding 加速 | 适用阶段 |
|---------|--------------|---------|
| Spring Boot 配置/boilerplate | 2-3x | Phase 0, 1, 6 |
| 接口定义/sealed interface/record | 1.5-2x | Phase 0 |
| 有参考的实现（Spring AI 封装、agentscope 对标） | 1.5-2x | Phase 1, 2 |
| ReAct 循环等核心逻辑 | 1.2-1.5x | Phase 3 |
| Pregel 移植（算法密集） | 1.0-1.2x | Phase 2 |
| Agent Evolution（独有，无参考） | 1.0x | Phase 4 |
| 联调/debug 集成问题 | 0.8-1.0x | 全程 |

**前提**：AI coding 团队 = 开发者熟练使用 Cursor/Claude Code 级别工具 + 对 Spring AI 有基础了解。

### 12.5 推荐团队配置

| 方案 | 配置 | AI coding 周期 | 适用场景 |
|------|------|--------------|---------|
| A（推荐） | 1 资深 Java/Spring + 1 中级 Java | 4-5 个月 | 平衡速度和质量 |
| B（精简） | 1 全栈开发 | 8-10 个月 | 资源有限 |
| C（加速） | 2 资深 + 1 中级 | 3-4 个月 | 有时间压力 |

---

## 十三、风险分析

| # | 风险 | 概率 | 影响 | 缓解措施 |
|---|------|------|------|---------|
| 1 | Spring AI 1.1.x API breaking change | 中 | 高 | 锁定 1.1.x + Adapter 层隔离 + 跟踪 1.2 变更日志 |
| 2 | Pregel 与 Spring AI 集成摩擦 | 中 | 高 | Phase 2 首周做 POC 验证，备选方案用 SAA Graph 思路重写 |
| 3 | Agent 接口设计反复 | 高 | 中 | Phase 0 定接口 → Phase 2 验证 → 小范围修正 |
| 4 | Agent Evolution 效果难验证 | 中 | 中 | Phase 4 设立 A/B 测试框架 + 质量基线 |
| 5 | 5 种 Agent 模式设计不当导致扩展困难 | 中 | 中 | 核心循环统一（推理→工具→观察），编排是配置差异 |
| 6 | Studio 零 Node.js 限制前端能力 | 低 | 低 | Studio 定位只读可视化，不做复杂交互 |
| 7 | 不引入 Alibaba 生态导致 Qwen/DashScope 适配不完善 | 低 | 低 | Spring AI 已有 DashScope 兼容接口 + OpenAI 格式兼容 |
| 8 | 测试迁移工作量被低估 | 中 | 中 | 旧测试按模块标注"可迁移/需重写/可丢弃" |
| 9 | 人力估算偏乐观（本次已修正） | 中 | 中 | 按 8 人月规划，留 20% buffer |

---

## 十四、开发者体验示例

### 14.1 最小可运行示例（Phase 2 完成后）

```java
// 1. pom.xml — 只需一个 starter
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>

// 2. App.java
@SpringBootApplication
public class App {
    public static void main(String[] args) { SpringApplication.run(App.class, args); }

    @Bean
    @Tool(description = "查询天气")
    public String getWeather(@ToolParam("城市") String city) {
        return weatherService.query(city);
    }

    @Bean
    public CommandLineRunner demo(WorkflowAgent agent) {
        return args -> {
            AgentResult result = agent.invoke("北京天气怎么样？");
            System.out.println(result.output());
        };
    }
}

// 3. application.yml
openjiuwen:
  workflow:
    location: classpath:weather-workflow.xml
```

### 14.2 AgenticAgent 示例（Phase 3 完成后）

```java
@Configuration
public class AgentConfig {

    @Bean
    public AgenticAgent researchAgent(ChatClient chatClient, ToolRegistry tools) {
        return AgentBuilder.agentic("research")
            .chatClient(chatClient)
            .tools(tools)
            .maxIterations(10)
            .systemPrompt("你是一个研究助手。")
            .hook(new LoggingHook())
            .build();
    }
}

// 调用
@Autowired private AgenticAgent researchAgent;

AgentResult result = researchAgent.invoke("分析 Spring AI 和 LangChain4j 的架构差异");
```

### 14.3 ClawAgent 示例（Phase 4 完成后）

```java
ClawAgent agent = AgentBuilder.claw("ops-agent")
    .chatClient(chatClient)
    .tools(tools)
    .skillBox(skillBox)        // 自动发现 @AgentSkill
    .enableEvolution(true)     // 启用自进化
    .build();

agent.loadSkill("kubernetes-diagnosis");
agent.loadSkill("log-analysis");

AgentResult result = agent.invoke("诊断为什么 Pod 一直重启");
EvolutionReport report = agent.triggerEvolution();  // 触发自进化评估
```

---

## 十五、与竞品的关系定位

### 15.1 不是替代，是互补

对 10,000 Java 开发者的叙事：

> **"不是替代 SAA，是让任何框架编排出来的 Agent 更好。"**

- 用 SAA 编排 Agent → 用 OpenJiuwen Evolution 调优
- 用 LangChain4j 构建 Agent → 用 OpenJiuwen Studio 监控
- 用 OpenJiuwen 构建完整方案 → 享受 Framework-agnostic + Agent 演进 + 私有化优先

### 15.2 差异化护城河

| 能力 | openjiuwen | SAA | agentscope | LangChain4j |
|------|-----------|-----|-----------|-------------|
| Agent 自动进化 | **独有** | 无 | 无 | 无 |
| Token 预算四层管控 | **独有** | 无 | 无 | 无 |
| 合规审计全链路 | **独有** | 无 | 无 | 无 |
| Pregel BSP 图引擎 | **独有** | 无 | 无 | 无 |
| 私有云优先 | **核心定位** | 有商业冲突 | — | — |
| 华为昇腾深度适配 | **独占** | 不可能做 | — | — |
| Framework-agnostic | **核心** | Spring 深度绑定 | 可选 | Java 原生 |

---

## 十六、相关文档索引

### 本系列文档

| 文档 | 日期 | 用途 |
|------|------|------|
| [ADR-0001: α 策略决策](ADR-0001-openjiuwen-alpha-strategy.md) | 05-16 | 战略定位 |
| [Java Agent 生态深度调研](2026-05-16-java-agent-ecosystem-deep-research.md) | 05-16 | 市场分析 |
| [竞争格局分析](2026-05-16-java-agent-competitive-landscape.md) | 05-16 | Porter 五力 + 蓝海 |
| [架构设计 v0.2](2026-05-19-openjiuwen-java-agent-architecture-design.md) | 05-19 | 初版架构（AD-002 已过时） |
| [重构源码调研 v2](2026-05-24-openjiuwen-java-refactoring-research-v2.md) | 05-24 | 四项目源码级分析 |
| [Core/Runtime/Studio 详细架构](2026-05-25-openjiuwen-java-core-runtime-studio-architecture.md) | 05-25 | 三模块接口+实现细节 |
| **本文档（v3 架构）** | **05-25** | **完整架构设计** |

### Wiki 文档

| 文档 | 用途 |
|------|------|
| [Spring Boot + Spring AI 重构分析](../../wiki/comparisons/openjiuwen-refactor-spring-ai.md) | 成本估算 + 依赖决策 |

### 外部参考

| 来源 | 用途 |
|------|------|
| [Spring AI 文档](https://docs.spring.io/spring-ai/reference/) | 底层依赖 API 参考 |
| [agentscope-java GitHub](https://github.com/agentscope-ai/agentscope-java) | Agent 接口设计参考 |
| [Spring AI Alibaba GitHub](https://github.com/alibaba/spring-ai-alibaba) | 编排能力验证 |

---

_最后更新：2026-05-25_
