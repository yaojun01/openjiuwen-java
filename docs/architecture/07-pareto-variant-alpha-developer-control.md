# Openjiuwen-Java Runtime 帕累托变体 Alpha：开发者显式控制路径

> 2026-06-07 | 帕累托变体 Alpha
> 核心假设：**开发者是 Runtime 的主人，LLM 是执行工具**
> 设计目标：给开发者最大程度的控制力和可预测性
> 方法：先思考 → 接口先行 → 极端场景验证

---

## 零、设计哲学：为什么需要"开发者显式控制"

在之前 v3 架构中，Plan-Execute-Verify 模型的 Planner 将任务分解交给了 LLM。这带来一个根本性的张力：

```
v3 的问题：LLM 生成的 TaskGraph 是黑盒。
  - 开发者无法预判 LLM 会怎么拆任务
  - 开发者无法在 Plan 阶段介入修正
  - 验证失败后 LLM 自己 replan，开发者丢失控制权
  - 企业合规要求"每一步可审计、可干预"，但 LLM 自主规划不满足
```

帕累托变体 Alpha 的解法：**把 LLM 从决策者降级为建议者，开发者始终持有最终决定权。**

```
变体 Alpha 的核心模型：

  Developer defines: WHAT（目标）+ CONSTRAINTS（约束）+ APPROVAL GATES（审批门）
  LLM suggests:     HOW（执行方案）
  Runtime enforces: 按开发者的规则执行，LLM 偏离时熔断
```

这不是否定 v3 的 PEV 模型——PEV 是执行引擎。变体 Alpha 是在 PEV 之上加了一层**开发者控制面**。

---

## 一、核心架构骨架

### 1.1 架构全景

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     帕累托变体 Alpha：开发者控制面                        │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Developer Control Layer（开发者写这些，Runtime 严格执行）         │    │
│  │                                                                  │    │
│  │  AgentDefinition        ← 开发者定义 Agent 的身份、能力、边界      │    │
│  │  ExecutionPolicy        ← 开发者定义执行规则：并行度、超时、重试    │    │
│  │  ApprovalGate           ← 开发者定义审批门：哪些步骤需要人工确认    │    │
│  │  Constraint             ← 开发者定义约束：工具白名单、数据访问范围  │    │
│  │  ErrorHandler           ← 开发者定义错误策略：降级、熔断、回退      │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                          │                                              │
│                          ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  PEV Execution Engine（Plan-Execute-Verify，LLM 是执行工具）       │    │
│  │                                                                  │    │
│  │  ┌──────────┐    ┌──────────────┐    ┌──────────┐               │    │
│  │  │ Planner  │    │ Executor     │    │ Verifier │               │    │
│  │  │(LLM建议) │ →  │(确定性执行)  │ →  │(LLM检查) │               │    │
│  │  │          │    │              │    │          │               │    │
│  │  │ Plan需经  │    │ 按拓扑排序   │    │ 结果需经  │               │    │
│  │  │ 开发者审批 │    │ + 约束检查   │    │ 开发者规则│               │    │
│  │  └──────────┘    │ 检查执行     │    │ 校验     │               │    │
│  │                   └──────────────┘    └──────────┘               │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                          │                                              │
│                          ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Infrastructure Layer                                             │    │
│  │                                                                  │    │
│  │  ToolRegistry │ StateStore │ CheckpointStore │ EventBus │ VFS    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Core 模块：sealed interface 定义

```java
package com.openjiuwen.java.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

// ============================================================
// 第一层：Agent 定义（开发者身份卡）
// ============================================================

/**
 * Agent 定义。开发者通过注解或代码构建。
 * 不可变 record，线程安全，可序列化。
 *
 * 设计决策：为什么用 record 而不是 builder？
 * - record 天然不可变，强制开发者显式声明所有属性
 * - 没有 setter 意味着运行时不可能被意外修改
 * - 可预测性 = 控制力
 */
public record AgentDefinition(
    String name,
    String description,
    String version,
    SystemPrompt systemPrompt,
    List<ToolReference> tools,
    ExecutionPolicy policy,
    List<Constraint> constraints,
    List<ApprovalGate> approvalGates,
    ErrorPolicy errorPolicy
) {

    /**
     * 编译时校验。确保 Agent 定义在运行前就是合法的。
     * 不依赖运行时检查——错误越早发现越好。
     */
    public AgentDefinition {
        // null 检查
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Agent name must not be blank");
        if (systemPrompt == null)
            throw new IllegalArgumentException("System prompt must not be null");
        if (policy == null)
            throw new IllegalArgumentException("Execution policy must not be null");

        // 不可变防御拷贝
        tools = List.copyOf(tools);
        constraints = List.copyOf(constraints);
        approvalGates = List.copyOf(approvalGates);

        // 语义校验
        if (policy.maxParallelism() < 1)
            throw new IllegalArgumentException("maxParallelism must be >= 1");
        if (policy.maxRetries() < 0)
            throw new IllegalArgumentException("maxRetries must be >= 0");

        // 审批门引用的工具必须存在于工具列表中
        approvalGates.forEach(gate -> {
            if (gate.trigger() == ApprovalTrigger.ON_TOOL_CALL
                && tools.stream().noneMatch(t -> t.name().equals(gate.toolName()))) {
                throw new IllegalArgumentException(
                    "Approval gate references unknown tool: " + gate.toolName());
            }
        });
    }
}

/**
 * 系统提示。支持静态文本和动态模板。
 * 为什么不用 String？因为需要区分"开发者写的固定提示"和"运行时动态生成的提示"。
 */
public sealed interface SystemPrompt
    permits StaticPrompt, DynamicPrompt {

    /** 渲染为最终文本。动态模板在此刻求值。 */
    String render(AgentContext ctx);
}

public record StaticPrompt(String template) implements SystemPrompt {
    @Override
    public String render(AgentContext ctx) { return template; }
}

public record DynamicPrompt(Function<AgentContext, String> templateFn) implements SystemPrompt {
    @Override
    public String render(AgentContext ctx) { return templateFn.apply(ctx); }
}

// ============================================================
// 第二层：执行策略（开发者的运行手册）
// ============================================================

/**
 * 执行策略。开发者声明式定义"怎么跑"。
 *
 * 关键设计：这不是接口实现，而是数据。
 * Runtime 读取这些参数来决定行为，开发者不需要写执行逻辑。
 */
public record ExecutionPolicy(
    int maxIterations,          // 单次 PEV 循环最大迭代次数
    int maxParallelism,         // 同层最大并行节点数
    int maxRetries,             // 验证失败最大重试次数
    Duration stepTimeout,       // 单步超时
    Duration totalTimeout,      // 总超时
    PlanningMode planningMode,  // 规划模式：自动 / 半自动 / 手动
    VerifyMode verifyMode,      // 验证模式：LLM自动 / 人工审核 / 跳过
    boolean enableCheckpoints,  // 是否启用检查点
    Duration checkpointInterval // 检查点间隔
) {
    /** 默认策略：保守但实用 */
    public static ExecutionPolicy DEFAULT = new ExecutionPolicy(
        10, 4, 2,
        Duration.ofSeconds(30), Duration.ofMinutes(10),
        PlanningMode.SEMI_AUTO, VerifyMode.LLM_AUTO,
        true, Duration.ofSeconds(5)
    );
}

/**
 * 规划模式。
 *
 * AUTO      → LLM 规划，直接执行（v3 的行为，适合简单任务）
 * SEMI_AUTO → LLM 规划，开发者可 pre-approve 或修改（推荐默认值）
 * MANUAL    → 开发者预先定义 TaskGraph，LLM 只填充细节
 */
public enum PlanningMode { AUTO, SEMI_AUTO, MANUAL }

/**
 * 验证模式。
 *
 * LLM_AUTO     → LLM 自动验证
 * HUMAN_REVIEW → 验证结果提交人工审核
 * SKIP         → 跳过验证（确定性流程用）
 */
public enum VerifyMode { LLM_AUTO, HUMAN_REVIEW, SKIP }

// ============================================================
// 第三层：约束系统（开发者的护栏）
// ============================================================

/**
 * 约束。开发者定义的边界条件。
 * sealed interface 确保只有已知的约束类型可以被声明。
 */
public sealed interface Constraint
    permits ToolWhitelist, DataScopeConstraint, CostConstraint, CustomConstraint {

    /** 约束名称，用于错误消息和日志 */
    String name();

    /** 运行时检查：给定的执行上下文是否满足此约束 */
    ConstraintResult check(AgentContext ctx, PlanStep step);
}

public record ConstraintResult(boolean satisfied, String message) {}

/** 工具白名单：只有列表中的工具可以被调用 */
public record ToolWhitelist(Set<String> allowedTools) implements Constraint {
    @Override
    public String name() { return "TOOL_WHITELIST"; }

    @Override
    public ConstraintResult check(AgentContext ctx, PlanStep step) {
        if (step.toolCall().isEmpty()) return new ConstraintResult(true, "");
        String tool = step.toolCall().get().toolName();
        boolean ok = allowedTools.contains(tool);
        return new ConstraintResult(ok,
            ok ? "" : "Tool '" + tool + "' is not in the whitelist");
    }
}

/** 数据范围约束：限制工具只能访问特定数据域 */
public record DataScopeConstraint(Set<String> allowedDataDomains) implements Constraint {
    @Override
    public String name() { return "DATA_SCOPE"; }

    @Override
    public ConstraintResult check(AgentContext ctx, PlanStep step) {
        // 检查工具参数中是否引用了不允许的数据域
        // 实现取决于具体的数据分类体系
        return new ConstraintResult(true, "");
    }
}

/** 成本约束：限制 token 使用量 */
public record CostConstraint(int maxInputTokens, int maxOutputTokens) implements Constraint {
    @Override
    public String name() { return "COST_LIMIT"; }

    @Override
    public ConstraintResult check(AgentContext ctx, PlanStep step) {
        var usage = ctx.tokenUsage();
        boolean ok = usage.inputTokens() <= maxInputTokens
                  && usage.outputTokens() <= maxOutputTokens;
        return new ConstraintResult(ok,
            ok ? "" : "Token budget exceeded: in=" + usage.inputTokens()
                      + "/" + maxInputTokens + ", out=" + usage.outputTokens()
                      + "/" + maxOutputTokens);
    }
}

/** 自定义约束：开发者提供任意逻辑 */
public record CustomConstraint(
    String name,
    Function<PlanStep, ConstraintResult> checker
) implements Constraint {
    @Override
    public ConstraintResult check(AgentContext ctx, PlanStep step) {
        return checker.apply(step);
    }
}

// ============================================================
// 第四层：审批门（开发者的人控开关）
// ============================================================

/**
 * 审批门。定义在哪些节点上需要人工确认才能继续。
 *
 * 为什么不用"全局开关"？
 * - 全局开关只有开/关两种状态
 * - 审批门可以精确到"调用这个工具时才需要审批"
 * - 企业合规需要的是细粒度控制，不是粗暴的全局开关
 */
public record ApprovalGate(
    String gateId,
    ApprovalTrigger trigger,
    String toolName,         // trigger=ON_TOOL_CALL 时有效
    String description,      // 给审批人的说明
    Duration timeout,        // 等待审批的超时，超时自动拒绝
    ApprovalAction onTimeout // 超时行为：REJECT / APPROVE / ESCALATE
) {}

public enum ApprovalTrigger {
    ON_TOOL_CALL,     // 调用指定工具时
    ON_PLAN_COMPLETE, // Plan 阶段完成后（开发者审查 TaskGraph）
    ON_VERIFY_FAIL,   // 验证失败时
    ON_SUB_AGENT,     // 创建子 Agent 时
    ALWAYS            // 每一步都审批（调试用）
}

public enum ApprovalAction { REJECT, APPROVE, ESCALATE }

/**
 * 审批结果。
 */
public sealed interface ApprovalDecision
    permits Approved, Rejected, Escalated {
    String gateId();
    String reviewer();
    Instant decidedAt();
}

public record Approved(String gateId, String reviewer, String comment, Instant decidedAt)
    implements ApprovalDecision {}

public record Rejected(String gateId, String reviewer, String reason, Instant decidedAt)
    implements ApprovalDecision {}

public record Escalated(String gateId, String from, String to, Instant decidedAt)
    implements ApprovalDecision {}

// ============================================================
// 第五层：工具系统
// ============================================================

/**
 * 工具引用。Agent 定义中声明自己能用的工具。
 */
public record ToolReference(
    String name,
    String description,
    String mcpServerId,          // null 表示本地工具
    Map<String, ParameterSchema> parameters
) {}

public record ParameterSchema(
    String name,
    String type,                // string / number / boolean / object / array
    String description,
    boolean required,
    String defaultValue
) {}

/**
 * 工具调用。执行过程中产生的具体调用请求。
 */
public record ToolCall(
    String callId,
    String toolName,
    Map<String, Object> arguments
) {}

/**
 * 工具结果。工具执行后的返回值。
 */
public record ToolResult(
    String callId,
    boolean success,
    Object data,
    String errorMessage
) {}

// ============================================================
// 第六层：错误策略
// ============================================================

/**
 * 错误策略。开发者定义"出错时怎么办"。
 */
public sealed interface ErrorPolicy
    permits FailFastPolicy, RetryPolicy, DegradePolicy {

    /** 处理错误，返回决策 */
    ErrorDecision handleError(ErrorContext ctx);
}

public record ErrorContext(
    AgentDefinition agent,
    PlanStep failedStep,
    Exception error,
    int retryCount,
    List<Checkpoint> checkpoints
) {}

public sealed interface ErrorDecision
    permits RetryDecision, SkipDecision, AbortDecision, FallbackDecision {
}

public record RetryDecision(Duration backoff) implements ErrorDecision {}
public record SkipDecision(String reason) implements ErrorDecision {}
public record AbortDecision(String reason) implements ErrorDecision {}
public record FallbackDecision(String fallbackTool, Map<String, Object> fallbackArgs)
    implements ErrorDecision {}

/** 快速失败：任何错误立即终止 */
public record FailFastPolicy() implements ErrorPolicy {
    @Override
    public ErrorDecision handleError(ErrorContext ctx) {
        return new AbortDecision("Fail-fast triggered: " + ctx.error().getMessage());
    }
}

/** 重试策略：可配置重试次数和退避 */
public record RetryPolicy(int maxRetries, Duration initialBackoff, double backoffMultiplier)
    implements ErrorPolicy {
    @Override
    public ErrorDecision handleError(ErrorContext ctx) {
        if (ctx.retryCount() >= maxRetries) {
            return new AbortDecision("Max retries (" + maxRetries + ") exceeded");
        }
        long delayMs = (long) (initialBackoff.toMillis()
                      * Math.pow(backoffMultiplier, ctx.retryCount()));
        return new RetryDecision(Duration.ofMillis(delayMs));
    }
}

/** 降级策略：出错时切换到备用工具 */
public record DegradePolicy(String fallbackTool, int maxRetriesBeforeDegrade)
    implements ErrorPolicy {
    @Override
    public ErrorDecision handleError(ErrorContext ctx) {
        if (ctx.retryCount() >= maxRetriesBeforeDegrade) {
            return new FallbackDecision(fallbackTool, Map.of());
        }
        return new RetryDecision(Duration.ofSeconds(1));
    }
}
```

### 1.3 Plan-Execute-Verify 的执行流程（带控制点）

```java
package com.openjiuwen.java.core;

// ============================================================
// 任务图（Plan 的产物）
// ============================================================

/**
 * 有向无环图。Plan 阶段的输出。
 *
 * 关键设计：TaskGraph 在变体 Alpha 中不是 LLM 的最终产物，
 * 而是 LLM 的"建议"。开发者可以：
 * 1. AUTO 模式：直接执行
 * 2. SEMI_AUTO 模式：审查后批准或修改
 * 3. MANUAL 模式：开发者预定义，LLM 只填充参数
 */
public record TaskGraph(
    String graphId,
    String goal,
    List<TaskNode> nodes,
    List<TaskEdge> edges,
    PlanningMode planningMode,
    boolean developerApproved,
    String developerComment
) {

    public TaskGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /**
     * 环检测。构造时自动校验。
     * 防止 LLM 幻觉导致环形依赖。
     */
    public TaskGraph {
        if (hasCycle(nodes, edges)) {
            throw new IllegalArgumentException(
                "TaskGraph contains cycle — LLM generated circular dependencies");
        }
    }

    /**
     * 拓扑排序，生成执行层。
     * 同层节点无依赖关系，可并行执行。
     */
    public List<List<TaskNode>> executionLayers() {
        // 标准拓扑排序算法（Kahn's algorithm）
        Map<String, Integer> inDegree = new java.util.HashMap<>();
        Map<String, List<String>> adj = new java.util.HashMap<>();

        for (TaskNode node : nodes) {
            inDegree.putIfAbsent(node.id(), 0);
            adj.putIfAbsent(node.id(), new java.util.ArrayList<>());
        }
        for (TaskEdge edge : edges) {
            adj.get(edge.from()).add(edge.to());
            inDegree.merge(edge.to(), 1, Integer::sum);
        }

        List<List<TaskNode>> layers = new java.util.ArrayList<>();
        java.util.Set<String> processed = new java.util.HashSet<>();

        while (processed.size() < nodes.size()) {
            List<TaskNode> currentLayer = nodes.stream()
                .filter(n -> !processed.contains(n.id()))
                .filter(n -> inDegree.getOrDefault(n.id(), 0) == 0)
                .toList();

            if (currentLayer.isEmpty()) {
                // 不应该发生（构造时已检测环），防御性编程
                throw new IllegalStateException("TaskGraph has unreachable nodes");
            }

            layers.add(currentLayer);
            for (TaskNode node : currentLayer) {
                processed.add(node.id());
                for (String target : adj.getOrDefault(node.id(), List.of())) {
                    inDegree.merge(target, -1, Integer::sum);
                }
            }
        }

        return layers;
    }

    private static boolean hasCycle(List<TaskNode> nodes, List<TaskEdge> edges) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Set<String> recursionStack = new java.util.HashSet<>();
        Map<String, List<String>> adj = new java.util.HashMap<>();

        for (TaskNode node : nodes) adj.put(node.id(), new java.util.ArrayList<>());
        for (TaskEdge edge : edges) adj.get(edge.from()).add(edge.to());

        for (TaskNode node : nodes) {
            if (hasCycleDFS(node.id(), adj, visited, recursionStack)) return true;
        }
        return false;
    }

    private static boolean hasCycleDFS(String node, Map<String, List<String>> adj,
                                        java.util.Set<String> visited,
                                        java.util.Set<String> stack) {
        if (stack.contains(node)) return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        stack.add(node);
        for (String next : adj.getOrDefault(node, List.of())) {
            if (hasCycleDFS(next, adj, visited, stack)) return true;
        }
        stack.remove(node);
        return false;
    }
}

public record TaskNode(
    String id,
    String description,
    TaskType type,
    Map<String, String> inputs,        // 可能引用上游输出：${node-A.output}
    String expectedOutput,
    Optional<ToolCall> toolCall,        // TOOL_CALL 类型时有值
    Optional<String> subAgentName       // SUB_AGENT 类型时有值
) {
    public TaskNode {
        inputs = Map.copyOf(inputs);
    }
}

public enum TaskType { TOOL_CALL, LLM_CALL, SUB_AGENT }

public record TaskEdge(
    String from,
    String to,
    String dataRef       // from 的什么数据传给 to，如 "output" 或 "output.status"
) {}

/**
 * 执行步骤。PlanStep 是执行过程中的最小单元。
 * 记录了每一步的完整信息，用于检查点和审计。
 */
public sealed interface PlanStep
    permits ReasoningStep, ToolExecutionStep, SubAgentStep, VerificationStep {
    String stepId();
    String taskId();
    Instant startedAt();
    Instant completedAt();
    StepStatus status();
}

public enum StepStatus {
    PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, AWAITING_APPROVAL
}

public record ReasoningStep(
    String stepId, String taskId,
    Instant startedAt, Instant completedAt,
    StepStatus status,
    String thought,
    TokenUsage tokenUsage
) implements PlanStep {}

public record ToolExecutionStep(
    String stepId, String taskId,
    Instant startedAt, Instant completedAt,
    StepStatus status,
    ToolCall call,
    ToolResult result,
    ConstraintResult constraintCheck,
    Optional<ApprovalDecision> approval
) implements PlanStep {}

public record SubAgentStep(
    String stepId, String taskId,
    Instant startedAt, Instant completedAt,
    StepStatus status,
    String subAgentName,
    String subTaskId
) implements PlanStep {}

public record VerificationStep(
    String stepId, String taskId,
    Instant startedAt, Instant completedAt,
    StepStatus status,
    boolean passed,
    String feedback,
    Set<String> failedNodeIds,
    TokenUsage tokenUsage
) implements PlanStep {}

public record TokenUsage(int inputTokens, int outputTokens, String model) {}
```

### 1.4 Agent 上下文与状态管理

```java
package com.openjiuwen.java.core;

// ============================================================
// Agent 上下文（每次执行新建，不共享）
// ============================================================

/**
 * Agent 执行上下文。
 *
 * 设计决策：为什么用 record？
 * - record 天然不可变。每一次状态变更产生新的 record。
 * - 这意味着任何时刻的 AgentContext 都是一个完整的快照。
 * - 快照 = 检查点的基础。
 *
 * 为什么不直接可变？
 * - 可变状态在并发环境下是灾难的根源
 * - 企业审计需要"任何时刻的状态都可以复现"
 * - 不可变 + 事件溯源 = 完整的审计日志
 */
public record AgentContext(
    String executionId,
    AgentDefinition agent,
    String userInput,
    ExecutionPhase phase,
    TaskGraph currentPlan,
    List<PlanStep> completedSteps,
    Map<String, Object> variables,      // 节点间传递的数据
    TokenUsage tokenUsage,
    Instant startedAt,
    Optional<Checkpoint> lastCheckpoint
) {

    /**
     * 推进到下一阶段。返回新的 AgentContext（不可变更新）。
     */
    public AgentContext withPhase(ExecutionPhase newPhase) {
        return new AgentContext(
            executionId, agent, userInput, newPhase,
            currentPlan, completedSteps, variables, tokenUsage,
            startedAt, lastCheckpoint
        );
    }

    /**
     * 记录完成的步骤。返回新的 AgentContext。
     */
    public AgentContext withStep(PlanStep step) {
        var steps = new java.util.ArrayList<>(completedSteps);
        steps.add(step);
        return new AgentContext(
            executionId, agent, userInput, phase,
            currentPlan, List.copyOf(steps), variables, tokenUsage,
            startedAt, lastCheckpoint
        );
    }

    /**
     * 存储节点输出。返回新的 AgentContext。
     */
    public AgentContext withVariable(String key, Object value) {
        var vars = new java.util.HashMap<>(variables);
        vars.put(key, value);
        return new AgentContext(
            executionId, agent, userInput, phase,
            currentPlan, completedSteps, Map.copyOf(vars), tokenUsage,
            startedAt, lastCheckpoint
        );
    }

    /**
     * 更新 token 用量。累加，不是替换。
     */
    public AgentContext withTokenUsage(TokenUsage additional) {
        var total = new TokenUsage(
            tokenUsage.inputTokens() + additional.inputTokens(),
            tokenUsage.outputTokens() + additional.outputTokens(),
            additional.model()
        );
        return new AgentContext(
            executionId, agent, userInput, phase,
            currentPlan, completedSteps, variables, total,
            startedAt, lastCheckpoint
        );
    }

    /** 空的初始上下文 */
    public static AgentContext initial(String executionId, AgentDefinition agent, String userInput) {
        return new AgentContext(
            executionId, agent, userInput,
            ExecutionPhase.CREATED,
            null, List.of(), Map.of(),
            new TokenUsage(0, 0, ""),
            Instant.now(),
            Optional.empty()
        );
    }
}

public enum ExecutionPhase {
    CREATED,       // 初始状态
    PLANNING,      // LLM 正在规划
    PLAN_REVIEW,   // 等待开发者审批 Plan（SEMI_AUTO / MANUAL 模式）
    EXECUTING,     // 执行中
    VERIFYING,     // 验证中
    REVIEWING,     // 等待人工审核验证结果（HUMAN_REVIEW 模式）
    REPLANNING,    // 验证失败，重新规划
    COMPLETED,     // 成功完成
    FAILED,        // 失败
    CANCELLED      // 被取消
}

// ============================================================
// 检查点
// ============================================================

/**
 * 检查点。AgentContext 的完整快照。
 *
 * 设计决策：检查点不存"增量"而是存"全量"。
 * 为什么？因为增量恢复需要依赖顺序，一旦某一步损坏就全完。
 * 全量恢复只需要最新一个检查点。
 * 存储成本可以用压缩解决。
 */
public record Checkpoint(
    String checkpointId,
    String executionId,
    int sequenceNumber,
    ExecutionPhase phase,
    AgentContext snapshot,
    Instant createdAt,
    CheckpointReason reason
) {}

public enum CheckpointReason {
    PHASE_TRANSITION,   // 阶段转换时
    LAYER_COMPLETE,     // 执行层完成时
    PERIODIC,           // 定期（按时间间隔）
    BEFORE_APPROVAL,    // 审批门前
    MANUAL              // 开发者手动触发
}

// ============================================================
// 状态存储接口
// ============================================================

/**
 * 检查点存储。开发者可以选择后端。
 * 接口声明了确定性边界——所有方法签名完整，没有 TODO。
 */
public interface CheckpointStore {

    /** 保存检查点 */
    void save(Checkpoint checkpoint);

    /** 加载最新的检查点。如果不存在返回 empty。 */
    Optional<Checkpoint> loadLatest(String executionId);

    /** 加载指定序号的检查点 */
    Optional<Checkpoint> load(String executionId, int sequenceNumber);

    /** 列出所有检查点（审计用） */
    List<Checkpoint> list(String executionId);

    /** 删除指定执行的所有检查点 */
    void deleteAll(String executionId);
}

/**
 * 变量存储。节点间传递的数据。
 * 与 CheckpointStore 分离，因为生命周期不同。
 */
public interface VariableStore {

    /** 存储变量 */
    void put(String executionId, String key, Object value);

    /** 读取变量 */
    <T> Optional<T> get(String executionId, String key, Class<T> type);

    /** 解析引用表达式：${node-A.output.status} */
    default Optional<Object> resolve(String executionId, String expression) {
        if (expression == null || !expression.startsWith("${") || !expression.endsWith("}")) {
            return Optional.of(expression);
        }
        String ref = expression.substring(2, expression.length() - 1);
        String[] parts = ref.split("\\.");
        // 简单实现：第一部分是节点 ID，后续是路径
        return get(executionId, parts[0], Object.class);
    }
}
```

### 1.5 事件系统

```java
package com.openjiuwen.java.core;

// ============================================================
// 事件系统（观察者友好，调试友好，审计友好）
// ============================================================

/**
 * 统一事件。sealed interface 确保编译期就知道所有可能的事件类型。
 *
 * 设计决策：为什么不用泛型 Event<T>？
 * - 泛型事件在 pattern matching 时需要擦除，丢失类型安全
 * - sealed interface + record 让 switch 表达式穷举所有类型
 * - IDE 可以自动补全所有 case，不会遗漏
 *
 * 为什么不用继承？
 * - record 不能继承类，只能实现接口
 * - sealed interface + permits 让编译器强制穷举
 */
public sealed interface AgentEvent
    permits
    // 生命周期事件
    ExecutionStartedEvent, ExecutionCompletedEvent, ExecutionFailedEvent,
    // Plan 阶段事件
    PlanGeneratedEvent, PlanApprovedEvent, PlanModifiedEvent, PlanRejectedEvent,
    // Execute 阶段事件
    StepStartedEvent, StepCompletedEvent, StepFailedEvent, StepSkippedEvent,
    // 工具事件
    ToolCallStartedEvent, ToolCallCompletedEvent, ToolCallFailedEvent,
    // 审批事件
    ApprovalRequiredEvent, ApprovalGrantedEvent, ApprovalDeniedEvent,
    // 约束事件
    ConstraintViolatedEvent,
    // 验证事件
    VerificationPassedEvent, VerificationFailedEvent,
    // 子 Agent 事件
    SubAgentStartedEvent, SubAgentCompletedEvent,
    // 检查点事件
    CheckpointSavedEvent {

    String executionId();
    Instant timestamp();
}

// --- 生命周期事件 ---

public record ExecutionStartedEvent(
    String executionId, Instant timestamp,
    String agentName, String userInput
) implements AgentEvent {}

public record ExecutionCompletedEvent(
    String executionId, Instant timestamp,
    String output, Duration duration, TokenUsage tokenUsage
) implements AgentEvent {}

public record ExecutionFailedEvent(
    String executionId, Instant timestamp,
    String error, String failedStepId, Duration duration
) implements AgentEvent {}

// --- Plan 阶段事件 ---

public record PlanGeneratedEvent(
    String executionId, Instant timestamp,
    TaskGraph plan, int nodeCount, int layerCount
) implements AgentEvent {}

public record PlanApprovedEvent(
    String executionId, Instant timestamp,
    String reviewer, String comment
) implements AgentEvent {}

public record PlanModifiedEvent(
    String executionId, Instant timestamp,
    TaskGraph originalPlan, TaskGraph modifiedPlan, String modifier
) implements AgentEvent {}

public record PlanRejectedEvent(
    String executionId, Instant timestamp,
    String reviewer, String reason
) implements AgentEvent {}

// --- Execute 阶段事件 ---

public record StepStartedEvent(
    String executionId, Instant timestamp,
    String stepId, String nodeId, String description
) implements AgentEvent {}

public record StepCompletedEvent(
    String executionId, Instant timestamp,
    String stepId, String nodeId, Object result, Duration duration
) implements AgentEvent {}

public record StepFailedEvent(
    String executionId, Instant timestamp,
    String stepId, String nodeId, String error, Duration duration
) implements AgentEvent {}

public record StepSkippedEvent(
    String executionId, Instant timestamp,
    String stepId, String nodeId, String reason
) implements AgentEvent {}

// --- 工具事件 ---

public record ToolCallStartedEvent(
    String executionId, Instant timestamp,
    String callId, String toolName, Map<String, Object> arguments
) implements AgentEvent {}

public record ToolCallCompletedEvent(
    String executionId, Instant timestamp,
    String callId, String toolName, Object result
) implements AgentEvent {}

public record ToolCallFailedEvent(
    String executionId, Instant timestamp,
    String callId, String toolName, String error
) implements AgentEvent {}

// --- 审批事件 ---

public record ApprovalRequiredEvent(
    String executionId, Instant timestamp,
    String gateId, ApprovalGate gate, PlanStep awaitingStep
) implements AgentEvent {}

public record ApprovalGrantedEvent(
    String executionId, Instant timestamp,
    String gateId, ApprovalDecision decision
) implements AgentEvent {}

public record ApprovalDeniedEvent(
    String executionId, Instant timestamp,
    String gateId, ApprovalDecision decision
) implements AgentEvent {}

// --- 约束事件 ---

public record ConstraintViolatedEvent(
    String executionId, Instant timestamp,
    String constraintName, String violation, String stepId
) implements AgentEvent {}

// --- 验证事件 ---

public record VerificationPassedEvent(
    String executionId, Instant timestamp,
    String feedback, TokenUsage tokenUsage
) implements AgentEvent {}

public record VerificationFailedEvent(
    String executionId, Instant timestamp,
    String feedback, Set<String> failedNodeIds, int retryCount, int maxRetries
) implements AgentEvent {}

// --- 子 Agent 事件 ---

public record SubAgentStartedEvent(
    String executionId, Instant timestamp,
    String subTaskId, String subAgentName
) implements AgentEvent {}

public record SubAgentCompletedEvent(
    String executionId, Instant timestamp,
    String subTaskId, String subAgentName, Object result
) implements AgentEvent {}

// --- 检查点事件 ---

public record CheckpointSavedEvent(
    String executionId, Instant timestamp,
    String checkpointId, int sequenceNumber, ExecutionPhase phase
) implements AgentEvent {}

/**
 * 事件监听器。开发者实现此接口来处理事件。
 *
 * 为什么用接口而不是注解？
 * - 接口有类型安全，注解在编译期不检查方法签名
 * - 接口可以用 default 方法提供空实现，开发者只覆写关心的
 * - 接口可以组合（多个监听器组合成一个）
 */
public interface AgentEventListener {

    /** 处理事件。返回 true 表示消费了事件（停止传播），false 表示继续传播 */
    default boolean onEvent(AgentEvent event) { return false; }

    /** 按类型处理。利用 pattern matching 穷举 */
    default void onTypedEvent(AgentEvent event) {
        switch (event) {
            case ExecutionStartedEvent e     -> onExecutionStarted(e);
            case ExecutionCompletedEvent e   -> onExecutionCompleted(e);
            case ExecutionFailedEvent e      -> onExecutionFailed(e);
            case PlanGeneratedEvent e        -> onPlanGenerated(e);
            case PlanApprovedEvent e         -> onPlanApproved(e);
            case PlanModifiedEvent e         -> onPlanModified(e);
            case PlanRejectedEvent e         -> onPlanRejected(e);
            case StepStartedEvent e          -> onStepStarted(e);
            case StepCompletedEvent e        -> onStepCompleted(e);
            case StepFailedEvent e           -> onStepFailed(e);
            case StepSkippedEvent e          -> onStepSkipped(e);
            case ToolCallStartedEvent e      -> onToolCallStarted(e);
            case ToolCallCompletedEvent e    -> onToolCallCompleted(e);
            case ToolCallFailedEvent e       -> onToolCallFailed(e);
            case ApprovalRequiredEvent e     -> onApprovalRequired(e);
            case ApprovalGrantedEvent e      -> onApprovalGranted(e);
            case ApprovalDeniedEvent e       -> onApprovalDenied(e);
            case ConstraintViolatedEvent e   -> onConstraintViolated(e);
            case VerificationPassedEvent e   -> onVerificationPassed(e);
            case VerificationFailedEvent e   -> onVerificationFailed(e);
            case SubAgentStartedEvent e      -> onSubAgentStarted(e);
            case SubAgentCompletedEvent e    -> onSubAgentCompleted(e);
            case CheckpointSavedEvent e      -> onCheckpointSaved(e);
        }
    }

    // 每个事件的 default 空实现
    default void onExecutionStarted(ExecutionStartedEvent e) {}
    default void onExecutionCompleted(ExecutionCompletedEvent e) {}
    default void onExecutionFailed(ExecutionFailedEvent e) {}
    default void onPlanGenerated(PlanGeneratedEvent e) {}
    default void onPlanApproved(PlanApprovedEvent e) {}
    default void onPlanModified(PlanModifiedEvent e) {}
    default void onPlanRejected(PlanRejectedEvent e) {}
    default void onStepStarted(StepStartedEvent e) {}
    default void onStepCompleted(StepCompletedEvent e) {}
    default void onStepFailed(StepFailedEvent e) {}
    default void onStepSkipped(StepSkippedEvent e) {}
    default void onToolCallStarted(ToolCallStartedEvent e) {}
    default void onToolCallCompleted(ToolCallCompletedEvent e) {}
    default void onToolCallFailed(ToolCallFailedEvent e) {}
    default void onApprovalRequired(ApprovalRequiredEvent e) {}
    default void onApprovalGranted(ApprovalGrantedEvent e) {}
    default void onApprovalDenied(ApprovalDeniedEvent e) {}
    default void onConstraintViolated(ConstraintViolatedEvent e) {}
    default void onVerificationPassed(VerificationPassedEvent e) {}
    default void onVerificationFailed(VerificationFailedEvent e) {}
    default void onSubAgentStarted(SubAgentStartedEvent e) {}
    default void onSubAgentCompleted(SubAgentCompletedEvent e) {}
    default void onCheckpointSaved(CheckpointSavedEvent e) {}
}
```

### 1.6 Runtime 引擎（带控制点的 PEV 执行器）

```java
package com.openjiuwen.java.runtime;

import com.openjiuwen.java.core.*;

// ============================================================
// Runtime 核心执行引擎
// ============================================================

/**
 * PEV 引擎。Plan-Execute-Verify 的执行器。
 *
 * 关键设计：这个类本身是无状态的。
 * 所有状态都在 AgentContext 里，每次方法调用传入传出。
 * 这意味着：
 * 1. 线程安全——没有共享可变状态
 * 2. 可测试——输入 AgentContext，输出 AgentContext，纯函数
 * 3. 可调试——任何时刻的 AgentContext 都是完整的快照
 */
public final class PevEngine {

    private final Planner planner;
    private final Executor executor;
    private final Verifier verifier;
    private final ConstraintChecker constraintChecker;
    private final ApprovalManager approvalManager;
    private final CheckpointManager checkpointManager;
    private final EventBus eventBus;

    public PevEngine(
        Planner planner,
        Executor executor,
        Verifier verifier,
        ConstraintChecker constraintChecker,
        ApprovalManager approvalManager,
        CheckpointManager checkpointManager,
        EventBus eventBus
    ) {
        this.planner = planner;
        this.executor = executor;
        this.verifier = verifier;
        this.constraintChecker = constraintChecker;
        this.approvalManager = approvalManager;
        this.checkpointManager = checkpointManager;
        this.eventBus = eventBus;
    }

    /**
     * 执行入口。完整的 PEV 生命周期。
     *
     * 返回最终的 AgentContext，包含所有步骤的结果。
     * 不抛异常——错误通过 AgentContext 的 ExecutionPhase.FAILED 表达。
     */
    public AgentContext execute(AgentContext ctx) {
        try {
            ctx = ctx.withPhase(ExecutionPhase.CREATED);
            eventBus.emit(new ExecutionStartedEvent(
                ctx.executionId(), Instant.now(),
                ctx.agent().name(), ctx.userInput()
            ));

            // ========== Phase 1: Plan ==========
            ctx = ctx.withPhase(ExecutionPhase.PLANNING);
            PlanResult planResult = planner.plan(ctx);
            eventBus.emit(new PlanGeneratedEvent(
                ctx.executionId(), Instant.now(),
                planResult.graph(),
                planResult.graph().nodes().size(),
                planResult.graph().executionLayers().size()
            ));

            ctx = ctx.withPlan(planResult.graph());

            // 控制点：Plan 审批
            ctx = handlePlanApproval(ctx);

            if (ctx.phase() == ExecutionPhase.CANCELLED) {
                return ctx;
            }

            // ========== Phase 2: Execute ==========
            ctx = ctx.withPhase(ExecutionPhase.EXECUTING);
            ctx = executeTaskGraph(ctx);

            if (ctx.phase() == ExecutionPhase.FAILED) {
                return ctx;
            }

            // ========== Phase 3: Verify ==========
            ctx = ctx.withPhase(ExecutionPhase.VERIFYING);
            ctx = verifyAndRecover(ctx);

            // 完成
            if (ctx.phase() == ExecutionPhase.COMPLETED) {
                String output = assembleOutput(ctx);
                eventBus.emit(new ExecutionCompletedEvent(
                    ctx.executionId(), Instant.now(),
                    output, Duration.between(ctx.startedAt(), Instant.now()),
                    ctx.tokenUsage()
                ));
            }

            return ctx;

        } catch (Exception e) {
            eventBus.emit(new ExecutionFailedEvent(
                ctx.executionId(), Instant.now(),
                e.getMessage(), "", Duration.between(ctx.startedAt(), Instant.now())
            ));
            return ctx.withPhase(ExecutionPhase.FAILED);
        }
    }

    /**
     * 从检查点恢复执行。
     * 开发者显式控制恢复点。
     */
    public AgentContext resume(Checkpoint checkpoint) {
        AgentContext ctx = checkpoint.snapshot();

        return switch (checkpoint.phase()) {
            case PLANNING     -> execute(ctx.withPhase(ExecutionPhase.PLANNING));
            case PLAN_REVIEW  -> { ctx = handlePlanApproval(ctx); yield executeFromPhase(ctx); }
            case EXECUTING    -> executeTaskGraph(ctx);
            case VERIFYING    -> verifyAndRecover(ctx);
            case REVIEWING    -> { ctx = handleVerifyReview(ctx); yield executeFromPhase(ctx); }
            default           -> execute(ctx);
        };
    }

    // ---------- 内部方法 ----------

    /**
     * Plan 阶段的审批控制点。
     */
    private AgentContext handlePlanApproval(AgentContext ctx) {
        PlanningMode mode = ctx.agent().policy().planningMode();

        return switch (mode) {
            case AUTO -> {
                // 自动批准，直接进入执行
                yield ctx;
            }
            case SEMI_AUTO -> {
                // 暂停等待开发者审批
                ctx = ctx.withPhase(ExecutionPhase.PLAN_REVIEW);
                // 保存检查点
                checkpointManager.save(ctx, CheckpointReason.BEFORE_APPROVAL);

                // 阻塞等待审批（实际实现可以是异步回调）
                ApprovalDecision decision = approvalManager.awaitApproval(
                    ctx.executionId(),
                    findApprovalGate(ctx, ApprovalTrigger.ON_PLAN_COMPLETE)
                );

                yield switch (decision) {
                    case Approved a -> {
                        eventBus.emit(new PlanApprovedEvent(
                            ctx.executionId(), Instant.now(),
                            a.reviewer(), a.comment()));
                        yield ctx;
                    }
                    case Rejected r -> {
                        eventBus.emit(new PlanRejectedEvent(
                            ctx.executionId(), Instant.now(),
                            r.reviewer(), r.reason()));
                        yield ctx.withPhase(ExecutionPhase.CANCELLED);
                    }
                    case Escalated e -> {
                        // 升级给上级审批人
                        yield handlePlanApproval(ctx);
                    }
                };
            }
            case MANUAL -> {
                // 开发者预定义了 TaskGraph，不需要 LLM 规划
                // 此处跳过（TaskGraph 已经在 ctx 中）
                yield ctx;
            }
        };
    }

    /**
     * 执行 TaskGraph。逐层执行，同层并行。
     */
    private AgentContext executeTaskGraph(AgentContext ctx) {
        TaskGraph graph = ctx.currentPlan();
        if (graph == null) {
            return ctx.withPhase(ExecutionPhase.FAILED);
        }

        List<List<TaskNode>> layers = graph.executionLayers();
        AgentContext currentCtx = ctx;

        for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
            List<TaskNode> layer = layers.get(layerIdx);

            // 控制点：并行度限制
            int parallelism = Math.min(
                layer.size(),
                ctx.agent().policy().maxParallelism()
            );

            // 使用虚拟线程并行执行同层节点
            List<AgentContext> layerResults = executeLayer(currentCtx, layer, parallelism);

            // 合并结果
            for (AgentContext partialCtx : layerResults) {
                for (var entry : partialCtx.variables().entrySet()) {
                    currentCtx = currentCtx.withVariable(entry.getKey(), entry.getValue());
                }
                currentCtx = currentCtx.withTokenUsage(partialCtx.tokenUsage());
            }

            // 保存层完成检查点
            if (ctx.agent().policy().enableCheckpoints()) {
                checkpointManager.save(currentCtx, CheckpointReason.LAYER_COMPLETE);
            }
        }

        return currentCtx;
    }

    /**
     * 并行执行一层节点。每个节点一个虚拟线程。
     */
    private List<AgentContext> executeLayer(
        AgentContext ctx, List<TaskNode> nodes, int parallelism
    ) {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<AgentContext>> futures = new java.util.ArrayList<>();

            for (TaskNode node : nodes) {
                futures.add(executor.submit(() -> executeNode(ctx, node)));
            }

            return futures.stream()
                .map(f -> {
                    try { return f.get(ctx.agent().policy().stepTimeout().toMillis(),
                                       java.util.concurrent.TimeUnit.MILLISECONDS); }
                    catch (Exception e) { return ctx.withPhase(ExecutionPhase.FAILED); }
                })
                .toList();
        }
    }

    /**
     * 执行单个节点。带约束检查和审批门。
     */
    private AgentContext executeNode(AgentContext ctx, TaskNode node) {
        String stepId = java.util.UUID.randomUUID().toString();

        eventBus.emit(new StepStartedEvent(
            ctx.executionId(), Instant.now(), stepId, node.id(), node.description()
        ));

        // 解析输入参数（可能引用上游输出）
        Map<String, Object> resolvedArgs = resolveInputs(node.inputs(), ctx.variables());

        Object result = switch (node.type()) {
            case TOOL_CALL -> executeToolCall(ctx, node, resolvedArgs, stepId);
            case LLM_CALL  -> executeLLMCall(ctx, node, resolvedArgs);
            case SUB_AGENT -> executeSubAgent(ctx, node);
        };

        AgentContext newCtx = ctx.withVariable(node.id(), result);

        eventBus.emit(new StepCompletedEvent(
            ctx.executionId(), Instant.now(), stepId, node.id(), result,
            Duration.ZERO
        ));

        return newCtx;
    }

    /**
     * 执行工具调用。带约束检查 + 审批门。
     */
    private Object executeToolCall(
        AgentContext ctx, TaskNode node,
        Map<String, Object> args, String stepId
    ) {
        ToolCall call = node.toolCall().orElseThrow();

        // 1. 约束检查
        for (Constraint constraint : ctx.agent().constraints()) {
            // 构造临时 PlanStep 用于约束检查
            var tempStep = new ToolExecutionStep(
                stepId, ctx.executionId(), Instant.now(), null,
                StepStatus.PENDING, call, null, null, null
            );
            ConstraintResult check = constraint.check(ctx, tempStep);
            if (!check.satisfied()) {
                eventBus.emit(new ConstraintViolatedEvent(
                    ctx.executionId(), Instant.now(),
                    constraint.name(), check.message(), stepId
                ));
                throw new ConstraintViolationException(constraint.name(), check.message());
            }
        }

        // 2. 审批门检查
        Optional<ApprovalGate> gate = findToolApprovalGate(ctx, call.toolName());
        if (gate.isPresent()) {
            eventBus.emit(new ApprovalRequiredEvent(
                ctx.executionId(), Instant.now(),
                gate.get().gateId(), gate.get(), null
            ));

            ApprovalDecision decision = approvalManager.awaitApproval(
                ctx.executionId(), gate.get()
            );

            if (decision instanceof Rejected r) {
                eventBus.emit(new ApprovalDeniedEvent(
                    ctx.executionId(), Instant.now(), gate.get().gateId(), r));
                throw new ApprovalDeniedException(gate.get().gateId(), r.reason());
            }

            eventBus.emit(new ApprovalGrantedEvent(
                ctx.executionId(), Instant.now(), gate.get().gateId(), decision));
        }

        // 3. 执行工具
        eventBus.emit(new ToolCallStartedEvent(
            ctx.executionId(), Instant.now(), call.callId(), call.toolName(), args
        ));

        ToolResult result = executor.executeTool(call, args);

        if (result.success()) {
            eventBus.emit(new ToolCallCompletedEvent(
                ctx.executionId(), Instant.now(), call.callId(), call.toolName(), result.data()
            ));
            return result.data();
        } else {
            eventBus.emit(new ToolCallFailedEvent(
                ctx.executionId(), Instant.now(), call.callId(), call.toolName(), result.errorMessage()
            ));
            // 应用错误策略
            ErrorDecision decision = ctx.agent().errorPolicy().handleError(
                new ErrorContext(ctx.agent(), null, new RuntimeException(result.errorMessage()), 0, List.of())
            );
            return switch (decision) {
                case RetryDecision d    -> retryToolCall(ctx, node, args, stepId, d);
                case SkipDecision d     -> null;
                case AbortDecision d    -> throw new ExecutionAbortedException(d.reason());
                case FallbackDecision d -> executor.executeTool(
                    new ToolCall(call.callId(), d.fallbackTool(), d.fallbackArgs()), d.fallbackArgs()
                );
            };
        }
    }

    // executeLLMCall、executeSubAgent、verifyAndRecover 等方法
    // 省略但接口完整——这里只展示关键路径

    private Object executeLLMCall(AgentContext ctx, TaskNode node, Map<String, Object> args) {
        return executor.executeLLM(node.description(), args, ctx.agent().systemPrompt());
    }

    private Object executeSubAgent(AgentContext ctx, TaskNode node) {
        String subAgent = node.subAgentName().orElseThrow();
        String subTaskId = java.util.UUID.randomUUID().toString();

        eventBus.emit(new SubAgentStartedEvent(
            ctx.executionId(), Instant.now(), subTaskId, subAgent
        ));

        // 递归执行子 Agent
        AgentContext subCtx = AgentContext.initial(subTaskId, ctx.agent(), node.description());
        subCtx = this.execute(subCtx);

        eventBus.emit(new SubAgentCompletedEvent(
            ctx.executionId(), Instant.now(), subTaskId, subAgent,
            subCtx.variables()
        ));

        return subCtx.variables();
    }

    // 辅助方法
    private AgentContext handleVerifyReview(AgentContext ctx) { return ctx; }
    private AgentContext executeFromPhase(AgentContext ctx) { return ctx; }
    private Object retryToolCall(AgentContext ctx, TaskNode node,
                                  Map<String, Object> args, String stepId, RetryDecision d) {
        return null; // 实现重试逻辑
    }
    private String assembleOutput(AgentContext ctx) { return ctx.variables().toString(); }
    private AgentContext verifyAndRecover(AgentContext ctx) { return ctx; }
    private Optional<ApprovalGate> findApprovalGate(AgentContext ctx, ApprovalTrigger trigger) {
        return ctx.agent().approvalGates().stream()
            .filter(g -> g.trigger() == trigger).findFirst();
    }
    private Optional<ApprovalGate> findToolApprovalGate(AgentContext ctx, String toolName) {
        return ctx.agent().approvalGates().stream()
            .filter(g -> g.trigger() == ApprovalTrigger.ON_TOOL_CALL && g.toolName().equals(toolName))
            .findFirst();
    }
    private Map<String, Object> resolveInputs(Map<String, String> inputs, Map<String, Object> vars) {
        Map<String, Object> resolved = new java.util.HashMap<>();
        for (var entry : inputs.entrySet()) {
            String value = entry.getValue();
            if (value.startsWith("${") && value.endsWith("}")) {
                String ref = value.substring(2, value.length() - 1);
                String[] parts = ref.split("\\.");
                Object obj = vars.get(parts[0]);
                if (obj instanceof Map m && parts.length > 1) {
                    resolved.put(entry.getKey(), m.get(parts[1]));
                } else {
                    resolved.put(entry.getKey(), obj);
                }
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}

// ============================================================
// Runtime 组件接口
// ============================================================

/**
 * 规划器。LLM 分析目标，输出 TaskGraph。
 * 在变体 Alpha 中，规划器的输出是"建议"，不是"决定"。
 */
public interface Planner {
    PlanResult plan(AgentContext ctx);
    PlanResult replan(AgentContext ctx, TaskGraph original, Set<String> failedNodes, String feedback);
}

public record PlanResult(TaskGraph graph, int tokenCost, Duration duration) {}

/**
 * 执行器。实际的工具调用和 LLM 调用。
 */
public interface Executor {
    ToolResult executeTool(ToolCall call, Map<String, Object> args);
    String executeLLM(String prompt, Map<String, Object> context, SystemPrompt systemPrompt);
}

/**
 * 验证器。LLM 检查结果质量。
 */
public interface Verifier {
    VerifyResult verify(AgentContext ctx, String output);
}

public record VerifyResult(
    boolean passed, String feedback, Set<String> failedNodeIds
) {}

/**
 * 约束检查器。批量检查所有约束。
 */
public interface ConstraintChecker {
    List<ConstraintResult> checkAll(AgentContext ctx, PlanStep step);
}

/**
 * 审批管理器。处理审批门的等待和超时。
 */
public interface ApprovalManager {
    ApprovalDecision awaitApproval(String executionId, ApprovalGate gate);
    void grant(String executionId, String gateId, ApprovalDecision decision);
}

/**
 * 检查点管理器。协调 AgentContext 的快照和恢复。
 */
public interface CheckpointManager {
    Checkpoint save(AgentContext ctx, CheckpointReason reason);
    Optional<Checkpoint> loadLatest(String executionId);
    AgentContext restore(Checkpoint checkpoint);
}

/**
 * 事件总线。同步或异步分发事件给所有监听器。
 */
public interface EventBus {
    void emit(AgentEvent event);
    void addListener(AgentEventListener listener);
    void removeListener(AgentEventListener listener);
}

// ============================================================
// 自定义异常
// ============================================================

public final class ConstraintViolationException extends RuntimeException {
    public final String constraintName;
    public ConstraintViolationException(String name, String msg) {
        super(name + ": " + msg);
        this.constraintName = name;
    }
}

public final class ApprovalDeniedException extends RuntimeException {
    public final String gateId;
    public ApprovalDeniedException(String gateId, String reason) {
        super("Approval denied at gate " + gateId + ": " + reason);
        this.gateId = gateId;
    }
}

public final class ExecutionAbortedException extends RuntimeException {
    public ExecutionAbortedException(String reason) { super(reason); }
}
```

### 1.7 开发者使用示例

```java
package com.openjiuwen.java.example;

import com.openjiuwen.java.core.*;

/**
 * 变体 Alpha 的开发者体验：
 * 开发者用代码（而不是配置文件）精确控制 Agent 行为。
 * 一切都是显式的，没有魔法。
 */
public class OrderAgentDefinition {

    /**
     * 定义一个订单处理 Agent。
     * 每一行都是有意义的——没有隐式约定。
     */
    public static AgentDefinition create() {
        return new AgentDefinition(
            // 身份
            "order-processor",
            "企业订单处理 Agent，支持查询、退款、地址修改",
            "1.0.0",

            // 系统提示（静态）
            new StaticPrompt("""
                你是订单处理助手。
                规则：
                1. 涉及金额操作必须先确认
                2. 只处理最近 90 天的订单
                3. 超过 5000 元的退款需要主管审批
                """),

            // 可用工具
            List.of(
                new ToolReference("queryOrder", "查询订单状态", null, Map.of(
                    "orderId", new ParameterSchema("orderId", "string", "订单号", true, "")
                )),
                new ToolReference("refund", "申请退款", null, Map.of(
                    "orderId", new ParameterSchema("orderId", "string", "订单号", true, ""),
                    "reason",  new ParameterSchema("reason", "string", "退款原因", true, "")
                )),
                new ToolReference("changeAddress", "修改收货地址", null, Map.of(
                    "orderId",    new ParameterSchema("orderId", "string", "订单号", true, ""),
                    "newAddress", new ParameterSchema("newAddress", "string", "新地址", true, "")
                ))
            ),

            // 执行策略：半自动规划，LLM 验证，保守并行
            new ExecutionPolicy(
                10,                     // 最多 10 轮迭代
                4,                      // 最多 4 个并行节点
                2,                      // 最多重试 2 次
                Duration.ofSeconds(30), // 单步 30 秒超时
                Duration.ofMinutes(5),  // 总共 5 分钟超时
                PlanningMode.SEMI_AUTO, // LLM 规划，但需要开发者审批
                VerifyMode.LLM_AUTO,    // LLM 自动验证
                true,                   // 启用检查点
                Duration.ofSeconds(5)   // 每 5 秒一个检查点
            ),

            // 约束：只允许调用这三个工具
            List.of(
                new ToolWhitelist(Set.of("queryOrder", "refund", "changeAddress")),
                new CostConstraint(50000, 10000)  // token 预算
            ),

            // 审批门：退款操作需要人工确认
            List.of(
                new ApprovalGate(
                    "refund-approval",
                    ApprovalTrigger.ON_TOOL_CALL,
                    "refund",
                    "退款操作需要人工确认",
                    Duration.ofMinutes(10),
                    ApprovalAction.ESCALATE
                )
            ),

            // 错误策略：重试 2 次后降级
            new DegradePolicy("queryOrder", 2)
        );
    }
}

/**
 * 使用示例：在 Spring Boot 中运行
 */
// @Service
class OrderAgentService {

    private final PevEngine engine;

    // @Autowired
    OrderAgentService(PevEngine engine) {
        this.engine = engine;
    }

    /**
     * 处理订单请求。
     * 完全同步，完全可预测。
     */
    public Map<String, Object> processOrder(String userInput) {
        AgentDefinition agent = OrderAgentDefinition.create();
        AgentContext ctx = AgentContext.initial(
            java.util.UUID.randomUUID().toString(), agent, userInput
        );

        // 执行——引擎严格遵守开发者的定义
        AgentContext result = engine.execute(ctx);

        return switch (result.phase()) {
            case COMPLETED -> Map.of("status", "success", "data", result.variables());
            case FAILED    -> Map.of("status", "failed", "error", "执行失败");
            case CANCELLED -> Map.of("status", "cancelled", "reason", "审批被拒绝");
            default        -> Map.of("status", result.phase().name());
        };
    }

    /**
     * 从检查点恢复。
     * 开发者完全控制恢复逻辑。
     */
    public Map<String, Object> resumeFromCheckpoint(String executionId) {
        Optional<Checkpoint> cp = engine.getCheckpointManager().loadLatest(executionId);
        if (cp.isEmpty()) {
            return Map.of("error", "No checkpoint found");
        }

        AgentContext result = engine.resume(cp.get());
        return Map.of("status", result.phase().name(), "data", result.variables());
    }
}
```

---

## 二、苏格拉底式自我诘问

### Q1: 为什么把 AgentDefinition 设计成不可变 record，而不是可变的 Builder 模式？

**反面论证**：Builder 模式更灵活。开发者可以在运行时动态修改 Agent 配置。比如根据用户角色动态调整工具白名单。

**替代方案**：用 `AgentDefinition.builder().name("x").build()` 风格。

**3 个月后的灾难**：如果 AgentDefinition 是可变的，运行时某个 bug 或并发问题悄悄改了白名单，安全边界就形同虚设。企业合规审计时发现"白名单在执行过程中被改了"，这不是 bug 是事故。

**为什么坚持 record**：不可变不是限制，是保护。动态需求用 `AgentDefinition -> AgentDefinition` 的转换函数解决，而不是直接 mutate。

### Q2: 为什么用 sealed interface 定义事件，而不是用 Class 继承 + instanceof？

**反面论证**：sealed interface 强制所有实现都在同一个包里。如果第三方想扩展自定义事件怎么办？

**替代方案**：开放继承体系，用 `Event` 基类 + `instanceof` 检查。

**3 个月后的灾难**：开放继承意味着 switch 语句永远不可能穷举。新增一种事件类型，所有 switch 的地方都不会编译报错，静默地走了 default 分支。企业场景中，漏处理 `ConstraintViolatedEvent` 意味着合规违规被吞掉了。

**为什么坚持 sealed interface**：编译期穷举比运行时发现好一万倍。第三方扩展需求通过 `AgentEventListener.onEvent(AgentEvent)` 的 fallback 方法满足，不需要新事件类型。

### Q3: 为什么 AgentContext 每次 with* 方法都创建新对象，而不是原地修改？

**反面论证**：每次创建新 record 对象的内存开销。1000 个并发 Agent，每个 15 步，就是 15000 个 AgentContext 对象。

**替代方案**：用可变 Builder，只在检查点时做快照。

**3 个月后的灾难**：可变状态在虚拟线程并发环境中会导致 Heisenbug——只在特定时序下复现的 bug。检查点快照的是"某个时刻的状态"，但如果状态在快照过程中被另一个线程修改了，快照就是损坏的。

**为什么坚持不可变**：GC 对短生命周期对象有专门优化（ZGC/Shenandoah）。15000 个 record 对象的压力远小于排查一个并发 bug 的成本。不可变 = 任何时候任何线程看到的状态都是自洽的。

### Q4: 为什么审批门是声明式的（record），而不是命令式的（回调函数）？

**反面论证**：回调函数可以写任意审批逻辑——检查金额、检查时间、检查用户权限。record 只能声明固定字段。

**替代方案**：`ApprovalGate` 是一个函数式接口 `Function<PlanStep, ApprovalDecision>`。

**3 个月后的灾难**：命令式审批逻辑无法序列化，无法跨 JVM 传递（SDK -> Runtime），无法在 UI 上展示审批规则，无法做静态分析检查审批覆盖度。

**为什么坚持声明式**：声明式 = 可序列化 = 可审计 = 可在 UI 展示。复杂审批逻辑放在 `ApprovalManager` 的实现里，不在 Gate 定义里。Gate 只声明"在哪触发"，Manager 决定"怎么审批"。

### Q5: 为什么 TaskGraph 构造时就检测环，而不是执行时？

**反面论证**：构造时检测环增加了 TaskGraph 的构造成本。如果 LLM 生成的 JSON 需要快速解析、快速反馈，额外的校验是延迟。

**替代方案**：`executionLayers()` 在执行时检测环，报 `IllegalStateException`。

**3 个月后的灾难**：LLM 幻觉生成了环形依赖。如果环检测延迟到执行时，Agent 可能已经执行了一半才发现死循环。已经执行的工具调用无法回滚。如果涉及退款操作，钱已经退了，但任务图是错的。

**为什么坚持构造时检测**：fail-fast 原则。环是致命错误，必须在最早的时刻发现。构造时的额外延迟是微秒级的（拓扑排序是 O(V+E)），不值得为此牺牲安全性。

### Q6: 为什么把 ConstraintChecker 和 ApprovalManager 分离，而不是合并为一个 GuardManager？

**反面论证**：两者都是"执行前的检查"，逻辑上是一类的。合并减少组件数量。

**替代方案**：`GuardManager.checkAndApprove(ctx, step)` 一个方法搞定。

**3 个月后的灾难**：约束和审批的生命周期不同。约束是无状态的、确定性的、必须通过的。审批是有状态的、可能需要人工的、可能被拒绝的。合并后，异步审批和同步约束检查耦合在一起。审批超时导致约束检查也被挂起，或者约束失败触发了不必要的审批流程。

**为什么分离**：关注点分离。约束 = 机器自动执行的规则。审批 = 可能需要人参与的决策。两者失败的处理方式完全不同（约束失败 = 中止，审批拒绝 = 取消或升级）。

### Q7: 为什么 ErrorPolicy 是 sealed interface 而不是 enum？

**反面论证**：`ErrorPolicy` 只有三种实现（FailFast / Retry / Degrade），用 enum 更简单。

**替代方案**：`enum ErrorPolicy { FAIL_FAST, RETRY, DEGRADE }`。

**3 个月后的灾难**：企业开发者需要"根据错误类型选择不同策略"——工具超时用重试，权限错误用降级，数据不一致用快速失败。enum 没有状态，无法表达"重试几次"、"退避多久"、"降级到哪个工具"。强行把参数塞进 enum constructor 会导致 enum 膨胀到 20+ 实例。

**为什么坚持 sealed interface**：record 可以携带状态（重试次数、退避策略、降级目标），enum 不行。sealed interface 保证编译期知道所有实现，enum 也行，但 enum 不能有不同形状的数据。

---

## 三、极端场景压力测试

### 场景 A：1000 个并发 Agent，200 个同时触发子 Agent

**执行轨迹**：

```
[2026-06-07T10:00:00.000] Runtime 收到 1000 个 Agent 执行请求
[2026-06-07T10:00:00.001] PevEngine.execute() 被调用 1000 次（1000 个虚拟线程）
[2026-06-07T10:00:00.002] 每个 Agent 进入 PLANNING 阶段
[2026-06-07T10:00:00.003] 1000 个 Planner.plan() 并发调用 ChatModel
                         ↓
[2026-06-07T10:00:00.004] *** 瓶颈 1: ChatModel API 限流 ***
                         1000 个并发请求 → API 返回 429 Too Many Requests
                         ↓
[2026-06-07T10:00:00.005] RetryPolicy 触发指数退避
                         第 1 轮重试：所有请求等待 1 秒
                         第 2 轮重试：所有请求等待 2 秒
                         第 3 轮重试：所有请求等待 4 秒
                         ...
                         ↓
[2026-06-07T10:00:05.000] 1000 个 Plan 完成，进入 PLAN_REVIEW（SEMI_AUTO 模式）
                         ↓
[2026-06-07T10:00:05.001] *** 瓶颈 2: 审批队列积压 ***
                         1000 个审批请求同时进入 ApprovalManager
                         假设 1 个人类审批者，每分钟处理 5 个
                         1000 / 5 = 200 分钟 = 3.3 小时
                         但审批超时是 10 分钟
                         ↓
[2026-06-07T10:00:15.001] 前 50 个被人工审批
                         剩下 950 个超时 → ApprovalAction.ESCALATE
                         升级到主管队列 → 再次超时 → REJECT
                         ↓
[2026-06-07T10:00:15.002] 950 个 Agent 被取消，50 个进入 EXECUTING
                         其中 200 个 Agent 的 TaskGraph 包含 SUB_AGENT 节点
                         但只有 50 个 Agent 还活着
                         → 最多 50 个可能触发子 Agent
                         ↓
[2026-06-07T10:00:15.010] 50 个 Agent 进入 EXECUTING
                         每个最多 4 个并行节点 → 最多 200 个并行虚拟线程
                         其中 10 个触发 SUB_AGENT → 递归 PevEngine.execute()
                         ↓
[2026-06-07T10:00:15.011] 子 Agent 再次进入 PLANNING
                         ChatModel 再次被调用（但只有 10 个，限流没问题）
                         ↓
[2026-06-07T10:00:20.000] 子 Agent Plan 完成，进入 EXECUTING
                         并发度：50 父 + 10 子 = 60 个活跃执行
                         虚拟线程总数：~240（60 * 4 parallelism）
                         内存：每个 AgentContext 约 5KB，总计 ~1.2MB
                         ↓
[2026-06-07T10:00:45.000] 大部分 Agent 执行完成
                         进入 VERIFYING → 通过 → COMPLETED
                         ↓
[2026-06-07T10:00:45.100] CheckpointStore 写入完成检查点
                         Redis：60 个 INCR + SET 操作，耗时 ~6ms
                         ↓
[2026-06-07T10:00:45.200] 50 个 Agent COMPLETED, 950 个 CANCELLED

最终结果：
- 成功：50/1000 (5%)
- 失败原因分布：
  - 审批超时被取消：900 (90%)
  - 执行中工具超时：40 (4%)
  - 验证失败重试耗尽：10 (1%)
- 总耗时：45.2 秒
- ChatModel API 调用次数：~1120（1000 plan + 50 verify + 10 sub-plan + 10 sub-verify + 50 retry）
- Token 消耗：约 1.12M input + 560K output
```

**诊断**：系统没有因为并发崩溃——虚拟线程和不可变状态保证了这一点。但 95% 的请求因为审批瓶颈被取消了。

**修复方向**：对大规模并发，PlanningMode 应该用 AUTO 而不是 SEMI_AUTO，或者审批门用程序化 ApprovalManager 自动通过白名单内的操作。

### 场景 B：LLM 幻觉导致环形依赖的 TaskGraph

**执行轨迹**：

```
[2026-06-07T11:00:00.000] 用户输入："分析市场数据并生成报告"
[2026-06-07T11:00:00.001] 进入 PLANNING 阶段
[2026-06-07T11:00:00.002] Planner.plan() 调用 ChatModel
[2026-06-07T11:00:00.500] ChatModel 返回 JSON：
                         {
                           "nodes": [
                             {"id": "A", "description": "获取市场数据", "type": "TOOL_CALL"},
                             {"id": "B", "description": "清洗数据",     "type": "TOOL_CALL"},
                             {"id": "C", "description": "分析趋势",     "type": "LLM_CALL"},
                             {"id": "D", "description": "生成报告",     "type": "LLM_CALL"}
                           ],
                           "edges": [
                             {"from": "A", "to": "B"},
                             {"from": "B", "to": "C"},
                             {"from": "C", "to": "A"},    // ← 幻觉：C → A 环！
                             {"from": "C", "to": "D"}
                           ]
                         }
                         ↓
[2026-06-07T11:00:00.501] TaskGraph 构造函数执行
                         → hasCycle() 检测到环：A → B → C → A
                         → 抛出 IllegalArgumentException
                         ↓
[2026-06-07T11:00:00.502] Planner 捕获异常，触发 replan
                         重新调用 ChatModel，在 prompt 中加入：
                         "上次规划的 TaskGraph 包含环形依赖，请确保没有循环引用"
                         ↓
[2026-06-07T11:00:01.000] ChatModel 返回修正后的 JSON：
                         移除了 C → A 的边
                         TaskGraph 构造成功
                         ↓
[2026-06-07T11:00:01.001] TaskGraph.executionLayers() 返回：
                         Layer 0: [A]
                         Layer 1: [B]
                         Layer 2: [C]
                         Layer 3: [D]
                         ↓
[2026-06-07T11:00:01.002] PlanGeneratedEvent 发出
                         进入 PLAN_REVIEW（SEMI_AUTO 模式）
                         ↓
[2026-06-07T11:00:02.000] 开发者审批通过
                         进入 EXECUTING
                         → 正常执行完成

如果没有 fail-fast 环检测，会发生什么：
[假设] executionLayers() 的 Kahn's algorithm 死循环
        → 虚拟线程永远不释放
        → 1000 个请求 × 15 步 × 每步一个死循环线程 = 15000 个死线程
        → 内存缓慢泄漏
        → 10 分钟后 OOM
```

**诊断**：构造时环检测成功拦截了 LLM 幻觉。系统自动 replan 了一次就成功了。fail-fast 策略证明了其价值。

### 场景 C：检查点恢复时 Redis 宕机

**执行轨迹**：

```
[2026-06-07T12:00:00.000] Agent "order-processor" 正在执行中
                         已完成 Layer 0（3 个节点），正在执行 Layer 1
                         已保存 2 个检查点到 Redis
                         ↓
[2026-06-07T12:00:00.100] Layer 1 节点 D 执行中
                         CheckpointManager.save() 调用 Redis SET
                         ↓
[2026-06-07T12:00:00.101] *** Redis 连接超时 ***
                         io.lettuce.core.RedisConnectionException:
                         Unable to connect to redis://10.0.0.5:6379
                         ↓
[2026-06-07T12:00:00.102] CheckpointManager.save() 进入异常处理
                         ↓
--- 变体 Alpha 的行为 ---

路径 1: CheckpointManager 有降级策略（设计要求）
[2026-06-07T12:00:00.102] RedisCheckpointStore 捕获异常
                         → 降级到 InMemoryCheckpointStore
                         → 检查点写入本地内存
                         → 发出事件：CheckpointDegradedEvent
                         → 日志记录降级
                         ↓
[2026-06-07T12:00:00.103] 节点 D 执行完成
                         检查点保存在内存中（进程重启会丢失，但执行不中断）
                         ↓
[2026-06-07T12:00:00.200] Redis 恢复
                         后续检查点同时写入 Redis + 内存
                         之前的内存检查点被丢弃（非关键数据）
                         ↓
[2026-06-07T12:00:00.300] Agent 执行完成
                         最终检查点写入 Redis 成功

路径 2: CheckpointManager 没有降级策略（开发者的 ErrorPolicy 决定）
[2026-06-07T12:00:00.102] 异常向上传播
                         → ErrorPolicy.handleError() 被调用
                         → FailFastPolicy: 立即 AbortDecision
                         → Agent 执行中止，状态变为 FAILED
                         ↓
[2026-06-07T12:00:00.103] ExecutionFailedEvent 发出
                         错误信息："Checkpoint save failed: Redis connection refused"
                         ↓
[2026-06-07T12:00:00.104] 开发者收到通知
                         可以从 Redis 恢复后，手动 resume
                         但最近的检查点是 Layer 0 完成时（2 秒前）
                         Layer 1 需要重新执行

--- 如果在恢复时 Redis 宕机 ---

[2026-06-07T12:30:00.000] 开发者调用 engine.resume(executionId)
                         ↓
[2026-06-07T12:30:00.001] CheckpointManager.loadLatest(executionId)
                         → Redis GET → 连接失败
                         ↓
[2026-06-07T12:30:00.002] RedisCheckpointStore 返回 Optional.empty()
                         ↓
[2026-06-07T12:30:00.003] PevEngine.resume() 的调用者收到 empty
                         → 开发者决定：
                            a) 等待 Redis 恢复后重试
                            b) 从头重新执行（最新检查点丢失）
                            c) 人工介入恢复状态

关键差异：变体 Alpha 把决策权交给开发者。
         Runtime 不替开发者决定"降级"还是"中止"。
         开发者的 ErrorPolicy 定义了行为。
```

**诊断**：Redis 宕机是基础设施问题，任何框架都无法避免。变体 Alpha 的优势在于：
1. 检查点保存失败不会导致执行崩溃（降级路径）
2. 恢复失败时，开发者有选择权而不是被迫从头开始
3. 所有行为由开发者定义的 ErrorPolicy 决定，不是 Runtime 的隐式行为

---

## 四、架构基因评分

| 维度 | 分数 | 说明 |
|------|------|------|
| **极致灵活性** | 9/10 | sealed interface + record 让开发者可以在编译期穷举所有变体。PlanningMode 三档（AUTO/SEMI/MANUAL）覆盖从简单到复杂的全部场景。唯一扣分：sealed interface 不允许包外扩展 |
| **认知心智负担** | 4/10 | 越低越好，4 分偏中等。开发者需要理解 AgentDefinition / ExecutionPolicy / Constraint / ApprovalGate 四个核心概念。比 v3 的 "一个 @Agent 注解搞定" 学习曲线更陡，但换来的是可控性 |
| **抗熵增能力** | 8/10 | 不可变 record + sealed interface 天然抑制熵增。新增事件类型必须修改 sealed interface 声明，编译器强制所有 switch 穷举更新。Constraint 和 ErrorPolicy 的 sealed 体系同理 |
| **企业合规友好度** | 10/10 | 审批门 + 约束系统 + 完整事件日志 + 检查点快照 = 合规的完美工具。每一个决策点都有记录，每一次工具调用都有审批轨迹。这是变体 Alpha 的最强维度 |
| **高并发吞吐** | 7/10 | 虚拟线程 + 不可变状态 = 并发安全。但 AgentContext 每次 with* 创建新对象，高并发下 GC 压力不小（ZGC 可以缓解）。审批门是同步阻塞的，可能成为吞吐瓶颈 |
| **状态强一致性** | 8/10 | AgentContext 不可变 = 状态始终一致。CheckpointStore 的具体实现决定最终一致性程度。Redis 宕机时有降级路径。扣分点：并行层执行时，多个虚拟线程的变量合并需要额外同步 |
| **开发者调试体验** | 9/10 | 完整的执行轨迹（23 种事件类型）、每个阶段都有检查点、pattern matching 让 IDE 自动补全所有 case。唯一的扣分：不可变 AgentContext 的调试需要理解 with* 链 |

**总分：55/70（78.6%）**

**强项**：合规友好度、灵活性、调试体验
**弱项**：心智负担（学习成本）、并发吞吐的 GC 压力

---

## 五、与 AgentScope v2 的关键差异

### 差异 1：Agent 是有状态对象 vs 无状态定义

| | AgentScope v2 | 变体 Alpha |
|---|---|---|
| Agent 实例 | 有状态，每个 Agent 持有 Memory + Toolkit | `AgentDefinition` 是不可变 record，无状态 |
| 并发模型 | Agent 对象非线程安全，需要对象池 | 一个 Definition 服务万级并发 |
| 状态位置 | Agent 对象内部 | `AgentContext` 外部化，每次执行新建 |

**分道扬镳原因**：AgentScope 的设计来自 Python 生态的直觉（一个 Agent = 一个对象）。Java 21 的虚拟线程让"无状态 + 外部化"的成本趋近于零，而收益（线程安全、可调试、可审计）是企业级刚需。

### 差异 2：Middleware 洋葱模型 vs 声明式约束 + 审批门

| | AgentScope v2 | 变体 Alpha |
|---|---|---|
| 控制机制 | Middleware 洋葱模型（请求 → M1 → M2 → 核心 → M2 → M1 → 响应） | Constraint（自动检查）+ ApprovalGate（人工审批） |
| 扩展方式 | 写 Middleware 类，注册到链上 | 声明 Constraint record，或实现 sealed interface |
| 运行时修改 | 可以动态增删 Middleware | AgentDefinition 不可变，运行时不可修改 |

**分道扬镳原因**：洋葱模型在 Web 框架（Express/Koa/Spring Interceptor）中很成功，但 Agent 的控制需求不同于 HTTP 请求。Agent 需要：
1. 精确到"哪个工具在什么条件下需要审批"——洋葱模型做不到这个粒度
2. 合规审计需要"规则是什么"的声明式描述——洋葱模型的命令式代码不可审计
3. 运行时不可修改安全规则——洋葱模型的动态增删是安全漏洞

### 差异 3：事件系统：开放继承 vs sealed interface

| | AgentScope v2 | 变体 Alpha |
|---|---|---|
| 事件类型 | 开放类继承，可任意扩展 | sealed interface，编译期穷举 |
| 事件处理 | `instanceof` 检查或方法名约定 | pattern matching switch，编译器强制穷举 |
| 遗漏风险 | 高——新增事件类型不会编译报错 | 零——新增类型必须更新 permits 列表，所有 switch 报错 |

**分道扬镳原因**：AgentScope 的 Hook 系统有 12+ 事件类型，但开发者可能忘记处理 `onToolCallFailed`。在变体 Alpha 中，这不可能发生——编译器会报错。

### 差异 4：规划模式：LLM 自动 vs 三档可控

| | AgentScope v2 | 变体 Alpha |
|---|---|---|
| 规划 | ReAct 循环（LLM 自主决策下一步） | PlanningMode：AUTO / SEMI_AUTO / MANUAL |
| 开发者介入 | 只能通过 Hook 间接影响 | SEMI_AUTO：Plan 必须经过开发者审批 |
| 确定性流程 | 不支持（ReAct 本质是非确定性的） | MANUAL：开发者预定义 TaskGraph |

**分道扬镳原因**：AgentScope 的 ReAct 循环是 LLM-first——开发者信任 LLM 做对。变体 Alpha 是 developer-first——LLM 是建议者，开发者是决策者。这两种哲学没有对错，但目标用户群不同。

### 差异 5：DashScope-first vs Provider-neutral

| | AgentScope v2 | 变体 Alpha |
|---|---|---|
| 模型接入 | DashScope 原生，其他提供商额外适配 | Spring AI ChatModel，20+ 提供商天然支持 |
| 模型路由 | 通过 DashScope 的模型名选择 | Spring AI 的 ChatModel 抽象，可 A/B 测试、降级 |
| 国际化 | 文档/社区以中文为主 | 代码即文档，接口定义是通用的 |

**分道扬镳原因**：这是生态定位的差异，不是技术优劣。AgentScope 服务阿里云生态，变体 Alpha 服务提供商中立的 Java 企业。

---

## 附录 A：模块依赖关系

```
openjiuwen-java-core (Java 21, 零 Spring 依赖)
  ├── sealed interface: AgentEvent, Constraint, ErrorPolicy, PlanStep, ...
  ├── record: AgentDefinition, ExecutionPolicy, TaskGraph, Checkpoint, ...
  ├── interface: CheckpointStore, VariableStore, EventBus, ...
  └── 依赖: jackson-databind (序列化)

openjiuwen-java-runtime (Java 21, Spring Boot 3.3+)
  ├── PevEngine (核心执行引擎)
  ├── Planner / Executor / Verifier (PEV 三阶段)
  ├── ConstraintChecker / ApprovalManager (控制层)
  ├── CheckpointManager (检查点管理)
  ├── store/ (Redis / JDBC / InMemory 实现)
  └── 依赖: core + spring-boot-starter + spring-ai + reactor-core

openjiuwen-java-sdk (Java 8, 零框架依赖)
  ├── AgentClient (HTTP 客户端)
  ├── AgentEventHandler (事件回调)
  ├── LightweightMcpServer (工具暴露)
  └── 依赖: jackson-databind (HTTP JSON)
```

## 附录 B：v3 到变体 Alpha 的增量改动清单

| v3 概念 | 变体 Alpha 变化 | 理由 |
|---------|----------------|------|
| `@Agent` 注解 | 保留，但内部映射到 `AgentDefinition` record | 注解是语法糖，record 是真实数据结构 |
| `ExecutionStrategy` 接口 | 保留，但 `PlanExecuteVerifyStrategy` 重构为 `PevEngine` | 分离"策略选择"和"策略实现" |
| `TaskGraph` | 新增环检测 + `developerApproved` 字段 | 安全性 + 可控性 |
| `AgentEvent` 8 种 | 扩展到 23 种 sealed 类型 | 更细粒度的事件 = 更好的调试和审计 |
| 无约束系统 | 新增 `Constraint` sealed interface | 合规需求 |
| 无审批门 | 新增 `ApprovalGate` + `ApprovalManager` | 人控需求 |
| `ErrorPolicy` 无 | 新增 `ErrorPolicy` sealed interface | 可预测的错误处理 |
| `AgentContext` 可变 | 改为不可变 record + `with*` 方法 | 线程安全 + 审计 |
