# agentscope-java v2 与 openjiuwen-java 最新对比分析

> 2026-06-03 | 基于两仓库最新源码 | 级别：[解读] | 可信度：[最高]
> 上次分析日期：agentscope-java 2026-05-22（v1.0.12）/ openjiuwen-java 2026-04-17（v0.1.7）

---

## 零、一句话结论

**agentscope-java 经历了完整的 v2 重构（757 commit），从研究框架升级为生产级平台。openjiuwen-java 停留在 v0.1.7 未动。openjiuwen 的三个独有资产（Pregel 图引擎、Agent 进化训练、工作流跳转/断点续传）仍然是 agentscope 不具备的，但 v2 在中间件/权限/沙箱/管理平台方面大幅领先，缩小了我们的差异化空间。**

---

## 一、项目状态总览

| 维度 | openjiuwen-java | agentscope-java v2 |
|------|----------------|-------------------|
| **版本** | 0.1.7（冻结） | 2.0.0-SNAPSHOT（活跃开发） |
| **最新提交** | 2026-04-17 | 2026-06-03（今天） |
| **近 30 天 commit** | 0 | 90 |
| **Java 版本** | 21 | **17**（降级兼容） |
| **包名** | com.openjiuwen | io.agentscope |
| **Spring 依赖** | 零 | 核心零 Spring；扩展层 6 个 Spring Boot 4.0.1 starter |
| **主代码** | 1,090 文件 / 125K 行 | 1,543 文件 / 183K 行（+47%） |
| **测试** | ~237 文件 | 613 文件（+159%） |
| **Maven 模块** | 1（单体 JAR） | 6（core + harness + extensions + examples + bom + distribution） |
| **扩展子模块** | 0 | 24 个（A2A/AGUI/RAG×5/Session×2/Skill×2/Studio/Training/...） |

### openjiuwen-java：冻结

所有指标与 4/17 分析完全一致：
- 1,090 文件 / 124,895 行
- LlmEventHandler.java 1,240 行（最大文件）
- Pregel.java 158 行 BSP 引擎
- agent_evolving/ 53 文件 / 8,370 行
- 零 Spring 依赖

**结论：openjiuwen-java 仓库已冻结，不再有新的开发活动。**

### agentscope-java：极其活跃

自上次分析（5/22 v1.0.12）以来：
- 757 个新 commit 合并到 main
- v2_dev 分支已合入 main
- 最新 commit：今天（6/3）
- 无 v2 release tag，仍是 SNAPSHOT

---

## 二、agentscope-java v2 架构重大变化

### 2.1 Hook → Middleware（洋葱模型）

| 维度 | v1 Hook | v2 Middleware |
|------|---------|--------------|
| **模式** | 单方法 `onEvent(HookEvent)` + switch | 5 个类型化方法，洋葱链 |
| **拦截点** | PreCall/PostCall + PreReasoning/PostReasoning + PreActing/PostActing | `onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt` |
| **参数** | 泛型 `HookEvent` | 类型化 `AgentInput` / `ReasoningInput` / `ActingInput` / `ModelCallInput` |
| **链式** | 优先级排序，线性执行 | 注册序构建洋葱，每层包裹下一层 |
| **兼容** | — | `LegacyHookDispatcher` 桥接旧 Hook |
| **状态** | 活跃 | Hook 已 `@Deprecated(forRemoval = true, since = "2.0.0")` |

**14 个内置 Hook 全部转为 Middleware**。

### 2.2 AgentEvent 流式（28 种事件类型）

v1 的粗粒度 `Event`（REASONING / TOOL_RESULT / SUMMARY）已废弃。新 `AgentEvent` 体系：

| 类别 | 事件类型 |
|------|---------|
| Agent 生命周期 | `AGENT_START` / `AGENT_END` |
| 模型调用 | `MODEL_CALL_START` / `MODEL_CALL_END` |
| 文本/思考块 | `*_BLOCK_START` / `*_DELTA` / `*_END` |
| 工具调用 | `TOOL_CALL_START` / `*_DELTA` / `*_END` |
| 工具结果 | `TOOL_RESULT_START` / `*_TEXT_DELTA` / `*_DATA_DELTA` / `*_END` |
| 人机协同 | `REQUIRE_USER_CONFIRM` / `USER_CONFIRM_RESULT` / `REQUEST_STOP` |
| 外部执行 | `REQUIRE_EXTERNAL_EXECUTION` / `EXTERNAL_EXECUTION_RESULT` |
| 限制 | `EXCEED_MAX_ITERS` |

新 API：`ReActAgent.streamEvents(List<Msg>)` 返回 `Flux<AgentEvent>`。

### 2.3 AgentState 统一状态管理

v1 的散乱状态（`memory_messages` + `toolkit_activeGroups` 等散在 Session key 中）被统一为单一 `AgentState` final class：

- 会话字段：`sessionId` / `summary` / `context(List<Msg>)` / `replyId` / `curIter`
- 子上下文：`PermissionContextState` / `ToolContextState` / `TaskContextState` / `PlanModeContextState`
- 完整 JSON 序列化（`toJson()` / `fromJsonString()`）
- Builder 模式 + Jackson `@JsonCreator`
- 向后兼容：`LegacyStateLoader` 从 v1 key 迁移

### 2.4 PermissionEngine（企业级权限 HITL）

全新权限系统：

- **4 种模式**：EXPLORE / ACCEPT_EDITS / BYPASS / DONT_ASK
- 基于规则的评估（`PermissionRule`）
- 工作目录感知的路径检查
- HITL 确认流：`RequireUserConfirmEvent` → `ConfirmResult` 通过 Msg metadata 传回
- 状态机：ASKING → ALLOWED / DENIED

### 2.5 HarnessAgent（生产级 Agent 封装）

新增模块，1,921 行，在 ReActAgent 之上提供：

- **5 种沙箱后端**：Docker / Kubernetes / E2B / Daytona / AgentRun
- 文件系统抽象（本地/远程/沙箱模式）
- 工作区管理（`WorkspaceManager` + `WorkspaceIndex`，SQLite 索引）
- 子 Agent 编排（`DynamicSubagentsMiddleware`）
- 技能管理（`WorkspaceSkillRepository` + `SkillCurator` + `SkillPromoter`）
- Shell 执行工具（AST 安全分析，Tree-sitter）
- 记忆压缩与刷新

### 2.6 Skill Curator Pipeline（技能自学习）

M1-M7 完整生命周期：

```
技能提议 → 安全扫描 → 灰度部署 → 使用追踪 → 提升门控 → 正式发布
```

包含：`SkillCurator` / `SkillPromoter` / `SkillPromotionGate` / `SkillSecurityScanner` / `CanaryFilter` / `AllowListFilter`

### 2.7 AgentscopeAdmin Spring Boot Starter

全新管理平台 starter：

- **AgentRegistry**：自动发现注册的 Agent Bean
- **REST Controller**：Session 管理 + Subagent 任务
- **9 个 Actuator 端点**：Status / Agents / Tools / Models / Commands / Doctor / Usage / Permissions / Drain / Shutdown / Subagents
- **Admin 命令**：`CommandPlane` + `AdminCommand` + `BuiltinCommandRegistrar`
- **Metrics**：`MetricsHook` + `MetricsRecorder`
- **审计**：`AdminAuditLogger` + Spring ApplicationEventPublisher
- **OpenAPI**：自动生成

### 2.8 其他重要变化

| 变化 | 说明 |
|------|------|
| **RuntimeContext** | 新的 per-call 元数据袋，替代 `ToolExecutionContext`，线程安全 ConcurrentMap + 类型化单例层 |
| **Session promoted** | 从 legacy 提升为正式接口，`JsonSession`（文件）+ `InMemorySession` + Redis/MySQL 扩展 |
| **GracefulShutdownManager** | JVM shutdown hook 集成 + 状态保存 + 部分推理策略（DISCARD vs KEEP） |
| **ReActAgent** | 从 ~500 行膨胀到 **3,138 行**，集成 Middleware + Permission + Events + State + Shutdown |
| **模型 Provider** | 5 家：OpenAI / Anthropic(Claude) / Gemini / DashScope(Qwen) / Ollama |
| **MCP** | 原生支持（15 文件） |
| **A2A** | 完整实现（104 文件） |

---

## 三、openjiuwen 独有资产确认

| 资产 | openjiuwen-java | agentscope-java v2 | 确认 |
|------|----------------|-------------------|------|
| **Pregel BSP 图引擎** | ✅ 158 行 | ❌ 不存在 | **openjiuwen 独有** |
| **Agent 进化训练** | ✅ 53 文件/8,370 行 | ❌ 不存在（有 Skill Curator 但不是 Agent 级进化） | **openjiuwen 独有** |
| **工作流跳转/断点续传** | ✅ 有 | ❌ 无（Session 只存 AgentState，无工作流概念） | **openjiuwen 独有** |
| **YAML/XML 工作流定义** | ✅ 有 | ❌ 无（agentscope 没有声明式工作流引擎） | **openjiuwen 独有** |

**关键判断**：Pregel 和 Evolution 在 agentscope v2 中被放弃，不是迁移——它们从未出现在 agentscope 的代码库中。这确认了它们是 openjiuwen 的结构性独有资产。

---

## 四、对 v3 架构设计的影响

### 4.1 需要修正的判断

| v3 原假设 | v2 现实 | 影响级别 | 修正方向 |
|-----------|--------|---------|---------|
| "Hook 系统，8 事件" | agentscope Hook 已废弃，换 Middleware（5 拦截点洋葱链） | **高** | v3 的 AgentLifecycle 应参考 Middleware 洋葱模型，不是 v1 Hook |
| "HITL 是弱项" | agentscope 有完整 PermissionEngine（4 模式 + 规则评估 + HITL 确认流） | **高** | v3 HITL 不能停留在"简单 Hook 拦截"，需要权限规则引擎 |
| "无管理平台" | agentscope 有 Admin starter（9 Actuator + Metrics + 审计 + OpenAPI） | **高** | v3 Studio 需要匹配或差异化 |
| "SkillBox 对标" | agentscope 有 Skill Curator Pipeline（M1-M7 自学习） | **中** | v3 SkillBox 需要更强的差异化（如结合 Evolution） |
| "无沙箱能力" | agentscope 有 5 种沙箱后端 + 文件系统抽象 | **中** | v3 如果定位企业私有化，沙箱是加分项但非必须 |
| "PlanNotebook 是空白" | agentscope 有 PlanNotebook + PlanModeMiddleware + PlanModeContextState | **中** | 不再是差异化点，但 v3 仍可保留（企业场景有用） |

### 4.2 仍然成立的差异化

| v3 差异化 | agentscope v2 状态 | 护城河强度 |
|-----------|-------------------|-----------|
| **Agent Evolution（LLM-as-Judge + Prompt/Memory/Tool 优化器）** | 无。Skill Curator 是技能级自学习，不是 Agent 级进化 | **强** |
| **Pregel BSP 图引擎** | 无。agentscope 不做图引擎 | **强** |
| **工作流 XML 配置（业务部门无代码修改）** | 无。agentscope 全部代码驱动 | **强** |
| **Token Budget 四层管控** | 无 | **强** |
| **合规审计全链路** | 有审计日志但不面向合规场景 | **中** |
| **私有化部署优先** | DashScope SDK 是核心依赖，有商业冲突 | **强** |
| **Spring AI 为底座（Provider 中立）** | 自建 Provider 适配，DashScope 一等公民 | **强** |

### 4.3 v3 架构具体修正建议

#### 修正 1：AgentLifecycle → Middleware 洋葱模型

v3 原设计（8 事件线性 Hook）应参考 agentscope v2 的 Middleware 洋葱链。建议：

```java
// v3 修正后：保留 8 事件语义，但用洋葱链实现
public interface AgentMiddleware {
    // 洋葱模型：每个 middleware 包裹 next
    AgentResult onInvoke(AgentContext ctx, AgentInput input, Next next);
    String onReasoning(AgentContext ctx, ReasoningInput input, Next next);
    Object onActing(AgentContext ctx, ActingInput input, Next next);
    
    // 简化版（向后兼容）：default 方法自动桥接到洋葱链
    default void onBeforeInvoke(AgentContext ctx) {}  // 保留旧接口
    default void onAfterInvoke(AgentContext ctx, AgentResult result) {}
    // ...
}
```

**理由**：洋葱模型比线性 Hook 更灵活（可以修改输入和输出、短路执行、异常恢复），且与 agentscope v2 对齐降低学习成本。

#### 修正 2：HITL 增加权限规则引擎

v3 原设计（简单 Hook 拦截）不够。建议增加：

```java
public interface PermissionEngine {
    PermissionResult evaluate(PermissionContext ctx);
}

public enum PermissionBehavior {
    ALLOW, DENY, ASK_USER  // 与 agentscope v2 对齐
}
```

#### 修正 3：Studio 功能对齐

v3 Studio 需要补齐 agentscope Admin 已有的能力：

| 能力 | agentscope Admin | v3 Studio 原计划 | 修正 |
|------|-----------------|-----------------|------|
| Agent 注册/发现 | ✅ AgentRegistry | ✅ AgentDiscoveryService | 已对齐 |
| 执行追踪 | ✅ MetricsHook | ✅ ExecutionTraceService | 已对齐 |
| Metrics/指标 | ✅ Actuator 9 端点 | ❌ 缺失 | **需补齐** |
| Admin 命令 | ✅ CommandPlane | ❌ 缺失 | Phase 5+ 可选 |
| 审计日志 | ✅ AdminAuditLogger | ✅ AuditQueryService | 已对齐 |
| OpenAPI 文档 | ✅ 自动生成 | ❌ 缺失 | **需补齐** |

#### 修正 4：ReActAgent 拆分策略

agentscope v2 的 ReActAgent 已膨胀到 **3,138 行**——这是我们要避免的反面教材。v3 的拆分策略（ReActLoop ~300 行 + ReasoningPhase + ActingPhase + SummaryPhase）仍然正确且更优。

---

## 五、agentscope v2 的教训与取舍

### 值得学习的

| 做法 | 评价 |
|------|------|
| **Middleware 洋葱模型** | 比 Hook 更灵活，是正确的架构演进 |
| **AgentState 统一** | 解决了 v1 状态散乱的问题 |
| **28 种细粒度 AgentEvent** | 为 Studio/AGUI 提供了丰富的数据源 |
| **PermissionEngine** | 企业级权限，值得参考 |
| **核心零 Spring** | 架构分层清晰 |
| **Java 17 而非 21** | 更广泛的兼容性（企业环境很多还在 17） |

### 我们要避免的

| 做法 | 问题 |
|------|------|
| **ReActAgent 3,138 行** | 上帝类反模式，违反 SRP |
| **DashScope SDK 核心依赖** | Provider 不中立 |
| **Skill Curator 但无 Agent Evolution** | 技能级学习 ≠ Agent 级进化，有本质差距 |
| **无图引擎/工作流引擎** | 缺少声明式编排能力 |

---

## 六、v3 架构文档待更新项

| 位置 | 更新内容 | 优先级 |
|------|---------|--------|
| § 1.3 四框架能力矩阵 | agentscope 列更新为 v2 数据（Middleware/Permission/Admin/Event/Harness） | 高 |
| § 4 Agent 接口设计 | AgentLifecycle 增加 Middleware 洋葱链选项 | 高 |
| § 5.2 AgentLifecycle | 从线性 8 事件 Hook 修正为洋葱链 + 8 事件语义 | 高 |
| § 6.1 AbstractAgent | fireBefore/fireAfter 模式改为 MiddlewareChain | 高 |
| § 8 Studio | 补齐 Metrics 端点 + OpenAPI 自动生成 | 中 |
| § 10 Python 习气消除 | 新增："避免 ReActAgent 3138 行反模式，保持 <400 行" | 低 |
| § 12 人力估算 | HITL 复杂度增加（PermissionEngine），Phase 3 可能 +1 周 | 中 |
| § 13 风险分析 | 新增："agentscope v2 的 Middleware/Permission 快速成熟，缩小差异化窗口" | 高 |
| § 15 差异化护城河 | 更新为 v2 对比后的新版 | 高 |

---

## 七、战略判断

### 差异化空间的变化

```
v3 设计时的差异化（基于 v1 分析）：
  Agent Evolution ████████████ 强
  Pregel Graph    ████████████ 强
  工作流 XML      ██████████   中强
  Token Budget    ██████████   中强
  HITL            ████████     中
  Admin/Studio    ██████       弱
  Permission      ██████       弱
  沙箱            ████         无

v3 修正后的差异化（基于 v2 分析）：
  Agent Evolution ████████████ 强（不变，最大杀手锏）
  Pregel Graph    ████████████ 强（不变）
  工作流 XML      ████████████ 强（不变）
  Token Budget    ████████████ 强（不变）
  私有化优先      ████████████ 强（DashScope 商业冲突）
  Spring AI 底座  ██████████   中强（Provider 中立）
  合规审计        ████████     中（agentscope 有审计但不面向合规）
  HITL            ██████       弱（agentscope PermissionEngine 已很强）
  Admin/Studio    ██████       弱（agentscope Admin 已有）
```

### 核心判断

**差异化空间从 6 项缩小到 5 项，但最强的 3 项（Evolution + Pregel + 工作流 XML + 私有化）不受影响。**

agentscope v2 在基础设施层（Middleware / Permission / Admin / Sandbox）大幅进步，但在 Agent 智能层（进化训练、图编排、声明式工作流）没有突破。openjiuwen 的定位应该更聚焦于**Agent 智能层**，而不是跟 agentscope 在基础设施层竞争。

---

_最后更新：2026-06-03_
