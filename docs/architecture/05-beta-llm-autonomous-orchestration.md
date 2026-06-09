# Openjiuwen-Java Runtime 帕累托变体 Beta：LLM 自主编排路径

> 2026-06-07 | 帕累托变体设计 | Beta 路径
> 核心假设：LLM 是 Runtime 的大脑，Runtime 的职责是为 LLM 提供最佳的自主决策环境
> 前置：变体 Alpha（开发者显式控制）= v3 架构的 PlanExecuteVerifyStrategy
> 方法：第一性原理 + 分叉探索 + 博弈收敛 + 苏格拉底诘问

---

## 零、变体 Beta 的核心定位

```
Alpha（开发者显式控制）：
  开发者定义 TaskGraph 的节点类型（TOOL_CALL / LLM_CALL / SUB_AGENT）
  Runtime 按拓扑排序机械执行
  LLM 只在 Planner 和 Verifier 两个"许可的入口"介入

Beta（LLM 自主编排）：
  开发者定义目标和约束（Goal + Guardrails）
  LLM 在约束空间内自主决定：拆什么、怎么拆、用什么工具、是否需要子 Agent、是否 replan
  Runtime 是 LLM 的"执行沙箱"——提供工具、记忆、检查点，但不替 LLM 做决策
```

**一句话**：Alpha 是"开发者编排，LLM 执行"；Beta 是"开发者设约束，LLM 编排并执行"。

---

## 一、核心架构骨架

### 1.1 设计哲学的五个支柱

| # | 支柱 | 含义 | 与 Alpha 的差异 |
|---|------|------|----------------|
| B1 | **Goal-Centric** | 开发者定义目标，不是定义路径 | Alpha 定义 TaskGraph，Beta 只定义 Goal |
| B2 | **Constrained Autonomy** | LLM 自主但受限——Guardrails 是不可逾越的边界 | Alpha 的约束隐含在 TaskGraph 结构中 |
| B3 | **Self-Reflection Loop** | LLM 自我评估、自我纠错，不需要外部 Verifier | Alpha 的 Verifier 是独立的 LLM 调用 |
| B4 | **Emergent Structure** | 任务结构从 LLM 推理中涌现，不由开发者预设 | Alpha 的 TaskGraph 是预设的 DAG |
| B5 | **Audit Trail as First-Class** | 每一步 LLM 决策都记录推理链，满足企业合规 | Alpha 的审计是事件日志，Beta 是决策推理链 |

### 1.2 架构总图

```
┌───────────────────────────────────────────────────────────────────────────┐
│                    Beta Runtime: LLM-Autonomous Architecture              │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                     AutonomousOrchestrator                          │  │
│  │                     (LLM 是控制中心)                                  │  │
│  │                                                                     │  │
│  │   ┌──────────────┐    ┌──────────────┐    ┌──────────────────┐     │  │
│  │   │   GoalSpec   │ →  │  Guardrails  │ →  │  DecisionLoop    │     │  │
│  │   │  (开发者定义)  │    │  (安全护栏)   │    │  (LLM 自主循环)   │     │  │
│  │   └──────────────┘    └──────────────┘    └────────┬─────────┘     │  │
│  │                                                    │               │  │
│  │              ┌──────────┬──────────┬────────────────┤               │  │
│  │              │          │          │                │               │  │
│  │         ┌────▼───┐ ┌───▼────┐ ┌───▼──────┐ ┌──────▼───────┐      │  │
│  │         │ Think  │ │  Act   │ │ Reflect  │ │  Replan      │      │  │
│  │         │(推理)   │ │(行动)  │ │(反思)    │ │(重规划)       │      │  │
│  │         └────────┘ └───┬────┘ └──────────┘ └──────────────┘      │  │
│  │                       │                                           │  │
│  │              ┌────────┼────────┐                                  │  │
│  │              │        │        │                                  │  │
│  │         ┌────▼──┐ ┌──▼───┐ ┌──▼───────┐                         │  │
│  │         │ Tool  │ │ Spawn│ │ Delegate │                         │  │
│  │         │ Call  │ │ Sub  │ │ (A2A)    │                         │  │
│  │         └───────┘ │Agent │ └──────────┘                         │  │
│  │                   └──────┘                                       │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                     Guardrail Engine (不可逾越)                      │  │
│  │   ToolWhitelist │ BudgetGuard │ DataAccessControl │ MaxDepthPolicy  │  │
│  │   HumanApprovalGate │ OutputSanitizer │ RateLimiter                 │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                     Reasoning Trail (审计链)                         │  │
│  │   DecisionRecord │ ThoughtChain │ ActionLog │ ReflectionNote        │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐ ┌──────────────┐   │  │
│  │ChatModel │ │MemoryHub │ │Checkpoint│ │  VFS   │ │ MCP Client   │   │  │
│  │(SpringAI)│ │(多槽位)   │ │  Store   │ │        │ │(调远程工具)   │   │  │
│  └──────────┘ └──────────┘ └──────────┘ └────────┘ └──────────────┘   │  │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │  REST API  /api/v1/agents/{name}/invoke | stream | submit          │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Core 层：新增的抽象

Beta 在 Core 层新增以下类型，与 Alpha 共享底层基础设施（MemoryStore / CheckpointStore / VirtualFileSystem / AgentEvent），但替换了编排模型。

```java
package com.openjiuwen.java.core.beta;

// ============================================================
// 1. GoalSpec：开发者定义的目标规范（替代 Alpha 的 TaskGraph）
// ============================================================

/**
 * 目标规范。开发者定义"要达成什么"和"不可逾越的边界"。
 * LLM 在这个规范内自主决定执行路径。
 */
public record GoalSpec(
    String goal,                          // 自然语言目标
    SuccessCriteria successCriteria,      // 成功标准（可验证的）
    Set<Constraint> constraints,          // 硬约束（不可违反）
    Set<String> allowedTools,             // 允许使用的工具（白名单）
    Set<String> forbiddenTools,           // 禁止使用的工具（黑名单）
    Budget budget,                        // 资源预算
    int maxDepth,                         // 最大子Agent递归深度
    int maxReplanCount,                   // 最大重规划次数
    Duration timeout                      // 超时时间
) {}

/**
 * 成功标准。LLM 用这个自我评估。
 */
public sealed interface SuccessCriteria
    permits TextualCriteria, StructuredCriteria, HumanApprovalCriteria {

    String description();
}

/**
 * 文本性成功标准——LLM 自我判断。
 */
public record TextualCriteria(String description) implements SuccessCriteria {}

/**
 * 结构化成功标准——可程序化验证。
 */
public record StructuredCriteria(
    String description,
    String assertionScript                  // JS/Groovy 脚本，输入结果，输出 boolean
) implements SuccessCriteria {}

/**
 * 需要人工确认。
 */
public record HumanApprovalCriteria(
    String description,
    String approvalPrompt                  // 给审批人看的提示
) implements SuccessCriteria {}

/**
 * 约束。不可违反的边界。
 */
public sealed interface Constraint
    permits ToolConstraint, DataConstraint, TimeConstraint, BudgetConstraint {

    String description();
    ConstraintSeverity severity();           // BLOCK（阻止执行）/ WARN（警告但允许）/ ESCALATE（升级到人工）
}

public record ToolConstraint(
    String description,
    ConstraintSeverity severity,
    Set<String> blockedTools,
    Set<String> requireApprovalTools        // 这些工具需要人工批准
) implements Constraint {}

public record DataConstraint(
    String description,
    ConstraintSeverity severity,
    Set<String> blockedDataPatterns,         // 正则：如 "DELETE.*FROM|DROP.*TABLE"
    Set<String> sensitiveDataFields          // 敏感字段：如 "password", "creditCard"
) implements Constraint {}

public record TimeConstraint(
    String description,
    ConstraintSeverity severity,
    Duration maxDuration,
    int maxIterations
) implements Constraint {}

public record BudgetConstraint(
    String description,
    ConstraintSeverity severity,
    int maxTokens,
    int maxToolCalls,
    int maxSubAgents
) implements Constraint {}

public enum ConstraintSeverity { BLOCK, WARN, ESCALATE }

/**
 * 资源预算。
 */
public record Budget(
    int maxTokens,                          // 总 Token 预算
    int maxToolCalls,                       // 最大工具调用次数
    int maxSubAgents,                       // 最大子 Agent 数量
    int maxLLMCalls                         // 最大 LLM 调用次数
) {}
```

```java
// ============================================================
// 2. LLMDecision：LLM 的每一步决策
// ============================================================

/**
 * LLM 的决策。每一次 Think-Act-Reflect 循环产生一个 Decision。
 * 这是 Beta 与 Alpha 的根本差异：Alpha 的决策是结构化的（TaskGraph），
 * Beta 的决策是自由格式的（LLM 自主产出）。
 */
public sealed interface LLMDecision
    permits ToolCallDecision, SpawnSubAgentDecision,
            ReflectDecision, ReplanDecision,
            FinalAnswerDecision, EscalateDecision {

    String reasoning();                     // LLM 的推理过程（审计必需）
    Instant timestamp();
}

/**
 * LLM 决定调用工具。
 */
public record ToolCallDecision(
    String reasoning,
    Instant timestamp,
    String toolName,
    Map<String, Object> arguments
) implements LLMDecision {}

/**
 * LLM 决定生成子 Agent。
 */
public record SpawnSubAgentDecision(
    String reasoning,
    Instant timestamp,
    String subGoal,                         // 子 Agent 的目标
    Set<String> allowedTools,               // 子 Agent 允许的工具
    GoalSpec subGoalSpec                    // 子 Agent 的 GoalSpec（继承+收窄约束）
) implements LLMDecision {}

/**
 * LLM 决定反思——自我评估当前进展。
 */
public record ReflectDecision(
    String reasoning,
    Instant timestamp,
    String assessment,                      // 对当前进展的评估
    double confidence,                      // 置信度 0.0-1.0
    List<String> gaps                       // 识别到的信息缺口
) implements LLMDecision {}

/**
 * LLM 决定重规划——推翻之前的路径。
 */
public record ReplanDecision(
    String reasoning,
    Instant timestamp,
    String replanReason,                    // 为什么重规划
    String newApproach                      // 新的执行策略描述
) implements LLMDecision {}

/**
 * LLM 认为可以给出最终答案。
 */
public record FinalAnswerDecision(
    String reasoning,
    Instant timestamp,
    String answer
) implements LLMDecision {}

/**
 * LLM 决定升级——需要人工介入。
 */
public record EscalateDecision(
    String reasoning,
    Instant timestamp,
    EscalationReason reason,
    String message
) implements LLMDecision {}

public enum EscalationReason {
    INSUFFICIENT_INFORMATION,               // 信息不足
    CONSTRAINT_AMBIGUITY,                   // 约束不清晰
    SAFETY_CONCERN,                         // 安全顾虑
    BUDGET_EXCEEDED,                        // 预算超限
    GOAL_AMBIGUITY                          // 目标不清晰
}
```

```java
// ============================================================
// 3. ReasoningTrail：审计推理链
// ============================================================

/**
 * 推理链。记录 LLM 的每一个决策及其推理过程。
 * 企业合规的核心：不是"做了什么"，而是"为什么这样做"。
 */
public record ReasoningTrail(
    String taskId,
    String goal,
    List<DecisionRecord> decisions,
    List<GuardrailEvent> guardrailEvents,
    Instant startTime,
    Instant endTime
) {}

/**
 * 单条决策记录。
 */
public record DecisionRecord(
    int stepIndex,
    LLMDecision decision,
    GuardrailCheck guardrailCheck,          // 决策前的护栏检查结果
    Object executionResult,                 // 执行结果（可能为 null）
    Duration stepDuration
) {}

/**
 * 护栏检查结果。
 */
public record GuardrailCheck(
    boolean approved,
    List<String> violations,                // 违反的约束列表
    ConstraintSeverity maxSeverity          // 最严重的违规级别
) {}

/**
 * 护栏事件——LLM 的决策被护栏拦截时记录。
 */
public record GuardrailEvent(
    int stepIndex,
    LLMDecision originalDecision,
    String blockedReason,
    LLMDecision correctedDecision           // 护栏修正后的决策（可能为 null = 直接阻止）
) {}
```

```java
// ============================================================
// 4. BetaTaskContext：Beta 变体的任务上下文
// ============================================================

/**
 * Beta 变体的任务上下文。
 * 与 Alpha 的 TaskContext 相比，增加了 GoalSpec 和 DecisionHistory。
 */
public record BetaTaskContext(
    String taskId,
    String agentName,
    String userInput,
    String systemPrompt,
    String model,
    GoalSpec goalSpec,                       // Beta 核心：目标规范
    List<ToolDefinition> tools,
    List<LLMDecision> decisionHistory,       // 已做的决策（LLM 每次推理时看到）
    Map<String, Object> workingMemory,       // 工作记忆（工具结果、中间结论等）
    MemoryStore sessionMemory,
    CheckpointStore checkpoints,
    VirtualFileSystem vfs,
    Budget consumedBudget                    // 已消耗的预算
) {
    /**
     * 追加一个决策到历史。
     * 返回新的 context（不可变设计）。
     */
    public BetaTaskContext withDecision(LLMDecision decision) {
        var newHistory = new ArrayList<>(decisionHistory);
        newHistory.add(decision);
        return new BetaTaskContext(
            taskId, agentName, userInput, systemPrompt, model,
            goalSpec, tools, Collections.unmodifiableList(newHistory),
            workingMemory, sessionMemory, checkpoints, vfs,
            consumedBudget
        );
    }

    /**
     * 更新工作记忆。
     */
    public BetaTaskContext withWorkingMemory(String key, Object value) {
        var newMemory = new LinkedHashMap<>(workingMemory);
        newMemory.put(key, value);
        return new BetaTaskContext(
            taskId, agentName, userInput, systemPrompt, model,
            goalSpec, tools, decisionHistory,
            Collections.unmodifiableMap(newMemory),
            sessionMemory, checkpoints, vfs, consumedBudget
        );
    }

    /**
     * 更新已消耗预算。
     */
    public BetaTaskContext withConsumedBudget(Budget additional) {
        return new BetaTaskContext(
            taskId, agentName, userInput, systemPrompt, model,
            goalSpec, tools, decisionHistory, workingMemory,
            sessionMemory, checkpoints, vfs,
            new Budget(
                consumedBudget.maxTokens() + additional.maxTokens(),
                consumedBudget.maxToolCalls() + additional.maxToolCalls(),
                consumedBudget.maxSubAgents() + additional.maxSubAgents(),
                consumedBudget.maxLLMCalls() + additional.maxLLMCalls()
            )
        );
    }
}
```

### 1.4 Runtime 层：自主编排核心

```java
package com.openjiuwen.java.runtime.beta;

// ============================================================
// 1. AutonomousOrchestrator：自主编排器（替代 Alpha 的 PlanExecuteVerifyStrategy）
// ============================================================

/**
 * 自主编排器。LLM 是控制中心。
 *
 * 与 Alpha 的根本差异：
 * - Alpha: Planner(LLM) → TaskGraph(结构化) → Executor(机械执行) → Verifier(LLM)
 * - Beta:  LLM 在循环中自主决定每一步：Think → Decide → Act → Reflect → (replan?)
 *
 * Runtime 不替 LLM 做决策。Runtime 的角色是：
 * 1. 把 LLM 的决策翻译成可执行的动作
 * 2. 在执行前检查 Guardrails
 * 3. 记录审计推理链
 * 4. 管理 LLM 的上下文窗口（自动压缩、摘要）
 */
public class AutonomousOrchestrator implements ExecutionStrategy {

    private final ChatModel chatModel;
    private final GuardrailEngine guardrailEngine;
    private final ReasoningTrailRecorder trailRecorder;
    private final SubAgentSpawner subAgentSpawner;
    private final ToolExecutor toolExecutor;
    private final ContextWindowManager contextWindowManager;

    @Override
    public String name() { return "autonomous"; }

    @Override
    public Flux<AgentEvent> execute(TaskContext rawCtx) {
        BetaTaskContext ctx = (BetaTaskContext) rawCtx;
        GoalSpec goal = ctx.goalSpec();

        return Flux.create(sink -> {
            sink.next(new ThinkingEvent(ctx.taskId(), Instant.now(),
                "开始自主编排，目标：" + goal.goal()));

            // 主循环：Think-Act-Reflect
            int stepCount = 0;
            int replanCount = 0;
            BetaTaskContext currentCtx = ctx;

            while (true) {
                stepCount++;

                // ===== 检查预算 =====
                if (budgetExhausted(currentCtx, goal)) {
                    sink.next(new EscalateEvent(ctx.taskId(), Instant.now(),
                        EscalationReason.BUDGET_EXCEEDED,
                        "预算耗尽：token=" + currentCtx.consumedBudget().maxTokens()
                            + ", toolCalls=" + currentCtx.consumedBudget().maxToolCalls()));
                    sink.next(new ErrorEvent(ctx.taskId(), Instant.now(), "预算耗尽"));
                    sink.complete();
                    return;
                }

                // ===== 检查超时 =====
                if (goal.timeout() != null
                        && Duration.between(currentCtx.sessionStartTime(), Instant.now())
                            .compareTo(goal.timeout()) > 0) {
                    sink.next(new ErrorEvent(ctx.taskId(), Instant.now(), "执行超时"));
                    sink.complete();
                    return;
                }

                // ===== 管理上下文窗口 =====
                String contextForLLM = contextWindowManager.buildContext(
                    currentCtx, goal, stepCount);

                // ===== LLM 决策 =====
                LLMDecision decision = callLLMForDecision(contextForLLM, currentCtx);

                sink.next(new DecisionEvent(ctx.taskId(), Instant.now(),
                    decision, stepCount));

                // ===== 护栏检查 =====
                GuardrailCheck check = guardrailEngine.evaluate(decision, goal);

                if (!check.approved()) {
                    // 护栏拦截
                    trailRecorder.recordGuardrailBlock(
                        currentCtx.taskId(), stepCount, decision, check);

                    if (check.maxSeverity() == ConstraintSeverity.BLOCK) {
                        // 硬拦截 → 告诉 LLM 换一条路
                        String feedback = "你的决策被阻止了。原因："
                            + String.join("; ", check.violations())
                            + "。请换一种方式达成目标。";
                        currentCtx = currentCtx.withDecision(
                            new ReflectDecision(feedback, Instant.now(),
                                "被护栏阻止，需要调整策略", 0.0, List.of()));
                        sink.next(new GuardrailEvent(ctx.taskId(), Instant.now(),
                            decision, check.violations()));
                        continue;
                    }

                    if (check.maxSeverity() == ConstraintSeverity.ESCALATE) {
                        // 需要人工审批
                        sink.next(new PauseEvent(ctx.taskId(), Instant.now(),
                            PauseReason.WAITING_FOR_HUMAN_APPROVAL,
                            "决策需要人工审批：" + decision.reasoning()));
                        // 暂停，等待外部恢复
                        sink.complete();
                        return;
                    }
                    // WARN → 允许但记录
                }

                // ===== 执行决策 =====
                currentCtx = executeDecision(decision, currentCtx, sink);

                // ===== 记录审计链 =====
                trailRecorder.recordDecision(currentCtx.taskId(), stepCount,
                    decision, check, currentCtx.workingMemory());

                // ===== 检查终止条件 =====
                if (decision instanceof FinalAnswerDecision(String reasoning, _, String answer)) {
                    sink.next(new CompleteEvent(ctx.taskId(), Instant.now(),
                        buildFinalResult(currentCtx, answer)));
                    sink.complete();
                    return;
                }

                if (decision instanceof EscalateDecision) {
                    sink.next(new PauseEvent(ctx.taskId(), Instant.now(),
                        PauseReason.WAITING_FOR_HUMAN_INPUT,
                        decision.reasoning()));
                    sink.complete();
                    return;
                }

                if (decision instanceof ReplanDecision) {
                    replanCount++;
                    if (replanCount > goal.maxReplanCount()) {
                        sink.next(new ErrorEvent(ctx.taskId(), Instant.now(),
                            "超过最大重规划次数：" + goal.maxReplanCount()));
                        sink.complete();
                        return;
                    }
                    // replan 不中断循环——LLM 已经在新路径上了
                    saveCheckpoint(currentCtx, "REPLAN", stepCount);
                }

                // ===== 定期保存检查点 =====
                if (stepCount % 3 == 0) {
                    saveCheckpoint(currentCtx, "AUTONOMOUS", stepCount);
                }
            }
        });
    }

    /**
     * 调用 LLM 获取下一步决策。
     * 关键：LLM 看到的是完整的决策历史 + 当前工作记忆 + 目标 + 约束。
     */
    private LLMDecision callLLMForDecision(
            String context, BetaTaskContext ctx) {
        String prompt = buildAutonomousPrompt(ctx, context);

        ChatResponse response = chatModel.call(
            new SystemMessage(ctx.systemPrompt()),
            new UserMessage(prompt)
        );

        return parseDecision(response.content());
    }

    /**
     * 构建自主编排的提示词。
     * 这是 Beta 变体最关键的提示词——它把 LLM 置于"驾驶员"位置。
     */
    private String buildAutonomousPrompt(BetaTaskContext ctx, String contextSummary) {
        GoalSpec goal = ctx.goalSpec();

        return """
            你是一个自主决策 Agent。你的目标是：

            ## 目标
            %s

            ## 成功标准
            %s

            ## 约束（不可违反）
            %s

            ## 当前状态
            %s

            ## 你已做的决策历史
            %s

            ## 可用工具
            %s

            ## 预算消耗
            Token: %d / %d
            工具调用: %d / %d
            子Agent: %d / %d

            ---
            请做出你的下一个决策。你必须输出 JSON 格式的决策：

            - 如果要调用工具：{"type": "tool_call", "reasoning": "为什么", "tool": "工具名", "args": {...}}
            - 如果要生成子Agent：{"type": "spawn_sub_agent", "reasoning": "为什么", "sub_goal": "子目标", "allowed_tools": ["tool1", "tool2"]}
            - 如果要反思：{"type": "reflect", "reasoning": "反思内容", "assessment": "进展评估", "confidence": 0.8, "gaps": ["信息缺口"]}
            - 如果要重规划：{"type": "replan", "reasoning": "为什么重规划", "replan_reason": "具体原因", "new_approach": "新策略"}
            - 如果可以给出最终答案：{"type": "final_answer", "reasoning": "为什么认为目标已达成", "answer": "最终答案"}
            - 如果需要人工介入：{"type": "escalate", "reasoning": "为什么需要人工", "reason": "INSUFFICIENT_INFORMATION|SAFETY_CONCERN|...", "message": "给人工的消息"}

            重要：
            1. 每次只做一个决策
            2. reasoning 字段是必须的——解释你的思考过程
            3. 在调用工具前，先思考是否真的需要这个工具
            4. 如果连续3次工具调用都没有推进目标，考虑 replan 或 reflect
            5. 如果你对结果有信心（confidence >= 0.8），考虑 final_answer
            """.formatted(
                goal.goal(),
                goal.successCriteria().description(),
                formatConstraints(goal.constraints()),
                contextSummary,
                formatDecisionHistory(ctx.decisionHistory()),
                formatTools(ctx.tools(), goal.allowedTools(), goal.forbiddenTools()),
                ctx.consumedBudget().maxTokens(), goal.budget().maxTokens(),
                ctx.consumedBudget().maxToolCalls(), goal.budget().maxToolCalls(),
                ctx.consumedBudget().maxSubAgents(), goal.budget().maxSubAgents()
            );
    }

    /**
     * 执行 LLM 的决策。
     */
    private BetaTaskContext executeDecision(
            LLMDecision decision, BetaTaskContext ctx,
            FluxSink<AgentEvent> sink) {

        return switch (decision) {
            case ToolCallDecision(String reasoning, _, String tool, Map<String,Object> args) -> {
                sink.next(new ToolCallEvent(ctx.taskId(), Instant.now(), tool, args));
                Object result = toolExecutor.execute(tool, args);
                sink.next(new ToolResultEvent(ctx.taskId(), Instant.now(), tool, result));
                yield ctx.withDecision(decision)
                         .withWorkingMemory("tool:" + tool + ":" + ctx.decisionHistory().size(), result)
                         .withConsumedBudget(new Budget(0, 1, 0, 1));
            }

            case SpawnSubAgentDecision(String reasoning, _, String subGoal,
                    Set<String> allowed, GoalSpec subSpec) -> {
                sink.next(new SubAgentSpawnEvent(ctx.taskId(), Instant.now(), subGoal));
                AgentResult subResult = subAgentSpawner.spawn(ctx.taskId(), subGoal, allowed, subSpec);
                sink.next(new SubAgentCompleteEvent(ctx.taskId(), Instant.now(),
                    subGoal, subResult.output()));
                yield ctx.withDecision(decision)
                         .withWorkingMemory("subagent:" + subGoal, subResult.output())
                         .withConsumedBudget(new Budget(0, 0, 1, 0));
            }

            case ReflectDecision(String reasoning, _, String assessment,
                    double confidence, List<String> gaps) -> {
                // 反思不执行任何动作，只是更新决策历史
                // LLM 在下一步推理时会看到这次反思
                sink.next(new ReflectEvent(ctx.taskId(), Instant.now(),
                    assessment, confidence, gaps));
                yield ctx.withDecision(decision);
            }

            case ReplanDecision(String reasoning, _, String replanReason, String newApproach) -> {
                // 重规划不中断循环——LLM 已经在新路径上了
                // 把重规划信息写入 VFS 供后续参考
                ctx.vfs().write("replan/" + ctx.decisionHistory().size() + ".md",
                    "# Replan\n原因：" + replanReason + "\n新策略：" + newApproach).subscribe();
                sink.next(new ReplanEvent(ctx.taskId(), Instant.now(),
                    replanReason, newApproach));
                yield ctx.withDecision(decision);
            }

            case FinalAnswerDecision(String reasoning, _, String answer) -> {
                yield ctx.withDecision(decision);
            }

            case EscalateDecision(String reasoning, _, EscalationReason reason, String message) -> {
                yield ctx.withDecision(decision);
            }
        };
    }
}
```

### 1.5 GuardrailEngine：安全护栏

```java
package com.openjiuwen.java.runtime.beta;

// ============================================================
// GuardrailEngine：安全护栏（不可逾越的边界）
// ============================================================

/**
 * 安全护栏引擎。
 *
 * 设计原则：
 * 1. 护栏是"代码级"的，不是"提示词级"的——LLM 无法绕过
 * 2. 每一个 LLM 决策在执行前都要经过护栏检查
 * 3. 护栏的判定是确定性的——不依赖 LLM
 * 4. 护栏拦截产生审计记录
 */
public class GuardrailEngine {

    private final List<Guardrail> guardrails;

    public GuardrailEngine(List<Guardrail> guardrails) {
        // 按 priority 排序——高优先级的先检查
        this.guardrails = guardrails.stream()
            .sorted(Comparator.comparingInt(Guardrail::priority).reversed())
            .toList();
    }

    /**
     * 评估 LLM 决策是否通过护栏。
     * 返回检查结果，不做副作用。
     */
    public GuardrailCheck evaluate(LLMDecision decision, GoalSpec goal) {
        List<String> violations = new ArrayList<>();
        ConstraintSeverity maxSeverity = null;

        for (Guardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.check(decision, goal);
            if (!result.passed()) {
                violations.add(result.violation());
                if (maxSeverity == null
                        || result.severity().ordinal() > maxSeverity.ordinal()) {
                    maxSeverity = result.severity();
                }
                if (result.severity() == ConstraintSeverity.BLOCK) {
                    // 遇到 BLOCK 级别的违规，立即返回
                    break;
                }
            }
        }

        return new GuardrailCheck(
            violations.isEmpty(),
            violations,
            maxSeverity
        );
    }
}

/**
 * 单个护栏。
 */
public interface Guardrail {

    /** 护栏名称 */
    String name();

    /** 优先级（越高越先检查） */
    int priority();

    /** 检查决策 */
    GuardrailResult check(LLMDecision decision, GoalSpec goal);
}

public record GuardrailResult(
    boolean passed,
    String violation,
    ConstraintSeverity severity
) {}

// ============================================================
// 内置护栏实现
// ============================================================

/**
 * 工具白名单护栏。
 */
public final class ToolWhitelistGuardrail implements Guardrail {

    @Override public String name() { return "tool-whitelist"; }
    @Override public int priority() { return 100; }

    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        if (decision instanceof ToolCallDecision(_, _, String tool, _)) {
            if (!goal.allowedTools().isEmpty()
                    && !goal.allowedTools().contains(tool)
                    && !goal.allowedTools().contains("*")) {
                return new GuardrailResult(false,
                    "工具 '" + tool + "' 不在白名单中", ConstraintSeverity.BLOCK);
            }
            if (goal.forbiddenTools().contains(tool)) {
                return new GuardrailResult(false,
                    "工具 '" + tool + "' 在黑名单中", ConstraintSeverity.BLOCK);
            }
        }
        return new GuardrailResult(true, null, null);
    }
}

/**
 * 危险操作检测护栏。
 * 检测工具调用参数中是否包含危险模式。
 */
public final class DangerousOperationGuardrail implements Guardrail {

    private static final Set<String> DANGEROUS_PATTERNS = Set.of(
        "DELETE.*FROM", "DROP.*TABLE", "TRUNCATE",
        "rm\\s+-rf", "format", "shutdown",
        "DELETE", "DROP", "TRUNCATE", "ALTER.*USER"
    );

    @Override public String name() { return "dangerous-operation"; }
    @Override public int priority() { return 200; }

    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        if (decision instanceof ToolCallDecision(_, _, String tool, Map<String,Object> args)) {
            String argsStr = args.values().stream()
                .map(Object::toString)
                .collect(Collectors.joining(" "));

            for (String pattern : DANGEROUS_PATTERNS) {
                if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
                        .matcher(argsStr).find()) {
                    return new GuardrailResult(false,
                        "检测到危险操作模式：" + pattern, ConstraintSeverity.BLOCK);
                }
            }

            // 检查数据约束
            for (Constraint c : goal.constraints()) {
                if (c instanceof DataConstraint(_, _, Set<String> blocked, _)) {
                    for (String bp : blocked) {
                        if (Pattern.compile(bp, Pattern.CASE_INSENSITIVE)
                                .matcher(argsStr).find()) {
                            return new GuardrailResult(false,
                                "违反数据约束：" + bp, ConstraintSeverity.BLOCK);
                        }
                    }
                }
            }
        }
        return new GuardrailResult(true, null, null);
    }
}

/**
 * 敏感工具人工审批护栏。
 */
public final class SensitiveToolApprovalGuardrail implements Guardrail {

    private final Set<String> sensitiveTools;
    private final HumanApprovalGate approvalGate;

    public SensitiveToolApprovalGuardrail(
            Set<String> sensitiveTools,
            HumanApprovalGate approvalGate) {
        this.sensitiveTools = sensitiveTools;
        this.approvalGate = approvalGate;
    }

    @Override public String name() { return "sensitive-tool-approval"; }
    @Override public int priority() { return 150; }

    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        if (decision instanceof ToolCallDecision(_, _, String tool, _)) {
            if (sensitiveTools.contains(tool)) {
                boolean approved = approvalGate.requestApproval(
                    "Agent 请求使用敏感工具：" + tool);
                if (!approved) {
                    return new GuardrailResult(false,
                        "敏感工具 '" + tool + "' 被人工拒绝",
                        ConstraintSeverity.BLOCK);
                }
            }

            // 检查 GoalSpec 中需要审批的工具
            for (Constraint c : goal.constraints()) {
                if (c instanceof ToolConstraint(_, _, _, Set<String> approvalTools)) {
                    if (approvalTools.contains(tool)) {
                        return new GuardrailResult(false,
                            "工具 '" + tool + "' 需要人工审批",
                            ConstraintSeverity.ESCALATE);
                    }
                }
            }
        }
        return new GuardrailResult(true, null, null);
    }
}

/**
 * 预算耗尽护栏。
 */
public final class BudgetExhaustionGuardrail implements Guardrail {

    @Override public String name() { return "budget-exhaustion"; }
    @Override public int priority() { return 300; }

    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        if (decision instanceof ToolCallDecision && goal.budget() != null) {
            // Budget 检查在 Orchestrator 主循环中已经做了
            // 这里做的是：工具调用的单次成本预估
        }
        if (decision instanceof SpawnSubAgentDecision) {
            // 子Agent 生成前的预算检查
        }
        return new GuardrailResult(true, null, null);
    }
}

/**
 * 递归深度护栏。
 */
public final class MaxDepthGuardrail implements Guardrail {

    private final int currentDepth;

    public MaxDepthGuardrail(int currentDepth) {
        this.currentDepth = currentDepth;
    }

    @Override public String name() { return "max-depth"; }
    @Override public int priority() { return 250; }

    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        if (decision instanceof SpawnSubAgentDecision) {
            if (currentDepth >= goal.maxDepth()) {
                return new GuardrailResult(false,
                    "已达到最大子Agent深度：" + goal.maxDepth(),
                    ConstraintSeverity.BLOCK);
            }
        }
        return new GuardrailResult(true, null, null);
    }
}

/**
 * 人工审批门。
 * 与护栏分离——因为审批可能涉及外部系统（邮件、审批流、即时消息）。
 */
public interface HumanApprovalGate {

    /**
     * 请求人工审批。
     * 同步阻塞直到获得审批结果。
     * 实现可以对接企业审批流系统。
     */
    boolean requestApproval(String requestDescription);

    /**
     * 异步审批。
     */
    CompletableFuture<Boolean> requestApprovalAsync(String requestDescription);
}
```

### 1.6 ContextWindowManager：上下文窗口管理

```java
package com.openjiuwen.java.runtime.beta;

// ============================================================
// ContextWindowManager：管理 LLM 的上下文窗口
// ============================================================

/**
 * 上下文窗口管理器。
 *
 * Beta 变体的 LLM 需要看到"所有决策历史"，但上下文窗口有限。
 * 这个管理器负责：
 * 1. 摘要过长的决策历史
 * 2. 保留关键的决策（工具调用结果、反思结论）
 * 3. 压缩冗余信息
 *
 * 设计原则：
 * - 近期决策完整保留（最近 N 条）
 * - 历史决策摘要保留（只保留 reasoning + 结论）
 * - 工具调用结果按重要性压缩
 * - 反思结论完整保留
 */
public class ContextWindowManager {

    private final ChatModel chatModel;
    private final int maxFullHistoryItems;      // 完整保留的最近决策数
    private final int maxContextTokens;         // 上下文窗口 Token 上限

    /**
     * 构建给 LLM 的上下文字符串。
     */
    public String buildContext(
            BetaTaskContext ctx, GoalSpec goal, int currentStep) {

        StringBuilder sb = new StringBuilder();

        // 1. 工作记忆（最重要的——工具结果、中间结论）
        sb.append("## 工作记忆\n");
        ctx.workingMemory().forEach((key, value) ->
            sb.append("- ").append(key).append(": ")
              .append(summarize(value)).append("\n"));

        // 2. 决策历史
        sb.append("\n## 决策历史\n");
        List<LLMDecision> history = ctx.decisionHistory();
        int fullStart = Math.max(0, history.size() - maxFullHistoryItems);

        // 2a. 早期历史 → 摘要
        if (fullStart > 0) {
            String summary = summarizeDecisions(history.subList(0, fullStart));
            sb.append("[早期决策摘要] ").append(summary).append("\n");
        }

        // 2b. 近期历史 → 完整
        for (int i = fullStart; i < history.size(); i++) {
            LLMDecision d = history.get(i);
            sb.append(formatDecision(d, i + 1)).append("\n");
        }

        // 3. VFS 中的关键文件
        sb.append("\n## 工作空间文件\n");
        try {
            ctx.vfs().list("notes/").block()
                .forEach(f -> sb.append("- ").append(f).append("\n"));
        } catch (Exception ignored) {
            // VFS 可能为空
        }

        return sb.toString();
    }

    /**
     * 摘要一组决策。
     * 调用 LLM 生成摘要——确保信息不丢失。
     */
    private String summarizeDecisions(List<LLMDecision> decisions) {
        if (decisions.isEmpty()) return "无早期决策";

        String raw = decisions.stream()
            .map(d -> d.reasoning())
            .collect(Collectors.joining("\n"));

        if (raw.length() < 500) return raw;

        // 超过 500 字符 → 用 LLM 摘要
        String summaryPrompt = "请用2-3句话摘要以下决策历史的关键信息：\n" + raw;
        return chatModel.call(summaryPrompt);
    }

    private String summarize(Object value) {
        String str = value.toString();
        if (str.length() > 500) {
            return str.substring(0, 500) + "...（已截断，完整结果在工作记忆中）";
        }
        return str;
    }
}
```

### 1.7 自我反思与纠错机制

```java
package com.openjiuwen.java.runtime.beta;

// ============================================================
// SelfReflection：LLM 自我反思机制
// ============================================================

/**
 * 自我反思机制。
 *
 * 与 Alpha 的 Verifier 的关键差异：
 * - Alpha：独立的 LLM 调用，在所有执行完成后做一次验证
 * - Beta：LLM 自己在循环中做反思，可以在任何时候触发
 *
 * 触发条件：
 * 1. LLM 主动决策 Reflect
 * 2. 连续3次工具调用没有推进目标（自动触发）
 * 3. LLM 的 confidence 低于阈值（自动触发）
 */
public class SelfReflectionTrigger {

    private static final int MAX_CONSECUTIVE_TOOL_CALLS = 3;
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.4;

    /**
     * 检查是否应该触发自动反思。
     * 在每次工具调用后调用。
     */
    public boolean shouldTriggerReflection(BetaTaskContext ctx) {
        List<LLMDecision> history = ctx.decisionHistory();

        // 条件 1：连续3次工具调用
        long consecutiveToolCalls = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof ToolCallDecision) {
                consecutiveToolCalls++;
            } else {
                break;
            }
        }
        if (consecutiveToolCalls >= MAX_CONSECUTIVE_TOOL_CALLS) {
            return true;
        }

        // 条件 2：最近一次反思的 confidence 过低
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof ReflectDecision(_, _, _, double conf, _)) {
                return conf < LOW_CONFIDENCE_THRESHOLD;
            }
        }

        return false;
    }

    /**
     * 构建反思提示词（注入到下一次 LLM 调用的上下文中）。
     */
    public String buildReflectionPrompt(BetaTaskContext ctx) {
        return """
            [系统注入] 你已经连续执行了多次操作，但可能没有明显推进目标。
            请停下来反思：
            1. 当前进展如何？
            2. 你的策略是否需要调整？
            3. 是否缺少关键信息？
            4. 是否应该考虑 replan？
            """;
    }
}
```

### 1.8 开发者视角：定义一个 Beta Agent

```java
// ============================================================
// 开发者视角：定义 Beta Agent
// ============================================================

@Agent(
    name = "enterprise-data-analyst",
    description = "企业数据分析助手，自主分析数据、生成报告",
    model = "deepseek-chat",
    strategy = "autonomous"              // 关键区别：autonomous 而不是 deep
)
public class EnterpriseDataAnalystAgent {

    @SystemPrompt
    public String systemPrompt() {
        return """
            你是一个企业数据分析专家。
            你可以自主决定如何分析数据、使用什么工具、生成什么报告。
            你必须在给定的约束内工作。
            每一步决策都要说明你的推理过程。
            """;
    }

    @GoalSpec                              // Beta 新增注解
    public GoalSpec goal() {
        return new GoalSpec(
            "分析销售数据并生成季度报告",
            new StructuredCriteria(
                "报告包含：总销售额、环比增长率、Top 10 产品、异常检测",
                "assert result.includes('总销售额') && result.includes('环比增长')"
            ),
            Set.of(
                new DataConstraint(
                    "不允许修改任何数据",
                    ConstraintSeverity.BLOCK,
                    Set.of("UPDATE.*SET", "INSERT.*INTO", "DELETE.*FROM"),
                    Set.of("password", "api_key")
                ),
                new TimeConstraint(
                    "分析不超过5分钟",
                    ConstraintSeverity.WARN,
                    Duration.ofMinutes(5),
                    50
                )
            ),
            Set.of("query_sales", "query_products", "calculate_growth",
                   "detect_anomalies", "generate_report"),
            Set.of("delete_data", "modify_data", "send_email"),
            new Budget(50000, 30, 3, 100),     // 50k tokens, 30次工具调用, 3个子Agent, 100次LLM调用
            2,                                   // 最大递归深度
            5,                                   // 最大重规划次数
            Duration.ofMinutes(10)               // 超时
        );
    }

    @Tool(description = "查询销售数据")
    public Object querySales(
        @Param(description = "查询条件") String condition
    ) {
        return salesDataRepository.query(condition);
    }

    @Tool(description = "查询产品信息")
    public Object queryProducts(
        @Param(description = "产品ID列表") List<String> productIds
    ) {
        return productRepository.batchQuery(productIds);
    }

    @Tool(description = "计算增长率")
    public Object calculateGrowth(
        @Param(description = "当前周期数据") Object current,
        @Param(description = "上一周期数据") Object previous
    ) {
        return growthCalculator.calculate(current, previous);
    }

    @Tool(description = "异常检测")
    public Object detectAnomalies(
        @Param(description = "数据集") Object dataset
    ) {
        return anomalyDetector.detect(dataset);
    }

    @Tool(description = "生成报告")
    public Object generateReport(
        @Param(description = "报告内容（Markdown）") String markdown
    ) {
        return reportService.generate(markdown);
    }
}
```

### 1.9 Beta 新增的事件类型

```java
// ============================================================
// Beta 新增事件类型（继承 Alpha 的事件体系）
// ============================================================

/**
 * LLM 决策事件。
 */
public record DecisionEvent(
    String taskId,
    Instant timestamp,
    LLMDecision decision,
    int stepIndex
) implements AgentEvent {}

/**
 * 护栏拦截事件。
 */
public record GuardrailEvent(
    String taskId,
    Instant timestamp,
    LLMDecision blockedDecision,
    List<String> violations
) implements AgentEvent {}

/**
 * 自我反思事件。
 */
public record ReflectEvent(
    String taskId,
    Instant timestamp,
    String assessment,
    double confidence,
    List<String> gaps
) implements AgentEvent {}

/**
 * 重规划事件。
 */
public record ReplanEvent(
    String taskId,
    Instant timestamp,
    String reason,
    String newApproach
) implements AgentEvent {}

/**
 * 子Agent 生成事件。
 */
public record SubAgentSpawnEvent(
    String taskId,
    Instant timestamp,
    String subGoal
) implements AgentEvent {}

/**
 * 子Agent 完成事件。
 */
public record SubAgentCompleteEvent(
    String taskId,
    Instant timestamp,
    String subGoal,
    String result
) implements AgentEvent {}

/**
 * 升级（请求人工）事件。
 */
public record EscalateEvent(
    String taskId,
    Instant timestamp,
    EscalationReason reason,
    String message
) implements AgentEvent {}

/**
 * 暂停事件。
 */
public record PauseEvent(
    String taskId,
    Instant timestamp,
    PauseReason reason,
    String message
) implements AgentEvent {}

public enum PauseReason {
    WAITING_FOR_HUMAN_INPUT,
    WAITING_FOR_HUMAN_APPROVAL,
    WAITING_FOR_EXTERNAL_SYSTEM,
    BUDGET_PAUSED
}
```

---

## 二、与变体 Alpha 的关键差异

| 维度 | Alpha（开发者显式控制） | Beta（LLM 自主编排） | 设计权衡 |
|------|----------------------|---------------------|---------|
| **状态管理策略** | TaskGraph 驱动：状态 = DAG 节点的完成状态 | DecisionHistory 驱动：状态 = LLM 决策的累积轨迹 | Alpha 状态可预测可复现；Beta 状态是涌现的，需要 ReasoningTrail 补偿 |
| **工具调用模式** | Planner 预设每个节点用什么工具 | LLM 在运行时自主选择工具 | Alpha 工具调用可审计可预测；Beta 灵活但不可预测 |
| **错误处理策略** | Verifier 检测失败节点 → 局部 replan → 只重跑失败部分 | LLM 自己反思 → 决定 replan/reflect/escalate | Alpha 错误恢复精确；Beta 恢复策略更丰富但不确定性更高 |
| **事件模型** | 结构化事件：PlanEvent / TaskStart / TaskComplete / VerifyEvent | 自由格式事件：DecisionEvent / ReflectEvent / ReplanEvent / GuardrailEvent | Alpha 事件适合前端渲染进度条；Beta 事件适合监控和审计 |
| **检查点策略** | 按拓扑层检查点：每层执行完保存 | 按步数检查点：每3步保存 + 每次 replan 保存 | Alpha 检查点与图结构对齐，恢复精确；Beta 检查点更频繁但恢复点不够精确 |
| **开发者的心智模型** | "我画一个 DAG，Runtime 帮我跑" | "我设目标和护栏，LLM 自己想办法" | Alpha 对习惯工作流的开发者友好；Beta 对习惯 prompt engineering 的开发者友好 |
| **合规审计** | 事件日志（做了什么） | 推理链（做了什么 + 为什么这样做） | Alpha 满足"可观测性"；Beta 满足"可解释性" |
| **可调试性** | 高：TaskGraph 可视化，节点状态明确 | 低：LLM 的决策路径不可预测 | Alpha 的调试是"看图找问题"；Beta 的调试是"读推理链找决策失误" |
| **适用场景** | 结构化业务流程（审批、数据处理、ETL） | 开放式探索（数据分析、研究报告、创意生成） | 不是替代关系，是互补关系 |

### 分叉点：五个关键设计决策

```
决策 1：谁来规划？
  Alpha: 开发者定义 TaskGraph（或 LLM 生成但开发者审批）
  Beta:  LLM 完全自主规划，开发者只定义 Goal
  → 选择了 Beta 路径

决策 2：谁来验证？
  Alpha: 独立的 Verifier（另一次 LLM 调用）
  Beta:  LLM 自己反思（同一个循环中的 Reflect 决策）
  → 选择了 Beta 路径

决策 3：错误恢复粒度？
  Alpha: 节点级（只重跑失败节点）
  Beta:  路径级（LLM 决定是 replan 整条路还是局部调整）
  → 选择了 Beta 路径

决策 4：护栏在哪里？
  Alpha: 结构约束（TaskGraph 本身限制了 LLM 的行动范围）
  Beta:  显式护栏引擎（GuardrailEngine 在每步检查）
  → Beta 需要更强的护栏因为 LLM 的自由度更高

决策 5：开发者需要理解什么？
  Alpha: TaskGraph / TaskNode / TaskEdge（结构化概念）
  Beta:  GoalSpec / Constraint / Budget（约束性概念）
  → Beta 的抽象层次更高
```

---

## 三、苏格拉底式自我诘问

### 诘问 1：为什么让 LLM 完全自主决策，而不是保留 Alpha 的结构化规划？

**反面论证（为什么不这样做）**：
LLM 不是完全可靠的决策者。它在复杂场景下会产生幻觉、遗漏步骤、或者陷入循环。结构化规划（TaskGraph）提供了"护栏"——LLM 只能在预设的框架内行动。

**替代方案（为什么不那样做）**：
混合模式——Alpha 的 TaskGraph + Beta 的自主决策。在 TaskGraph 的每个节点内部，LLM 可以自主决定怎么执行。但这会产生两套心智模型，增加开发者的认知负担。

**3 个月后的灾难**：
一个金融客户要求"Agent 自主分析客户信用并生成风险评估"。LLM 在第 7 步决定查看客户的社交关系图谱（调用了一个查询成本极高的工具），消耗了 80% 的预算，但这个信息对信用评估几乎没有帮助。更糟糕的是，审计人员看到推理链后发现 LLM 的第 4 步推理有事实错误，导致后续所有决策都建立在错误假设上。

**LLM 幻觉/能力不足时的崩溃模式**：
LLM 在决策中产生事实幻觉 → 基于错误事实做后续决策 → 决策链越来越偏离目标 → 要么超预算、要么超时、要么产出完全错误的结果。自我反思机制可能识别不到幻觉——因为反思的 LLM 也是同一个模型。

**我的回应**：
这个诘问击中了 Beta 的核心弱点——LLM 不是可靠的理由引擎。缓解措施：
1. BudgetGuard 强制约束 LLM 的行动次数
2. ReasoningTrail 让审计人员能追溯到具体哪一步出了问题
3. Beta 不适合高可靠性要求的场景——这种场景用 Alpha

### 诘问 2：为什么用 GuardrailEngine 做护栏，而不是用更简单的"工具白名单"？

**反面论证**：
GuardrailEngine 过度设计了。大多数企业场景只需要"这个工具能用，那个不能"。一个简单的白名单/黑名单就够了。

**替代方案**：
分层护栏——第一层是简单的工具白名单（编译时检查），第二层是运行时的参数检查（正则匹配），第三层是 LLM-based 检查（用另一个 LLM 评估决策安全性）。三层递进。

**3 个月后的灾难**：
企业客户说"我们有一个工具叫 execute_query，LLM 用它执行了 DROP TABLE"。白名单上只有工具名，没有检查参数内容。GuardrailEngine 的 DataConstraint（正则匹配参数）理论上可以拦截，但正则维护成本很高——每次数据库 schema 变更都要更新正则。

**LLM 幻觉/能力不足时的崩溃模式**：
LLM 生成的工具调用参数恰好绕过了正则匹配（比如用 `/**/DROP/**/TABLE` 绕过 `DROP.*TABLE`）。护栏被绕过，造成数据损坏。

**我的回应**：
护栏永远不可能完美——这是一个"防御深度"问题。Beta 的正确做法是：
1. GuardrailEngine 是第一道防线（快速拦截明显的违规）
2. 工具实现本身要有权限控制（不信任上游参数）
3. 对于高风险操作，HumanApprovalGate 是兜底

### 诘问 3：为什么用 DecisionHistory 作为状态，而不是用结构化状态（类似 Alpha 的 TaskGraph 进度）？

**反面论证**：
DecisionHistory 是线性的——它记录了"做了什么"但没有记录"还差什么"。Alpha 的 TaskGraph + 进度追踪天然知道哪些节点已完成、哪些待执行。Beta 丢失了这个全局视图。

**替代方案**：
在 Beta 中也维护一个"隐式计划"——LLM 在第一次决策时被强制要求输出一个粗糙的计划，后续决策按计划执行。但这又回到了 Alpha 的路径。

**3 个月后的灾难**：
一个 20 步的复杂任务，LLM 在第 15 步时忘记了前面的决策（因为 DecisionHistory 被压缩了），重复执行了第 5 步已经做过的查询。上下文窗口管理器为了节省 token，把第 5 步的结果摘要了，但摘要不够详细，LLM 以为还没做过。

**LLM 幻觉/能力不足时的崩溃模式**：
LLM 的上下文窗口管理器压缩决策历史时丢失了关键信息 → LLM 做出重复或矛盾的决策 → 决策质量随步数增加而下降。

**我的回应**：
这是 Beta 的固有弱点。缓解措施：
1. VFS 作为"外部记忆"——关键结果写入 VFS，不依赖上下文窗口
2. SelfReflectionTrigger 在连续重复操作时强制反思
3. 对于超过 15 步的任务，建议用 Alpha 的结构化路径

### 诘问 4：为什么 LLM 自我反思可以替代独立的 Verifier？

**反面论证**：
自我反思是"让犯错的人检查自己的错误"——认知偏差会让 LLM 忽略自己的错误。Alpha 的独立 Verifier 用不同的 LLM 调用（甚至可以用不同的模型）做验证，更有可能发现错误。

**替代方案**：
Beta + 独立验证：LLM 自主编排，但在 final_answer 之前，强制调用一次独立的 Verifier。结合了 Alpha 的可靠性和 Beta 的灵活性。

**3 个月后的灾难**：
LLM 在第 12 步输出了 final_answer，reasoning 写的是"我已经完成了所有分析"。但实际上它跳过了异常检测步骤——因为 SelfReflection 没有触发（连续工具调用只有 2 次，没到阈值 3），而 LLM 的 confidence 被设为 0.9。如果有一个独立 Verifier，它会检查到"异常检测"这个成功标准没有被满足。

**LLM 幻觉/能力不足时的崩溃模式**：
LLM 过度自信地认为目标已达成 → 输出 final_answer → 缺少独立验证 → 错误结果被提交。

**我的回应**：
这是最有说服力的诘问。修正方案：Beta 在产出 final_answer 之前，增加一个"强制验证点"——检查 SuccessCriteria 是否真的被满足。对于 StructuredCriteria，可以用脚本自动化验证；对于 TextualCriteria，至少增加一个独立的 LLM 调用做验证。这不改变 Beta 的自主性（LLM 仍然自主决策），只是增加了一个安全网。

### 诘问 5：为什么用 `@GoalSpec` 方法让开发者定义目标，而不是用 YAML/properties？

**反面论证**：
GoalSpec 涉及大量领域知识（约束、预算、工具权限），Java 代码不是业务人员能读懂的。YAML 更适合让产品经理/业务专家定义目标和约束。

**替代方案**：
```yaml
openjiuwen:
  agents:
    enterprise-data-analyst:
      goal: "分析销售数据并生成季度报告"
      success-criteria:
        type: structured
        description: "报告包含总销售额、环比增长率、Top 10 产品、异常检测"
        assertion: "assert result.includes('总销售额')"
      constraints:
        - type: data
          severity: BLOCK
          blocked-patterns: ["UPDATE.*SET", "DELETE.*FROM"]
      budget:
        max-tokens: 50000
        max-tool-calls: 30
```

**3 个月后的灾难**：
业务人员在 YAML 里把 `max-tool-calls` 设成了 300（以为越大越好），LLM 在一个简单查询上循环调了 200 次工具，消耗了大量 Token。业务人员不理解每个参数的含义，只是"把数字调大一点试试"。

**LLM 幻觉/能力不足时的崩溃模式**：
不适用——这是配置问题，不是 LLM 问题。但糟糕的配置会放大 LLM 的行为偏差。

**我的回应**：
两者都应该支持。代码定义（`@GoalSpec`）是开发者友好的，YAML 定义是业务友好的。优先级：YAML > `@GoalSpec` > 默认值。这与 Alpha 的三级覆盖策略一致。

### 诘问 6（附加）：Beta 的 ReasoningTrail 在企业合规中真的有用吗？

**反面论证**：
审计人员不需要看 LLM 的"推理过程"——他们需要看的是"做了什么操作、访问了什么数据、结果是什么"。ReasoningTrail 中的 reasoning 字段是 LLM 生成的自然语言，不可验证、不可信。审计的基石是确定性的日志，不是概率性的推理。

**替代方案**：
Beta 的审计只记录确定性的操作日志（工具调用、数据访问、时间戳），reasoning 作为附加信息而非审计依据。

**3 个月后的灾难**：
监管审查要求"解释为什么 Agent 删除了这条记录"。ReasoningTrail 中写着"LLM 认为这是重复数据"——但监管不接受 LLM 的"认为"作为解释。审计人员需要的是"基于什么规则、在什么条件下、触发了什么操作"。

**LLM 幻觉/能力不足时的崩溃模式**：
LLM 在 reasoning 中写了"基于用户授权删除"——但用户从未授权。reasoning 本身就是幻觉，审计反而被误导。

**我的回应**：
ReasoningTrail 不是审计依据，是调试和改进的辅助工具。企业合规的审计依据仍然是确定性的操作日志（GuardrailEvent + ToolCallEvent）。ReasoningTrail 的定位是"为什么"的补充解释，不是"为什么"的法律证据。

---

## 四、极端场景压力测试

### 场景 A：LLM 在 Plan 阶段持续生成无效方案（超过 10 次 replan）

**场景描述**：
目标是"分析 A 公司和 B 公司的合并可行性"。LLM 反复生成不同的分析方案，但每次在执行中发现方案不可行（缺少数据、工具不支持、约束冲突），触发 replan。10 次后仍然没有找到有效路径。

**执行失败轨迹**：

```
Step 1: [ReflectDecision] confidence=0.3, gaps=["缺少A公司财务数据"]
Step 2: [ToolCallDecision] tool=query_sales, args={company: "A"}
        → 结果：数据不存在
Step 3: [ReflectDecision] confidence=0.2, gaps=["A公司数据不可用"]
Step 4: [ReplanDecision] reason="缺少A公司数据，改用行业公开数据", newApproach="行业对标"
Step 5: [ToolCallDecision] tool=query_public_data, args={industry: "tech"}
        → 护栏拦截：query_public_data 不在工具白名单中
Step 6: [ReflectDecision] confidence=0.1, gaps=["工具不足"]
Step 7: [ReplanDecision] reason="公开数据工具不可用，改用已有数据", newApproach="仅用B公司数据"
Step 8: [ToolCallDecision] tool=query_sales, args={company: "B"}
        → 结果：数据量不足（只有3条记录）
Step 9: [ReflectDecision] confidence=0.1, gaps=["数据量不足"]
Step 10: [ReplanDecision] reason="数据量不足，无法分析", newApproach="生成定性报告"
Step 11: [ReflectDecision] confidence=0.1, gaps=["仍然缺少关键数据"]
        → 超过 maxReplanCount=5 → 触发错误
        → [ErrorEvent] "超过最大重规划次数：5"

最终状态：FAILED
消耗预算：Token=12000/50000, ToolCalls=2/30, Replan=6/5(超限)
```

**诊断**：
- 根因：目标与可用资源不匹配（缺少关键数据 + 工具不足）
- LLM 的 replan 策略是"试错式"的，不是"分析式"的
- 每次 replan 后没有先验证新路径的可行性

**缓解措施设计**：
```java
// ReplanFeasibilityCheck：每次 replan 后先做可行性检查
public class ReplanFeasibilityCheck {
    /**
     * LLM 提出 replan 后，先评估新路径的可行性。
     * 评估维度：数据可用性、工具支持、预算剩余。
     */
    public FeasibilityAssessment assess(
            String newApproach, BetaTaskContext ctx) {
        String prompt = """
            以下执行计划是否可行？

            新计划：%s
            可用工具：%s
            可用数据：%s
            剩余预算：token=%d, toolCalls=%d

            请评估可行性（0.0-1.0）并说明原因。
            """.formatted(newApproach, ctx.tools(), ctx.workingMemory(),
                remaining(ctx));

        return chatModel.callForObject(prompt, FeasibilityAssessment.class);
    }
}
```

### 场景 B：LLM 自主调用了企业敏感工具（删除数据库记录）

**场景描述**：
目标是"清理过期的临时订单"。LLM 在分析过程中决定调用 `delete_order` 工具删除一批"看起来过期"的订单，但这些订单实际上只是状态为"待审核"，并非过期。

**执行失败轨迹**：

```
Step 1-5: [正常分析流程]
          LLM 查询了订单列表，识别出 50 条"状态异常"的订单
Step 6: [ReflectDecision] confidence=0.7, assessment="识别出50条异常订单"
Step 7: [ToolCallDecision] tool=delete_order, args={orderIds: [...50个ID...]}
        reasoning="这些订单状态异常，看起来是过期临时订单，需要清理"
        
        → GuardrailEngine 检查：
          ✓ ToolWhitelistGuardrail: delete_order 在白名单中（开发者允许了）
          ✓ DangerousOperationGuardrail: 参数中没有 DELETE/DROP 模式
          ✗ SensitiveToolApprovalGuardrail: delete_order 需要人工审批
            → severity=ESCALATE
            → 暂停执行，等待人工审批
        
        → [PauseEvent] WAITING_FOR_HUMAN_APPROVAL
        → "Agent 请求使用敏感工具：delete_order"

人工审批结果：拒绝
        → [GuardrailEvent] "敏感工具 'delete_order' 被人工拒绝"
        → 告知 LLM：你的决策被阻止，请换一种方式
Step 8: [ReflectDecision] confidence=0.4, assessment="删除被拒绝，改用标记方式"
Step 9: [ToolCallDecision] tool=flag_order, args={orderIds: [...50个ID...], flag: "REVIEW_NEEDED"}
        reasoning="不能删除，改为标记为需要审核"
        → 护栏检查：通过
        → 执行成功
Step 10: [FinalAnswerDecision] answer="已标记50条异常订单为'需要审核'..."
```

**诊断**：
- SensitiveToolApprovalGuardrail 成功拦截了危险操作
- LLM 能够在被拦截后调整策略（从删除改为标记）
- 如果 delete_order 不在敏感工具列表中 → 灾难性后果

**暴露的问题**：
```yaml
# 开发者必须把所有危险操作列入 sensitive-tools
# 遗漏任何一个都是潜在事故
openjiuwen:
  agents:
    order-cleaner:
      constraints:
        - type: tool
          severity: ESCALATE
          require-approval-tools:
            - delete_order
            - batch_update_order
            - modify_payment_status   # 如果遗漏了这个？
```

**缓解措施**：
```java
// 默认敏感工具策略：写操作默认需要审批，除非显式声明为安全
public final class DefaultWriteOperationGuardrail implements Guardrail {
    /**
     * 任何非查询类工具（名称包含 write/update/delete/modify/create/insert），
     * 如果不在 goalSpec.explicitlyApprovedTools 中，默认需要 ESCALATE。
     */
    @Override
    public GuardrailResult check(LLMDecision decision, GoalSpec goal) {
        if (decision instanceof ToolCallDecision(_, _, String tool, _)) {
            if (isWriteOperation(tool) && !goal.explicitlyApprovedTools().contains(tool)) {
                return new GuardrailResult(false,
                    "写操作 '" + tool + "' 默认需要人工审批",
                    ConstraintSeverity.ESCALATE);
            }
        }
        return new GuardrailResult(true, null, null);
    }
}
```

### 场景 C：子Agent 与父Agent 产生"目标漂移"

**场景描述**：
目标是"为企业生成年度经营报告"。主 Agent 生成了 3 个子 Agent：子Agent-1 分析财务数据，子Agent-2 分析客户数据，子Agent-3 分析竞争对手。子Agent-3 在分析过程中发现了一个有趣的竞争格局变化，开始深入研究这个变化（花了大量预算），忘记了原始目标是为年度报告提供竞争对手摘要。

**执行失败轨迹**：

```
=== 主Agent ===
Goal: "生成年度经营报告"
Step 1-3: [分析报告结构]
Step 4: [SpawnSubAgentDecision] subGoal="分析竞争对手动态",
        allowedTools=["web_search", "competitor_db", "news_api"]
        → 子Agent-3 启动

=== 子Agent-3 ===
Goal (继承): "为年度经营报告提供竞争对手摘要"
Step 1: [ToolCallDecision] tool=competitor_db, args={action: "overview"}
        → 结果：5 个竞争对手概览
Step 2: [ReflectDecision] confidence=0.5,
        gaps=["竞争对手X最近有重大战略调整，需要深入研究"]
Step 3: [ToolCallDecision] tool=news_api, args={query: "竞争对手X 战略调整"}
        → 结果：50 条新闻
Step 4: [ToolCallDecision] tool=web_search, args={query: "竞争对手X 最新动态"}
        → 结果：20 篇深度文章
Step 5: [ReflectDecision] confidence=0.3,
        gaps=["需要分析竞争对手X的新产品战略对市场的影响"]
Step 6: [ToolCallDecision] tool=web_search, args={query: "竞争对手X 新产品分析"}
        → Token 预算已达子Agent限额的 80%
Step 7: [ReflectDecision] confidence=0.2,
        gaps=["研究不够深入"]
        → 继续深挖...
        → Token 预算耗尽 → [ErrorEvent] "预算耗尽"

=== 主Agent ===
Step 5: [SubAgentCompleteEvent] 子Agent-3 失败，返回了部分结果
Step 6: [ReflectDecision] confidence=0.5,
        assessment="竞争对手分析不完整"
Step 7: [FinalAnswerDecision] reasoning="年度报告缺少竞争对手分析部分，
        但其他部分完整", answer="年度报告（缺少竞争对手详情）..."
```

**诊断**：
- 子Agent-3 的目标从"提供摘要"漂移到"深入研究竞争对手X"
- 根因：子Agent 的 GoalSpec 没有足够明确地约束范围
- 预算约束起了作用（子Agent 最终被预算限制截断），但结果是主Agent 得到了不完整的分析

**缓解措施设计**：
```java
// GoalAlignmentCheck：子Agent 定期检查与父Agent 目标的对齐度
public class GoalAlignmentCheck {
    /**
     * 子Agent 每执行 5 步，检查一次是否偏离原始子目标。
     */
    public double checkAlignment(
            String parentGoal, String subGoal,
            List<LLMDecision> subAgentDecisions) {
        String prompt = """
            子Agent 的任务是：%s
            父Agent 的总体目标是：%s

            子Agent 的决策历史：
            %s

            请评估子Agent 是否偏离了任务目标（0.0=完全偏离, 1.0=完全对齐）。
            """.formatted(subGoal, parentGoal,
                formatDecisions(subAgentDecisions));

        return chatModel.callForObject(prompt, Double.class);
    }
}

// 在 AutonomousOrchestrator 中集成：
if (stepCount % 5 == 0 && isSubAgent()) {
    double alignment = goalAlignmentCheck.checkAlignment(
        parentGoal, currentGoal, decisionHistory);
    if (alignment < 0.5) {
        // 注入纠正提示
        contextForLLM += "\n[系统警告] 你的行为正在偏离原始任务目标。请回归正题。";
    }
}
```

---

## 五、架构基因评分

| 维度 | 评分 | 理由 |
|------|------|------|
| **极致灵活性** | **9/10** | LLM 可以处理任何类型的目标，不受预设结构限制。扣 1 分因为 Guardrails 确实约束了自由度——但这是故意的 |
| **认知心智负担** | **4/10** (越低越好) | 开发者需要理解 GoalSpec / Constraint / Budget / Guardrail 四个概念，比 Alpha 的 @Agent+@Tool 复杂。但比 LangChain 的 Graph+State+Channel 简单。中等偏高 |
| **抗熵增能力** | **5/10** | LLM 的不确定性随任务长度和复杂度增长。DecisionHistory 的累积、上下文窗口的压缩、子Agent 的目标漂移——都是熵增的来源。Guardrails 和 Budget 是抗熵手段，但不能完全消除 |
| **企业合规友好度** | **7/10** | ReasoningTrail 提供了"可解释性"，GuardrailEngine 提供了"可控性"。但 LLM 决策的非确定性让合规审计困难——审计人员无法重现 LLM 的决策路径 |
| **高并发吞吐** | **8/10** | 与 Alpha 相同的底层：Agent 无状态 + 虚拟线程。但 Beta 的每个 Task 消耗更多 LLM 调用（反思、护栏检查），单个 Task 的延迟更高 |
| **状态强一致性** | **5/10** | Beta 的状态是 DecisionHistory——一个只追加的列表。检查点的粒度是"每 N 步"，不像 Alpha 的"每拓扑层"那样精确。恢复后 LLM 需要重新"理解"上下文，不保证恢复后的行为与崩溃前一致 |
| **LLM 友好度** | **9/10** | LLM 在 Beta 中获得了最大自由度——自主决策路径、自主工具选择、自主反思纠错。Runtime 提供了完整的上下文（决策历史+工作记忆+预算状态），LLM 拥有做出好决策所需的所有信息 |

**综合雷达图**：

```
            极致灵活性 (9)
                 │
       LLM友好度(9)───┼─── 认知心智负担 (4, 越低越好)
                 │
  企业合规(7) ───┼─── 抗熵增 (5)
                 │
   高并发吞吐(8) ┼ 状态强一致性 (5)
```

### 帕累托前沿分析

```
Alpha 擅长的维度（开发者控制优势）：
  - 认知心智负担：3/10（更低，更简单）
  - 抗熵增：7/10（结构化天然抗熵）
  - 企业合规：8/10（确定性审计）
  - 状态强一致性：8/10（检查点精确）

Beta 擅长的维度（LLM 自主优势）：
  - 极致灵活性：9/10（vs Alpha 的 6/10）
  - LLM 友好度：9/10（vs Alpha 的 5/10）

两者持平的维度：
  - 高并发吞吐：8/10（底层相同）
```

**结论**：Alpha 和 Beta 不是替代关系，是帕累托前沿上的两个点。
- Alpha 在可控性、可审计性、可预测性上占优
- Beta 在灵活性、适应性、LLM 潜力释放上占优
- 正确的产品策略：**两者共存，开发者按场景选择**

---

## 六、模块结构与文件清单

```
openjiuwen-java-core/                     ← 新增 Beta 类型
└── com.openjiuwen.java.core.beta/
    ├── model/
    │   ├── GoalSpec.java                 ← 目标规范
    │   ├── SuccessCriteria.java          ← 成功标准 (sealed interface)
    │   ├── TextualCriteria.java
    │   ├── StructuredCriteria.java
    │   ├── HumanApprovalCriteria.java
    │   ├── Constraint.java               ← 约束 (sealed interface)
    │   ├── ToolConstraint.java
    │   ├── DataConstraint.java
    │   ├── TimeConstraint.java
    │   ├── BudgetConstraint.java
    │   ├── ConstraintSeverity.java
    │   ├── Budget.java                   ← 资源预算
    │   ├── LLMDecision.java              ← LLM决策 (sealed interface)
    │   ├── ToolCallDecision.java
    │   ├── SpawnSubAgentDecision.java
    │   ├── ReflectDecision.java
    │   ├── ReplanDecision.java
    │   ├── FinalAnswerDecision.java
    │   ├── EscalateDecision.java
    │   ├── EscalationReason.java
    │   ├── ReasoningTrail.java           ← 审计推理链
    │   ├── DecisionRecord.java
    │   ├── GuardrailCheck.java
    │   └── GuardrailEvent.java
    ├── event/
    │   ├── DecisionEvent.java            ← Beta新增事件
    │   ├── GuardrailBlockedEvent.java
    │   ├── ReflectEvent.java
    │   ├── ReplanEvent.java
    │   ├── SubAgentSpawnEvent.java
    │   ├── SubAgentCompleteEvent.java
    │   ├── EscalateEvent.java
    │   └── PauseEvent.java
    ├── strategy/
    │   └── BetaTaskContext.java           ← Beta任务上下文
    └── annotation/
        └── GoalSpec.java                  ← @GoalSpec 注解

openjiuwen-java-runtime/                   ← 新增 Beta 实现
└── com.openjiuwen.java.runtime.beta/
    ├── AutonomousOrchestrator.java        ← 自主编排器（核心）
    ├── guardrail/
    │   ├── GuardrailEngine.java           ← 护栏引擎
    │   ├── Guardrail.java                 ← 护栏接口
    │   ├── GuardrailResult.java
    │   ├── ToolWhitelistGuardrail.java    ← 工具白名单
    │   ├── DangerousOperationGuardrail.java ← 危险操作检测
    │   ├── SensitiveToolApprovalGuardrail.java ← 敏感工具审批
    │   ├── BudgetExhaustionGuardrail.java ← 预算耗尽
    │   ├── MaxDepthGuardrail.java         ← 递归深度限制
    │   ├── DefaultWriteOperationGuardrail.java ← 默认写操作审批
    │   └── HumanApprovalGate.java         ← 人工审批门接口
    ├── reflection/
    │   ├── SelfReflectionTrigger.java     ← 自我反思触发器
    │   ├── GoalAlignmentCheck.java        ← 目标对齐检查（子Agent漂移检测）
    │   └── ReplanFeasibilityCheck.java    ← 重规划可行性检查
    ├── context/
    │   └── ContextWindowManager.java      ← 上下文窗口管理
    ├── audit/
    │   ├── ReasoningTrailRecorder.java    ← 推理链记录器
    │   └── AuditReportGenerator.java      ← 审计报告生成
    └── config/
        └── BetaAutoConfiguration.java     ← Beta 自动配置
```

---

## 七、Alpha + Beta 共存策略

### 配置选择

```java
// Alpha 路径（开发者显式控制）
@Agent(name = "order-approval", strategy = "deep")
public class OrderApprovalAgent { ... }

// Beta 路径（LLM 自主编排）
@Agent(name = "market-analysis", strategy = "autonomous")
public class MarketAnalysisAgent { ... }
```

```yaml
# 全局默认策略
openjiuwen:
  default-strategy: deep         # 默认用 Alpha（安全）

  # 也可以按 Agent 覆盖
  agents:
    market-analysis:
      strategy: autonomous       # 这个 Agent 用 Beta
      autonomous:
        max-replan-count: 10
        reflection-interval: 5
        guardrails:
          - tool-whitelist
          - dangerous-operation
          - default-write-operation
```

### Beta 的启用条件

Beta 不是默认路径——它需要开发者显式声明。原因：
1. Beta 的认知心智负担更高
2. Beta 的 LLM 调用成本更高
3. Beta 的可预测性更低

建议启用条件：
- 目标是开放式的（没有确定的最优路径）
- 开发者理解 GoalSpec / Constraint / Budget
- 有足够的 LLM 预算
- 有审计和护栏机制

---

## 八、信息来源

| 来源 | 级别 | 可信度 | 时效性 |
|------|------|--------|--------|
| [openjiuwen-java Deep Agent 架构推演](./2026-06-06-openjiuwen-java-deep-agent-architecture.md) | [解读] | [最高] | [当前有效] |
| [openjiuwen-java 架构 v3](./2026-06-06-openjiuwen-java-architecture-v3.md) | [解读] | [最高] | [当前有效] |
| [openjiuwen-java 架构 v2](./2026-06-06-openjiuwen-java-architecture-v2.md) | [解读] | [最高] | [当前有效] |
| [GEPA 架构推演第21-30轮](./2026-06-06-gepa-runtime-rounds-21-30.md) | [解读] | [最高] | [当前有效] |
| [openjiuwen Runtime 重构架构](./2026-06-05-openjiuwen-runtime-architecture.md) | [解读] | [最高] | [当前有效] |
| [Deep Agents - LangChain Blog](https://www.langchain.com/blog/deep-agents) | [技术媒体] | [最高] | [当前有效] |
| [Autonomous Deep Agent - arXiv 2502.07056](https://arxiv.org/html/2502.07056v1) | [原文] | [最高] | [当前有效] |
| [Claude Code 系统提示词分析] | [解读] | [技术媒体] | [当前有效] |
| [CrewAI "Entangled Software" 理念] | [技术媒体] | [待验证] | [当前有效] |

---

_最后更新：2026-06-07_
_前置：[openjiuwen-java 架构 v3](./2026-06-06-openjiuwen-java-architecture-v3.md)（Alpha 变体）_
