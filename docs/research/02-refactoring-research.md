# openJiuwen-Java 重构调研报告 v2

> 2026-05-24 | 基于四项目完整源码阅读 | 级别：[原文] | 可信度：[最高]

---

## 零、代码规模全景

| 项目 | 主代码文件数 | 主代码行数 | 测试文件数 | 核心模块 |
|------|------------|-----------|-----------|---------|
| **openjiuwen-java** | 1,090 | 124,895 | 237 | 单体 JAR |
| **agentscope-java core** | 346 | 73,507 | 291 | agentscope-core |
| **agentscope-java harness** | 152 | 25,156 | — | agentscope-harness |
| **Spring AI (核心5模块)** | 414 | ~80,000 | — | model/client/commons/rag/vector-store |
| **SAA graph-core** | 200 | 32,343 | — | graph-core |
| **SAA agent-framework** | 159 | 28,458 | 87 | agent-framework |

**openjiuwen-java 是四个项目中代码量最大的（125K 行）**，比 agentscope-core + harness 加起来（99K）还多 26%。

openjiuwen-java 内部分布：

| 子包 | 文件数 | 行数 | 职责 |
|------|-------|------|------|
| `core/` | 914 | 98,925 | 所有核心逻辑 |
| `extensions/` | 60 | 10,270 | 扩展（如 MCP 适配） |
| `agent_evolving/` | 53 | 8,370 | Agent 训练/进化 |
| `dev_tools/` | 33 | 5,706 | 开发调试工具 |
| `spi/` | 23 | 1,449 | 服务提供者接口 |
| `deepagents/` | 7 | 175 | 深度 Agent（实验性） |

---

## 一、openjiuwen-java 代码级架构分析

### 1.1 ReAct 循环实现（最核心的 1,240 行）

ReAct 循环的核心实现在 `LlmEventHandler.java`（**1,240 行，整个项目最大的单文件**）：

```
用户输入 → handleUserInput()
  → executeReactLoop()        // 主循环，非递归迭代
    → generatePlanFromLlm()   // 调 LLM 生成 tool calls（作为 tasks）
    → executeTask()           // 执行单个 task
    → 结果加入 context
    → 判断是否继续循环
```

**具体调用链**：
1. `LlmAgent.invoke()` / `LlmAgent.stream()` → 入口
2. `Controller.stream()` → 事件驱动循环
3. `LlmEventHandler.handleUserInput()` → ReAct 入口
4. `executeReactLoop()` → 迭代主循环（非递归，while 循环）
5. `generatePlanFromLlm()` → 调用 `Model.invoke()`（自建 HTTP 客户端）
6. LLM 返回 → 解析为 `Task`（tool calls）
7. `TaskScheduler.executeTask()` → 虚拟线程执行
8. `TaskExecutor.executeAbility()` → 具体工具调用
9. 结果流回 → 加入对话上下文 → 回到第 5 步

**设计问题**：
- ReAct 逻辑全部塞在 1,240 行的 LlmEventHandler 里，没有抽象出独立的 Reasoning/Acting/Observation 阶段
- 缺少 Hook/拦截点——无法在推理前/后、工具执行前/后插入自定义逻辑
- LLM 调用和 Task 执行耦合在一起，无法独立测试

### 1.2 工具系统（~30 文件）

- `Tool.java`：抽象基类，`invoke()` / `stream()` 方法
- `AnnotatedToolFactory.java`：通过反射从 `@ToolDefinition` 注解创建工具
- `TypeSchemaExtractor.java`：从 Java 类型自动生成 JSON Schema
- `LocalFunction.java`：本地方法调用执行器
- **与 Spring AI 的对应**：Spring AI 的 `@Tool` + `MethodToolCallbackProvider` + `ToolCallingManager` 完全覆盖此功能

### 1.3 LLM 客户端（~50 文件）

- `Model.java`（190 行）：统一入口，使用 `ServiceLoader` 发现 `ModelClient` 实现
- `BaseModelClient.java`（346 行）：HTTP 客户端基类，手动构建请求
- 具体实现：OpenAI、DashScope 等，各自处理 API 格式差异
- **与 Spring AI 的对应**：Spring AI 的 `ChatModel` 接口 + 15+ 提供者实现完全覆盖

### 1.4 图执行引擎（Pregel 模型）

- `Pregel.java`（158 行）：BSP 执行引擎，支持超步同步、消息传递、状态持久化
- `PregelLoop.java`：单个超步的管理
- `CompiledGraph.java`：图编译产物
- `GraphEngine.java`：图构建 API
- **独特价值**：这是 openjiuwen 独有的资产——agentscope-java 没有图引擎，SAA 的 StateGraph 是另一种实现。Pregel 的异步并行执行能力在 Agent 集群场景下有优势

### 1.5 Agent 进化训练（53 文件，8,370 行）

- `Trainer.java`（618 行）：编排 Evaluate → Update → Writeback 循环
- `DefaultEvaluator.java`：LLM-as-judge 评估
- `InstructionOptimizer.java`：文本梯度优化 + prompt 重写
- `MemoryOptimizer.java`：记忆策略优化
- `ToolCallOptimizer.java`：工具调用策略优化
- `CheckpointManager.java`：检查点/恢复
- `TracerTrajectoryExtractor.java`：从执行轨迹提取训练数据
- **独特价值**：四个项目中唯一有 Agent 自动训练能力的。agentscope-java 和 SAA 都没有对应模块

### 1.6 依赖关系（pom.xml）

```
Java 21 | Jackson 2.17.0 | SnakeYAML 2.2 | SLF4J/Logback | Lombok
Bouncy Castle | Project Reactor 3.6.2 | Milvus SDK | PGVector | PDFBox | POI-OOXML
```

**关键发现：零 Spring 依赖**。整个项目不依赖 Spring Boot、Spring AI 或任何 Spring 模块。

---

## 二、agentscope-java 代码级架构分析

### 2.1 Agent 接口层级（设计最清晰的参考）

```
Agent (接口，组合了三个能力接口)
  ├── CallableAgent    → Mono<Msg> call(List<Msg>)
  ├── StreamableAgent  → Flux<Event> stream(...)
  └── ObservableAgent  → Mono<Void> observe(Msg)

AgentBase (抽象基类)
  ├── Hook 管理、中断处理、状态管理
  ├── acquireExecution() / releaseExecution()  // AtomicBoolean 保证单线程执行
  └── 抽象方法：doCall(List<Msg>)

StructuredOutputCapableAgent → 加入 generate_response 工具模式

ReActAgent → 完整实现
  ├── reasoning() → 流式调用 Model，累积 chunks
  ├── acting() → 执行工具调用（并行/串行）
  ├── summarizing() → 超过 maxIters 时的总结
  └── 中断检查点：checkInterruptedAsync()
```

**关键设计差异**：
- agentscope-java 的 `reasoning()` 和 `acting()` 是**明确分离的方法**，各自有 Pre/Post Hook
- openjiuwen-java 的 ReAct 逻辑全部混在 `LlmEventHandler.executeReactLoop()` 里，没有阶段分离

### 2.2 Hook 系统（8 种事件）

```
PreCallEvent → PostCallEvent           （整个 Agent 调用前后）
PreReasoningEvent → PostReasoningEvent （每次 LLM 调用前后，可修改）
PreActingEvent → PostActingEvent       （每次工具执行前后，可修改）
PreSummaryEvent → PostSummaryEvent     （总结阶段）
ReasoningChunkEvent / ActingChunkEvent （流式 chunk，只读）
ErrorEvent                              （错误通知）
```

优先级：0-50（系统级）→ 51-100（高优先）→ 101-500（正常）→ 501-1000（低优先如日志）

### 2.3 Model 接口（极简）

```java
public interface Model {
    Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);
    String getModelName();
}
```

内置 6 种格式化器：DashScope、OpenAI、Anthropic、Gemini、Ollama、DeepSeek

### 2.4 工具系统

- `@Tool` / `@ToolParam` 注解 → 自动生成 ToolSchema
- `Toolkit`（组合模式）：ToolRegistry + ToolGroupManager + ToolSchemaProvider + ToolExecutor
- `SubAgentTool`：Agent 作为工具，支持多轮会话
- `McpClientManager`：MCP 工具集成

### 2.5 独有能力（openjiuwen-java 缺失的）

| 能力 | 实现方式 | 代码量 |
|------|---------|-------|
| PlanNotebook | 内置计划管理 + Hook 注入系统提示 | ~500 行 |
| SkillBox | 声明式 @AgentSkill + Git/MySQL/FS 仓库 | ~800 行 |
| LongTermMemory | 语义搜索跨会话记忆，3 种模式 | ~400 行 |
| AutoContextMemory | 基于 Token 比率智能压缩 | ~300 行 |

### 2.6 与 Spring 的关系

- **核心零 Spring 依赖**
- 提供可选的 `agentscope-spring-boot-starters` 扩展
- 也可在 Quarkus / Micronaut 中使用

---

## 三、Spring AI 可复用清单

### 3.1 可以直接复用的（不用重写）

| Spring AI 组件 | 替代 openjiuwen 的 | 复用方式 |
|---------------|-------------------|---------|
| `ChatModel` + 15 提供者 | `foundation/llm/` 自建 HTTP 客户端（~50 文件） | 直接用 Spring AI starter |
| `@Tool` + `MethodToolCallbackProvider` | `foundation/tool/` 自建反射工具系统（~30 文件） | 直接用 Spring AI @Tool |
| `ToolCallingManager` | `TaskExecutor` + `LocalFunction` | 直接用 |
| `ChatMemory` + 6 持久化后端 | `memory/` 自建记忆系统（部分） | 直接用，扩展 LongTerm |
| `VectorStore` 22 实现 | `retrieval/` Milvus/PGVector 直连 SDK（~80 文件） | 直接用 Spring AI starter |
| `RetrievalAugmentationAdvisor` | `retrieval/` 自建 RAG 管线 | 直接用 |
| `PromptTemplate` | `foundation/prompt/` 自建模板 | 直接用 |
| `MCP Client/Server` | `extensions/` 部分 MCP 适配 | 直接用 |
| `Micrometer Observation` | 无（openjiuwen 无可观测性） | 直接用 |
| `@ConfigurationProperties` | 自建 YAML/JSON 配置加载 | 直接用 |

### 3.2 不能复用的（需要自建）

| 需要自建 | 参考来源 | 原因 |
|---------|---------|------|
| `Agent` 接口层级 | agentscope-java AgentBase | Spring AI 无 Agent 抽象 |
| ReAct 循环 | agentscope-java ReActAgent | Spring AI 的 ToolCallAdvisor 只有工具循环，无 Reasoning 阶段 |
| Hook 系统 | agentscope-java 8 事件 Hook | Spring AI 的 Advisor 链粒度不够（只有 call/stream 两层） |
| 图执行引擎 | openjiuwen Pregel（已有） | Spring AI 无图引擎 |
| 多 Agent 编排 | SAA FlowAgent 系列 | Spring AI 无多 Agent 概念 |
| AgentTool（Agent-as-Tool） | SAA AgentTool | Spring AI 无此机制 |
| LongTermMemory | agentscope-java | Spring AI ChatMemory 只存消息，无语义搜索 |
| PlanNotebook | agentscope-java | Spring AI 无计划管理 |
| SkillBox | agentscope-java | Spring AI 无技能系统 |
| Agent 进化训练 | openjiuwen agent_evolving（已有） | 无任何框架有此能力 |

### 3.3 Spring AI Advisor 链作为 ReAct 基础的可行性分析

Spring AI 的 `ToolCallAdvisor` 实现了：
```
调用模型 → 检测工具调用意图 → 执行工具 → 将结果注入对话 → 重复
```

**缺少的部分**：
1. **无 Reasoning 阶段**：没有显式的"思考"步骤，只有工具调用
2. **无 Hook 生命周期**：没有 pre/post 推理、pre/post 工具执行的拦截点
3. **无状态管理**：所有状态在消息列表中，无法区分"思考"和"行动"
4. **无迭代计数**：没有 maxIterations 概念

**结论**：`ToolCallAdvisor` 可以作为 Agent 循环的 **Act 执行器**，但 ReAct 的 Reasoning/Planning/Hook/State 需要在它之上自建。

---

## 四、SAA 架构验证

SAA 证明了在 Spring AI 之上构建完整 Agent 框架是可行的。其最小必要集合：

### 4.1 SAA 的最小 Agent 实现（~3,200 行核心代码）

```
StateGraph (图构建器) → 约 500 行核心
CompiledGraph (编译产物) → 约 600 行
ReactAgent (ReAct Agent) → 约 800 行
  ├── AgentLlmNode (LLM 调用节点)
  ├── AgentToolNode (工具执行节点)
  └── 条件边路由（think → act → think 循环）
AgentTool (Agent-as-Tool) → 约 300 行
Hook 系统 → 约 500 行
```

### 4.2 SAA 的设计取舍

**SAA 选择了"一切皆图"**：
- Agent 是 `StateGraph` 的包装器
- ReAct 循环 = LLM 节点 ↔ Tool 节点的条件路由
- 多 Agent 编排 = FlowAgent 也是一种图

**openjiuwen 应该选择"Agent 是核心，图是可选编排"**：
- 更接近 agentscope-java 的设计哲学
- 简单场景不需要图，直接用 ReActAgent
- 复杂场景才需要图引擎（Pregel）
- 保留灵活性

### 4.3 SAA 的 Alibaba 绑定（需要解绑的部分）

| SAA 组件 | Alibaba 绑定 | 替代方案 |
|---------|-------------|---------|
| DashScope 模型 | `dashscope-sdk-java 2.15.1` | Spring AI ChatModel（模型无关） |
| Nacos 配置/发现 | `nacos-client 3.1.0` | Spring Cloud Config + Eureka/Consul |
| fastjson | `fastjson 1.2.83` | Jackson（Spring Boot 默认） |
| A2A SDK | `a2a-java-sdk 0.2.5.Beta2` | 基于 Spring AI MCP 传输自建 |
| MyBatis-Plus (Admin) | `mybatis-plus` | Spring Data JPA |

---

## 五、逐能力对比矩阵

| # | 能力维度 | openjiuwen 现状 | agentscope-java | Spring AI | SAA | **重构目标** |
|---|---------|----------------|----------------|-----------|-----|------------|
| 1 | 模型接入 | 自建 HTTP（~50文件） | 自建 6 格式化器 | **15+ ChatModel 提供者** | 通过 Spring AI | **复用 Spring AI** |
| 2 | Agent 接口 | 无（散在 EventHandler） | **Agent→AgentBase→ReActAgent** | 无 | Agent→BaseAgent→ReactAgent | **自建，参考 agentscope** |
| 3 | ReAct 循环 | LlmEventHandler 1240行 | **reasoning()+acting() 分离** | ToolCallAdvisor（仅工具循环） | LlmNode+ToolNode+条件边 | **自建，阶段分离** |
| 4 | 工具系统 | 自建反射（~30文件） | @Tool+Toolkit | **@Tool+ToolCallback** | 通过 Spring AI | **复用 Spring AI** |
| 5 | Hook/拦截器 | 无 | **8 事件 Hook** | Advisor 链（2 层） | 4 位置 Hook+JumpTo | **自建 8 事件** |
| 6 | 短期记忆 | 自建 | InMemoryMemory | **ChatMemory+6 DB** | 图状态消息 | **复用 Spring AI** |
| 7 | 长期记忆 | 无 | **LongTermMemory（3 模式）** | 无 | 无 | **自建，参考 agentscope** |
| 8 | 智能压缩 | 无 | **AutoContextMemory** | 无 | 无 | **自建，参考 agentscope** |
| 9 | 向量存储 | Milvus/PGVector 直连 | 扩展（5 种 RAG 后端） | **22 种 VectorStore** | 通过 Spring AI | **复用 Spring AI** |
| 10 | RAG 管线 | 自建 | GenericRAGHook | **模块化 RAG+Advisor** | KnowledgeRetrievalNode | **复用 Spring AI** |
| 11 | 图引擎 | **Pregel（独有）** | 无 | 无 | StateGraph | **保留+Spring 化** |
| 12 | 多 Agent 编排 | group/层级控制 | Pipeline+SubAgentTool | 无 | **4 种 FlowAgent** | **自建 FlowAgent** |
| 13 | Agent-as-Tool | 无 | SubAgentTool | 无 | **AgentTool** | **自建 AgentTool** |
| 14 | PlanNotebook | 无 | **有（独有）** | 无 | 无 | **自建，参考 agentscope** |
| 15 | SkillBox | 无 | **有（Git/MySQL/FS 仓库）** | 无 | 无 | **自建，参考 agentscope** |
| 16 | Agent 训练 | **有（独有，53 文件）** | 无 | 无 | 无 | **保留+Spring 化** |
| 17 | 多工作流管理 | 有（跳转/断点续传） | Session+StateModule | 无 | 检查点持久化 | **保留核心逻辑** |
| 18 | 可观测性 | 无 | OpenTelemetry（可选） | **Micrometer 原生** | 通过 Micrometer | **复用 Spring AI** |
| 19 | MCP | 部分适配 | 原生 MCP 工具 | **客户端+服务端** | MCP 节点 | **复用 Spring AI** |
| 20 | A2A | 无 | extensions-a2a | 无 | **内置** | **后期自建** |
| 21 | Spring Boot | **无** | 可选 Starter | **一等公民** | **强制** | **强制 Spring Boot** |

**统计**：
- 可以直接复用 Spring AI 的：**10 项**（#1,4,6,9,10,18,19,21 + 配置/序列化）
- 需要自建但参考 agentscope-java 的：**6 项**（#2,3,5,7,14,15）
- 需要自建但参考 SAA 的：**2 项**（#12,13）
- openjiuwen 独有资产保留：**3 项**（#11,16,17）

---

## 六、重构路线图

### 设计原则

1. **Spring AI 是基座**：模型/工具/存储/RAG/MCP 全部复用，不重造轮子
2. **agentscope-java 是设计参考**：Agent 层级、Hook、Plan、Skill 的设计模式借鉴但不依赖
3. **SAA 验证可行性**：其 ReactAgent+FlowAgent 证明 Spring AI 上构建 Agent 可行
4. **保留 openjiuwen 核心资产**：Pregel 引擎、agent_evolving、多工作流管理
5. **零 Alibaba 依赖**：agentscope-java、agentscope、spring-ai-alibaba、DashScope SDK、Nacos、fastjson 全部不用

### 阶段 0：项目骨架（1 周）

建立 Spring Boot 多模块 Maven 项目。

```
openjiuwen/
├── pom.xml                           # parent POM, 继承 spring-boot-starter-parent
├── openjiuwen-bom/                   # BOM 统一版本管理
├── openjiuwen-core/                  # Agent 接口 + Hook + Memory + Plan + Skill
├── openjiuwen-agent-react/           # ReActAgent 实现
├── openjiuwen-agent-flow/            # FlowAgent（Sequential/Parallel/Loop/Routing）
├── openjiuwen-graph/                 # Pregel 图引擎（从旧项目移植）
├── openjiuwen-evolution/             # Agent 训练/进化（从旧项目移植）
├── openjiuwen-autoconfigure/         # Spring Boot 自动配置
├── openjiuwen-starter/               # 一站式 starter
├── openjiuwen-mcp/                   # MCP 集成（可选）
├── openjiuwen-a2a/                   # A2A 协议（可选，后期）
└── openjiuwen-examples/              # 示例
```

**关键 pom.xml 依赖**：
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.x</version>
</parent>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.1.x</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**交付物**：空项目骨架 + CI 跑通 + 依赖版本锁定

**成本**：0.25 人月

### 阶段 1：Spring AI 基座替换（3 周）

**目标**：删除被 Spring AI 完全替代的代码。

**删除清单**：

| 旧代码 | 文件数 | 替代方案 | 动作 |
|--------|-------|---------|------|
| `foundation/llm/` 自建 LLM 客户端 | ~50 | Spring AI ChatModel | **删除** |
| `foundation/tool/` 自建工具系统 | ~30 | Spring AI @Tool + ToolCallingManager | **删除** |
| `retrieval/` 向量存储直连 | ~80 | Spring AI VectorStore starters | **删除** |
| `foundation/prompt/` 模板系统 | ~15 | Spring AI PromptTemplate | **删除** |
| `foundation/store/` 部分（向量相关） | ~40 | Spring AI VectorStore | **删除** |
| `common/utils/` JSON/Schema 工具 | ~20 | Spring AI + Jackson 自动配置 | **删除** |
| 自建配置加载 | ~10 | @ConfigurationProperties | **删除** |
| **合计删除** | **~245 文件** | | |

**保留清单**：

| 旧代码 | 文件数 | 理由 | 动作 |
|--------|-------|------|------|
| `controller/` 核心逻辑 | ~45 | 任务调度、意图识别是业务逻辑 | **重构适配** |
| `application/` Agent 类型 | ~25 | LlmAgent、WorkflowAgent 的业务逻辑 | **重构适配** |
| `graph/` 图引擎 | ~60 | Pregel 是独有资产 | **移植+Spring 化** |
| `agent_evolving/` 训练 | 53 | 独有资产 | **移植+Spring 化** |
| `memory/` 部分 | ~30 | 长期记忆/压缩是扩展能力 | **保留核心+扩展** |
| `workflow/` 多工作流 | ~25 | 独有资产 | **保留** |
| `multiagent/` 多 Agent | ~20 | 保留编排逻辑 | **重构适配** |
| `session/` + `context/` | ~30 | 会话和上下文管理 | **适配 Spring** |
| `security/` | ~15 | 安全相关 | **适配 Spring Security** |
| `spi/` | 23 | 扩展点定义 | **保留** |
| **合计保留** | **~326 文件** | | |

**删除率**：约 245 / 1090 = **22% 直接删除**，另有约 **15% 需要大幅重写**（适配 Spring AI 接口）。

**交付物**：
- 旧代码清理完毕
- Spring AI ChatModel、@Tool、VectorStore、ChatMemory 配置生效
- 现有测试中不依赖被删除模块的通过率 ≥ 90%

**成本**：0.75 人月

### 阶段 2：Agent 层重建（5 周）★ 核心阶段

**目标**：构建 Spring-native 的 Agent 抽象体系。

#### 2.1 Agent 接口（参考 agentscope-java，~200 行）

```java
// 三接口组合，参考 agentscope-java 的 CallableAgent/StreamableAgent/ObservableAgent
public interface Agent extends CallableAgent, StreamableAgent {
    String name();
    AgentConfig config();
    List<AgentHook> hooks();
}

public interface CallableAgent {
    Mono<AgentResponse> call(AgentInput input);
    <T> Mono<T> call(AgentInput input, Class<T> responseType);
}

public interface StreamableAgent {
    Flux<AgentEvent> stream(AgentInput input);
}
```

#### 2.2 AgentBase 抽象基类（参考 agentscope-java，~400 行）

```java
public abstract class AgentBase implements Agent {
    // Hook 管理（优先级排序）
    // 中断机制（AtomicBoolean）
    // 执行锁（单线程执行保证）
    // RuntimeContext 传播
    // Spring AI ChatClient 注入
}
```

#### 2.3 ReActAgent（参考 agentscope-java reasoning()+acting() 分离，~600 行）

```java
public class ReActAgent extends AgentBase {
    private final ChatClient chatClient;        // Spring AI
    private final List<ToolCallback> tools;      // Spring AI
    private final ChatMemory memory;             // Spring AI
    private final List<AgentHook> hooks;         // 自建
    private final int maxIterations;

    // 分离的 ReAct 阶段：
    protected Mono<ReasoningResult> reasoning(AgentContext ctx);
    protected Mono<ActingResult> acting(AgentContext ctx);
    protected Mono<SummaryResult> summarizing(AgentContext ctx);
}
```

#### 2.4 AgentHook 系统（参考 agentscope-java 8 事件，~300 行）

```java
public interface AgentHook {
    <T extends AgentEvent> Mono<T> onEvent(T event);
    default int priority() { return 100; }
}

// 事件层级（sealed class）：
// PreCallEvent, PostCallEvent
// PreReasoningEvent, PostReasoningEvent
// PreActingEvent, PostActingEvent
// PreSummaryEvent, PostSummaryEvent
// ReasoningChunkEvent, ActingChunkEvent (只读)
// ErrorEvent
```

#### 2.5 LongTermMemory（参考 agentscope-java，~400 行）

```java
public interface LongTermMemory {
    Flux<MemoryEntry> search(String query, int topK);
    Mono<Void> record(MemoryEntry entry);
    Mono<Void> forget(String entryId);
}

// 3 种模式：AGENT_CONTROL, AUTO_CONTROL, BOTH
```

#### 2.6 AutoContextMemory（参考 agentscope-java，~300 行）

```java
// 基于 Token 比率的智能压缩
// 包装 Spring AI ChatMemory
public class AutoContextMemory implements ChatMemory {
    private final ChatMemory delegate;  // 实际存储
    private final int maxTokens;
    private final ChatModel compressionModel;  // 用于压缩
}
```

#### 2.7 PlanNotebook（参考 agentscope-java，~200 行）

```java
public class PlanNotebook {
    private final ChatModel model;
    public Mono<Plan> createPlan(String goal);
    public Mono<Void> updateStep(int index, StepStatus status);
    public Flux<PlanStep> getNextSteps();
}
```

#### 2.8 SkillManager（参考 agentscope-java SkillBox，~400 行）

```java
@AgentSkill(name = "weather_query", description = "...")
public class WeatherSkill {
    @Tool public String getWeather(String city) { ... }
}

public class SkillManager {
    public List<ToolCallback> loadSkill(String name);
    public void registerRepository(SkillRepository repo);  // FS/Classpath/Git
}
```

**交付物**：
- Agent/AgentBase/ReActAgent 接口和实现
- 8 事件 Hook 系统
- LongTermMemory + AutoContextMemory
- PlanNotebook
- SkillManager + @AgentSkill
- 完整集成测试 + 示例代码

**成本**：1.25 人月

### 阶段 3：图引擎 + 多 Agent 编排（3 周）

#### 3.1 Pregel 引擎 Spring 化（~60 文件移植）

- 将现有 `graph/` 包移植到 `openjiuwen-graph` 模块
- 替换自建异步 IO 为 Spring Reactor
- 图状态持久化改为 Spring AI 的 ChatMemory/VectorStore
- 可观测性集成 Micrometer
- 添加 DSL 层，降低 Pregel 使用门槛

#### 3.2 FlowAgent 系列（参考 SAA，~800 行）

```java
// SequentialAgent：子 Agent 顺序执行
SequentialAgent.builder().agents(a1, a2, a3).build();

// ParallelAgent：子 Agent 并行执行
ParallelAgent.builder().agents(a1, a2, a3).maxConcurrency(3).build();

// LoopAgent：循环执行直到条件满足
LoopAgent.builder().agent(a1).maxLoops(5).condition(r -> r.score() > 0.9).build();

// RoutingAgent：LLM 路由到不同子 Agent
RoutingAgent.builder().agents(Map.of("weather", a1, "stock", a2)).router(chatClient).build();
```

#### 3.3 AgentTool 机制（参考 SAA，~200 行）

```java
// 任何 Agent 可以包装为 ToolCallback
ToolCallback agentTool = AgentTool.from(weatherAgent)
    .name("weather_specialist")
    .description("Delegate weather queries")
    .build();

// 注册到另一个 Agent 的工具列表中
ReActAgent supervisor = ReActAgent.builder()
    .tools(agentTool, otherTool)
    .build();
```

**交付物**：
- Spring 化 Pregel 图引擎
- 4 种 FlowAgent
- AgentTool 机制
- 多 Agent 编排示例

**成本**：0.75 人月

### 阶段 4：Agent 进化训练移植（2 周）

- `Trainer` → Spring `@Scheduled` + `TaskExecutor`
- `CheckpointManager` → Spring Data JDBC
- `Evaluator` → 复用 Spring AI ChatModel
- `TrajectoryExtractor` → Micrometer Tracing
- `Optimizer` 系列 → 保留核心算法，替换 IO 层

**成本**：0.5 人月

### 阶段 5：生产特性（3 周）

- Spring Boot 自动配置（`openjiuwen-autoconfigure`）
- Micrometer + OpenTelemetry 可观测性
- REST API（Spring MVC）
- MCP 集成（复用 Spring AI MCP）
- Admin UI（可选，Spring Boot + Thymeleaf 或 React）

**成本**：0.75 人月

---

## 七、开发成本汇总

| 阶段 | 内容 | 人月 | 关键风险 |
|------|------|------|---------|
| 0 | 项目骨架 | 0.25 | 低 |
| 1 | Spring AI 基座替换（删 245 文件） | 0.75 | 中（测试兼容性） |
| 2 | **Agent 层重建（核心）** | **1.25** | **中高（设计决策多）** |
| 3 | 图引擎 + 多 Agent 编排 | 0.75 | 中（Pregel Spring 化） |
| 4 | Agent 进化训练 | 0.5 | 低（移植为主） |
| 5 | 生产特性 | 0.75 | 低 |
| **合计** | | **4.25 人月** | |

### 人力配置

| 方案 | 配置 | 周期 | 适用场景 |
|------|------|------|---------|
| A（推荐） | 2 核心开发 + 1 兼职架构评审 | 3-4 个月 | 平衡速度和质量 |
| B（精简） | 1 全栈开发 | 5-6 个月 | 资源有限 |
| C（激进） | 3 全栈开发 | 2-3 个月 | 有时间压力 |

### 风险矩阵

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| Spring AI 1.1.x API 变更 | 中 | 中 | 锁定版本 + Adapter 层隔离 |
| Pregel 与 Spring Reactor 集成复杂 | 中 | 高 | 阶段 1 末做 POC 验证 |
| Agent 层设计反复 | 高 | 中 | 先定接口再实现，架构评审卡点 |
| agent_evolving 对旧 API 深度依赖 | 中 | 低 | 先跑通核心路径，边缘 case 后补 |
| 测试迁移工作量被低估 | 中 | 中 | 旧测试按模块标注"可迁移/需重写/可丢弃" |

---

## 八、代码删除/保留/重写全景图

```
openjiuwen-java (1,090 文件, 124,895 行)
│
├── 【删除】~245 文件 (22%)
│   ├── foundation/llm/          (~50 文件)  → Spring AI ChatModel
│   ├── foundation/tool/         (~30 文件)  → Spring AI @Tool
│   ├── retrieval/ 向量存储部分   (~80 文件)  → Spring AI VectorStore
│   ├── foundation/prompt/       (~15 文件)  → Spring AI PromptTemplate
│   ├── foundation/store/ 部分   (~40 文件)  → Spring AI VectorStore
│   └── common/utils/ 部分       (~30 文件)  → Spring AI + Jackson
│
├── 【重写】~165 文件 (15%)
│   ├── controller/              (~45 文件)  → 重构为 Agent+Hook 体系
│   ├── application/             (~25 文件)  → 重构为 ReActAgent+FlowAgent
│   ├── memory/ 部分             (~20 文件)  → 适配 Spring AI ChatMemory + 扩展
│   ├── session/ + context/      (~30 文件)  → 适配 Spring 管理
│   ├── multiagent/              (~20 文件)  → 重构为 FlowAgent
│   ├── workflow/                (~25 文件)  → 适配图引擎新 API
│   └── security/                (~15 文件)  → 适配 Spring Security
│   │
│   重写后新增代码（不在旧项目中）：
│   ├── Agent 接口层级                       (~200 行)
│   ├── AgentBase 抽象基类                    (~400 行)
│   ├── Hook 系统（8 事件）                    (~300 行)
│   ├── LongTermMemory + AutoContextMemory     (~700 行)
│   ├── PlanNotebook                          (~200 行)
│   ├── SkillManager                          (~400 行)
│   ├── FlowAgent 4 种实现                     (~800 行)
│   ├── AgentTool                             (~200 行)
│   └── Spring Boot 自动配置                   (~500 行)
│   新增合计约 ~3,700 行核心代码
│
├── 【移植】~326 文件 (30%)
│   ├── graph/                   (~60 文件)  → Pregel 引擎，改 IO 层为 Reactor
│   ├── agent_evolving/          (53 文件)   → 训练模块，改基础设施为 Spring
│   ├── spi/                     (23 文件)   → 直接移植
│   ├── extensions/ 部分         (~60 文件)  → 选择性移植
│   ├── dev_tools/               (33 文件)   → 选择性移植
│   └── 其他适配                  (~100 文件) → 接口适配层
│
└── 【丢弃】~354 文件 (33%)
    └── 旧测试中依赖已删除模块的 (~200 文件) + 旧示例 (~80 文件) + 过时工具类 (~74 文件)
```

---

## 九、重构后的分层架构

```
┌────────────────────────────────────────────────────────┐
│  业务应用层 (用户代码)                                    │
│  使用 ReActAgent / FlowAgent / SkillManager / PlanNotebook │
├────────────────────────────────────────────────────────┤
│  openjiuwen Agent 层 (自建)                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ │
│  │ReActAgent│ │FlowAgent │ │AgentTool │ │Evolution  │ │
│  │+Hook系统  │ │Sequential│ │Agent     │ │Trainer    │ │
│  │+Memory扩展│ │Parallel  │ │as Tool   │ │+Evaluator │ │
│  │+Plan     │ │Loop      │ │          │ │+Optimizer │ │
│  │+Skill    │ │Routing   │ │          │ │           │ │
│  └──────────┘ └──────────┘ └──────────┘ └───────────┘ │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Pregel 图引擎 (移植自旧项目)                        │  │
│  │ BSP 异步并行 + 状态持久化 + 断点续传                 │  │
│  └──────────────────────────────────────────────────┘  │
├────────────────────────────────────────────────────────┤
│  Spring AI (复用，不重写)                                 │
│  ChatModel + ChatClient + @Tool + ToolCallingManager    │
│  Advisor Chain + ChatMemory + VectorStore (22 种)       │
│  RAG Pipeline + MCP Client/Server + Micrometer          │
├────────────────────────────────────────────────────────┤
│  Spring Boot (自动配置 / 可观测性 / 安全 / 部署)           │
├────────────────────────────────────────────────────────┤
│  Java 21 + Project Reactor                               │
└────────────────────────────────────────────────────────┘
```

**与竞品的定位差异**：

| 层级 | agentscope-java | SAA | **openjiuwen 重构后** |
|------|----------------|-----|---------------------|
| Agent 层 | Agent+ReAct | ReactAgent+FlowAgent | **ReActAgent+FlowAgent** |
| 编排层 | Pipeline（弱） | StateGraph（强） | **Pregel（最强）+ DSL** |
| 训练层 | **无** | **无** | **agent_evolving（独有）** |
| 模型层 | 自建 6 格式化器 | DashScope 为主 | **Spring AI 15+ 模型** |
| 框架 | 无框架（可选） | Spring Boot | **Spring Boot（一等公民）** |
| 生态绑定 | Alibaba（可选） | Alibaba（强） | **无绑定** |

---

## 十、结论

### 重构价值

openjiuwen-java 的 1,090 个文件、125K 行代码中：
- **22% 可直接删除**（被 Spring AI 完全替代）
- **33% 可丢弃**（旧测试、旧示例、过时代码）
- **15% 需要重写**（Agent 层、编排层）
- **30% 可移植保留**（Pregel 引擎、训练模块、SPI）

重构后净代码量预计：**~60-70K 行**（删除 ~55K 行旧代码，新增 ~3.7K 行核心代码）。

### 核心差异化竞争力

重构后的 openjiuwen-java = **Spring AI 生态 + agentscope 级 Agent 智能 + Pregel 图引擎 + Agent 自动训练**

这在 Java Agent 框架市场中是独一无二的组合：
- agentscope-java 没有 Pregel 和 agent_evolving
- SAA 没有 agent_evolving 且绑定 Alibaba
- Spring AI 没有 Agent 层
- 没有任何框架同时拥有图引擎和 Agent 训练

### 执行建议

1. **先做阶段 0+1 的 POC**（2 周）：验证 Spring AI 替换可行性和 Pregel Reactor 兼容性
2. **阶段 2 先定接口再做实现**：Agent/AgentBase/Hook 接口是全局影响最大的设计决策，需要架构评审
3. **阶段 3 可以和阶段 2 并行**：如果人力允许，图引擎移植和 Agent 层可以由不同开发者同时推进
4. **阶段 4 优先级最低**：agent_evolving 是增值模块，可以在核心 Agent 框架稳定后再移植
