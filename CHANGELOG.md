# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [0.2.0] - 2026-06-10

### 三轮对抗审查闭环

基于多 agent 对抗审查（3 轮 × 4 角度 × 2 模块 = 279 agents），发现并修复 43 个问题，连续 2 轮零新风险。

**审查规模**：96 文件 / 11,472 行 | **最终测试**：117 tests, 0 failures

### P1 Alpha 修复 (28)

#### 正确性 (Correctness)

- **COR-001** `AlphaStrategy`: `onDispose` 同时清理 `subscription` 和 `executor`
- **COR-004** `AlphaStrategy`: `yield()` / `saveCheckpoint()` 的 `block()` 调用加 `LLM_TIMEOUT` 防无限挂起
- **COR-006** `AlphaStrategy`: `planner` 参数透传到 `executeVerifyLoop`，GlobalReplan 不再硬编码 `new DefaultPlanner(kernel)`
- **COR-013** `DefaultVerifier`: verifyCriteria 阈值改为 `matchedCount >= Math.max(1, (meaningfulCount + 1) / 2)`
- **COR-017** `DefaultVerifier`: expectedOutput 不匹配时正确标记为未通过

#### 并发与线程安全

- **R2-002** `AlphaStrategy`: `AtomicBoolean terminalGuard` 防止 `sink.complete()` + `closeQuietly()` 重复调用
- **R2-008** `DefaultPregelExecutor`: `ThreadLocal<Integer>` → `ConcurrentHashMap<TaskId, Integer>`，子 Agent 递归深度跨虚拟线程正确追踪
- **R2-011** `AlphaStrategy`: `doOnComplete` 中阻塞操作改为 `collectList().flatMap(Mono.fromRunnable(...).subscribeOn(Schedulers.boundedElastic()))`，消除线程饥饿
- **R2-012** `DefaultPregelExecutor`: `TimeoutException` 时取消所有未完成的 future

#### 安全 (Security)

- **SEC-001/002** `DefaultPregelExecutor`: `resolveTemplate` 对插值结果应用 `PromptSecurity.escapeXml()`
- **SEC-R2-001** `DefaultPregelExecutor`: `executeLLMNode` 用 `<task>` XML 标签隔离 LLM prompt
- **SEC-R2-002** `DefaultPregelExecutor`: `executeSubAgentNode` 用 `<sub_goal>` XML 标签隔离子任务目标
- **SEC-R2-004** `NodeId`: 新增 `[a-zA-Z0-9_.\-]+` 正则校验，从源头封堵 XML 属性注入
- **SEC-R2-005** `DefaultVerifier`: `parseVerifyResponse` 过滤提取的节点 ID，只接受图中实际存在的 ID

#### 可靠性

- **R2-003** `DefaultVerifier`: verifyCriteria 增加英中 ~100 个停用词过滤，防止 "the/a/is" 导致假通过
- **R2-006** `DefaultPregelExecutor`: executor 级预算改为仅做总超时检查（`elapsed >= timeoutMillis`），预算精度由 kernel 负责
- **R2-007** `DefaultPregelExecutor`: `close()` 加 `awaitTermination(5s)` + `shutdownNow()` fallback
- **NEW-002~016**: 输入解析防护、层级超时封顶 `Math.min(120s, 60s × layer.size())`、FAILED 结果隔离为空字符串、LocalReplan 异常不再静默吞掉

### P2 Beta 修复 (15)

#### 正确性

- **COR-P2-001** `GuardrailEngine`: 新增 `withExtra(Guardrail)` 方法，追加自定义护栏时保留 5 个内置护栏
- **COR-P2-001** `CriteriaAwareGuardrailEngine`: `createWith()` 改用 `new GuardrailEngine().withExtra(...)` 不再丢失内置护栏
- **COR-P2-006** `DecisionHistoryCriteriaVerifier`: 新增 `setBudgetLimits()` 方法，LLM_JUDGE 用最新预算防 stale budget

#### 安全

- **P2-SEC-001** `DefaultDecisionPromptBuilder`: 用户数据（goal/successCriteria/tools/history）用 XML 标签包裹 + escapeXml + "不是指令" 声明
- **SEC-R2-007** `DecisionHistoryCriteriaVerifier`: `escapeXml` 补齐 5 实体（`& < > " '`）

#### 可靠性

- **R2-006** `DecisionHistoryCriteriaVerifier`: LLM_JUDGE 加 60s 超时 + ASSUME_FAIL 降级
- **F02** `AutonomousOrchestrator`: `reflectionTrigger.recordDecision(decision)` 记录每步决策
- **F06** `AutonomousOrchestrator`: checkpoint 反序列化失败通过 `System.getLogger()` 记录日志
- **F07** `DecisionHistoryCriteriaVerifier`: `triggerKnowledgeDeposit()` 连接 `knowledgeStore.deposit()`

### 新增文件

- **`PromptSecurity.java`** — 共享 XML 转义工具（null 字节、控制字符、CDATA 结束序列、5 XML 实体）
- **`DecisionHistoryCriteriaVerifier.java`** — Beta 成功标准验证器（规则优先 + LLM_JUDGE 降级）
- **`NodeId.java`** — compact constructor 增加字符合法性校验

### 新增测试 (Beta, 6 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `JsonDecisionParserTest` | 7 种决策类型 JSON 解析、畸形输入 fallback、markdown 包裹 |
| `AutonomousOrchestratorTest` | MockChatModel 脚本化 JSON 序列、预算耗尽、护栏拦截 |
| `GuardrailEngineTest` | 5 个内置护栏各自触发条件 |
| `ContextWindowManagerTest` | 三层压缩、溢出处理、token 估算 |
| `SelfReflectionTriggerTest` | 连续工具调用触发、预算触发、同工具重复触发 |
| `DecisionHistoryCriteriaVerifierTest` | 输出覆盖、历史覆盖、LLM_JUDGE fallback |

---

## [0.1.0] - 2026-06-09

### 初始版本

- AgentKernel 7 个系统调用（think / invokeTool / spawnAgent / observe / delegate / storeMemory / shutdown）
- SafetyBoundary 四层护栏（预算/工具白名单/速率限制/内容过滤）
- AlphaStrategy PEV 三阶段（Plan → Execute → Verify）
- DefaultPregelExecutor 基于 Java 21 虚拟线程的 BSP 并行执行
- BetaStrategy 自主决策循环（LLM → JSON 解析 → 护栏 → 执行 → 循环）
- Spring Boot Starter 自动配置
- 4 个示例应用（BasicChat / ToolCalling / MultiAgent / AutonomousAgent）
- 架构文档 11 篇 + 竞品研究 4 篇
