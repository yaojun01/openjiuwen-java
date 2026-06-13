package com.openjiuwen.runtime.alpha;

import com.openjiuwen.core.alpha.event.AlphaEvent;
import com.openjiuwen.runtime.alpha.executor.DefaultPregelExecutor;
import com.openjiuwen.runtime.alpha.executor.PregelExecutor;
import com.openjiuwen.core.alpha.executor.SuperstepResult;
import com.openjiuwen.core.alpha.graph.*;
import com.openjiuwen.core.alpha.model.*;
import com.openjiuwen.runtime.alpha.planner.DefaultPlanner;
import com.openjiuwen.runtime.alpha.planner.Planner;
import com.openjiuwen.runtime.alpha.verifier.DefaultVerifier;
import com.openjiuwen.core.alpha.verifier.ReplanStrategy;
import com.openjiuwen.runtime.alpha.verifier.Verifier;
import com.openjiuwen.core.alpha.verifier.VerifyResult;
import com.openjiuwen.runtime.core.dispatch.ExecutionStrategy;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.*;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Alpha 策略——PEV（Plan-Execute-Verify）显式控制引擎。
 *
 * 三阶段执行模型（深度设计版）：
 *
 * Phase 1: Plan
 *   Planner 接收 PlanGoal → LLM 生成 TaskGraph → PlanValidator 校验（环检测/依赖/预算/约束）
 *   → 校验失败则自纠错循环 → 根据 PlanningMode 触发审批
 *
 * Phase 2: Execute
 *   PregelExecutor 按 BSP 超步执行 TaskGraph → 同层虚拟线程并行 → 子 Agent 递归（最深3层）
 *   → ErrorPolicy 处理失败节点 → 每层保存检查点
 *
 * Phase 3: Verify
 *   Verifier 逐节点验证 + successCriteria 逐条验证 → 失败则 ReplanStrategy 决策
 *   → LocalReplan / GlobalReplan / AcceptPartial
 *
 * 核心特征：
 * - 显式控制：开发者通过 ExecutionPolicy 精确控制每一步的行为
 * - 确定性执行：TaskGraph 一旦确定，执行顺序固定
 * - 可插拔：Planner / Executor / Verifier 均可替换
 * - 审批门：在指定节点前暂停，等待人工确认
 * - 局部重做：验证失败只重跑失败节点，不从头来
 */
@Component("alpha")
public class AlphaStrategy implements ExecutionStrategy {

    private static final int MAX_VERIFY_RETRIES = 3;
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(120); // COR-003/004

    @Override
    public String name() {
        return "alpha";
    }

    @Override
    public Flux<AgentEvent> execute(TaskContext context) {
        return Flux.<AgentEvent>create(sink -> {
            PregelExecutor executor = resolveExecutor(context); // COR-001: 声明在 try 外，便于 finally 清理
            AtomicBoolean terminalGuard = new AtomicBoolean(false); // R2-002: 防止双终端信号
            try {
                TaskId taskId = context.taskId();
                AgentKernel kernel = context.kernel();
                AtomicReference<BudgetLimits> budgetRef = new AtomicReference<>(
                    context.currentBudgetLimits());
                ExecutionPolicy policy = resolveExecutionPolicy(context);

                // 创建可插拔组件
                Planner planner = resolvePlanner(context, kernel);
                Verifier verifier = resolveVerifier(context, kernel);

                // 构建 PlanGoal
                PlanGoal goal = buildPlanGoal(context);

                // ==================== Phase 1: Plan ====================
                sink.next(AgentEvent.of(taskId, EventType.TASK_STARTED, "开始规划"));

                // COR-003: 添加超时防止永久阻塞
                PlanResult planResult = planner.plan(taskId, goal, policy)
                    .timeout(LLM_TIMEOUT).block();

                if (planResult == null || !planResult.isValid() || planResult.graph() == null) {
                    sink.next(AgentEvent.of(taskId, EventType.TASK_FAILED,
                        "规划失败: " + (planResult != null ? planResult.issues().toString() : "null")));
                    sink.complete();
                    closeQuietly(executor); // COR-001
                    return;
                }

                TaskGraph graph = planResult.graph();
                sink.next(toAlphaEvent(taskId, new AlphaEvent.PlanGenerated(taskId, now(), graph)));

                // 保存检查点：规划完成
                saveCheckpoint(kernel, taskId, "PLANNING", 0, graph);

                // 报告校验问题（如有）
                if (!planResult.issues().isEmpty()) {
                    for (PlanResult.PlanIssue issue : planResult.issues()) {
                        sink.next(toAlphaEvent(taskId, new AlphaEvent.ConstraintViolated(
                            taskId, now(), new Constraint.OutputFormatConstraint(
                                "plan-warning", issue.message(), "warning"))));
                    }
                }

                // Plan 审批
                if (policy.planningMode() == PlanningMode.MANUAL) {
                    kernel.yield(taskId,
                        new YieldReason.WaitingForApproval("plan-review", "等待人类审核执行计划"),
                        serializeGraph(graph)).block(LLM_TIMEOUT); // COR-004
                    sink.next(toAlphaEvent(taskId, new AlphaEvent.ApprovalRequired(
                        taskId, now(), null)));
                } else if (policy.planningMode() == PlanningMode.SEMI_AUTO) {
                    // SEMI_AUTO：只对有审批约束的节点 yield
                    for (TaskNode node : graph.nodes()) {
                        if (node.type() == TaskNodeType.TOOL_CALL) {
                            sink.next(toAlphaEvent(taskId, new AlphaEvent.ApprovalRequired(
                                taskId, now(), ApprovalGate.pending(
                                    node.id().value(),
                                    new ToolName(node.description()),
                                    "SEMIAUTO: 工具调用需确认",
                                    Map.of()))));
                        }
                    }
                }

                // ==================== Phase 2: Execute ====================
                Map<NodeId, Object> completedResults = new ConcurrentHashMap<>();
                AtomicBoolean verifyExceeded = new AtomicBoolean(false); // COR-007: 跟踪验证失败

                // R2-011: 用 concatMap 替代 doOnComplete 中的阻塞调用，避免线程饥饿
                Disposable subscription = executor.execute(taskId, graph, policy, budgetRef.get())
                    .doOnNext(superstep -> {
                        // REG-001: 按 superstep 结果数追踪 LLM/工具调用消耗
                        int nodesThisStep = superstep.nodeResults().size();
                        if (nodesThisStep > 0) {
                            // 保守估计：每个节点至少 1 次 LLM 调用
                            BudgetLimits current = budgetRef.get();
                            budgetRef.set(current.recordLLMCall(nodesThisStep * 128));
                        }

                        // 合并结果
                        completedResults.putAll(superstep.nodeResults());

                        // 发布节点完成事件
                        for (var entry : superstep.nodeResults().entrySet()) {
                            if (!superstep.failedNodes().contains(entry.getKey())) {
                                kernel.emit(toAlphaEvent(taskId, new AlphaEvent.NodeCompleted(
                                    taskId, now(), entry.getKey(), entry.getValue())))
                                    .subscribe(r -> {}, e -> {}); // COR-012: 显式错误处理
                            }
                        }

                        // 发布节点失败事件（C层可观测：透传 executor 已捕获的真实异常原因，不再用通用文案）
                        for (NodeId failedId : superstep.failedNodes()) {
                            Object raw = superstep.nodeResults().get(failedId);
                            String error = (raw instanceof String s && s.startsWith("FAILED:"))
                                ? s.substring("FAILED:".length()).trim()
                                : (raw != null ? String.valueOf(raw) : "执行失败");
                            kernel.emit(toAlphaEvent(taskId, new AlphaEvent.NodeFailed(
                                taskId, now(), failedId, error)))
                                .subscribe(r -> {}, e -> {}); // COR-012
                        }

                        sink.next(toAlphaEvent(taskId, new AlphaEvent.LayerCompleted(
                            taskId, now(), superstep.superstepIndex(),
                            new ArrayList<>(superstep.nodeResults().keySet()))));
                    })
                    .collectList()
                    .flatMap(steps ->
                        // NEW-002: 隔离阻塞 verify 操作到独立线程，避免 boundedElastic 线程饥饿
                        Mono.fromRunnable(() -> {
                            // ==================== Phase 3: Verify ====================
                            if (policy.verifyMode() != VerifyMode.NONE) {
                                executeVerifyLoop(taskId, goal, graph, completedResults,
                                    policy, budgetRef, planner, verifier, kernel, executor, sink, 0, verifyExceeded);
                            }

                            // COR-007: 验证超限则跳过成功事件
                            if (!verifyExceeded.get()) {
                                String finalOutput = assembleOutput(completedResults);
                                sink.next(toAlphaEvent(taskId, new AlphaEvent.AlphaCompleted(
                                    taskId, now(), finalOutput, graph, true)));
                                sink.next(AgentEvent.of(taskId, EventType.TASK_COMPLETED, finalOutput));
                            }
                            if (terminalGuard.compareAndSet(false, true)) {
                                sink.complete();
                                closeQuietly(executor);
                            }
                        }).subscribeOn(Schedulers.boundedElastic())
                    )
                    .onErrorResume(error -> {
                        sink.next(AgentEvent.of(taskId, EventType.TASK_FAILED, error.getMessage()));
                        if (terminalGuard.compareAndSet(false, true)) {
                            sink.complete();
                            closeQuietly(executor);
                        }
                        return Mono.empty();
                    })
                    .subscribe();

                // COR-001/COR-002: 外层 Flux 取消时，取消订阅并清理资源
                sink.onDispose(() -> {
                    subscription.dispose();
                    if (terminalGuard.compareAndSet(false, true)) {
                        closeQuietly(executor);
                    }
                });

            } catch (Exception e) {
                sink.next(AgentEvent.of(context.taskId(), EventType.TASK_FAILED, e.getMessage()));
                if (terminalGuard.compareAndSet(false, true)) {
                    sink.complete();
                    closeQuietly(executor);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint) {
        // 从检查点恢复：反序列化状态，跳到对应阶段继续执行
        // TODO: COR-019 实现 checkpoint 恢复
        return execute(context);
    }

    // ==================== Verify 循环 ====================

    /**
     * 验证循环：验证 → 失败 → replan → 重新执行 → 再验证。
     * 最多重试 MAX_VERIFY_RETRIES 次。
     */
    private void executeVerifyLoop(
            TaskId taskId, PlanGoal goal, TaskGraph graph,
            Map<NodeId, Object> completedResults,
            ExecutionPolicy policy, AtomicReference<BudgetLimits> budgetRef,
            Planner planner, Verifier verifier, AgentKernel kernel,
            PregelExecutor executor,
            reactor.core.publisher.FluxSink<AgentEvent> sink,
            int retryCount,
            AtomicBoolean verifyExceeded) {

        // COR-004: 添加超时防止验证器永久阻塞
        VerifyResult verifyResult;
        try {
            verifyResult = verifier.verify(taskId, goal, graph,
                completedResults, policy, budgetRef.get())
                .timeout(LLM_TIMEOUT).block();
            // REG-001: 追踪 verify LLM 调用消耗
            budgetRef.set(budgetRef.get().recordLLMCall(256));
        } catch (Exception e) {
            sink.next(toAlphaEvent(taskId, new AlphaEvent.VerifyFailed(
                taskId, now(), "验证超时或异常: " + e.getMessage(), Set.of())));
            return;
        }

        if (verifyResult == null) {
            sink.next(toAlphaEvent(taskId, new AlphaEvent.VerifyFailed(
                taskId, now(), "验证器返回 null", Set.of())));
            return;
        }

        if (verifyResult.passed()) {
            sink.next(toAlphaEvent(taskId, new AlphaEvent.VerifyPassed(
                taskId, now(), verifyResult.overallFeedback())));
            return;
        }

        sink.next(toAlphaEvent(taskId, new AlphaEvent.VerifyFailed(
            taskId, now(), verifyResult.overallFeedback(), verifyResult.failedNodes())));

        if (retryCount >= MAX_VERIFY_RETRIES) {
            sink.next(AgentEvent.of(taskId, EventType.TASK_FAILED,
                "验证失败次数超过上限 " + MAX_VERIFY_RETRIES));
            verifyExceeded.set(true); // COR-007: 标记验证超限
            return;
        }

        // 决定 replan 策略
        ReplanStrategy replanStrategy = verifier.decideReplanStrategy(verifyResult, retryCount);

        switch (replanStrategy) {
            case ReplanStrategy.LocalReplan lr -> {
                // 局部重做：重新执行失败节点
                sink.next(toAlphaEvent(taskId, new AlphaEvent.PlanRevised(
                    taskId, now(), graph, verifyResult.failedNodes(),
                    verifyResult.overallFeedback())));

                // COR-005: 实际重新执行失败节点而非空递归
                Set<String> failedIds = verifyResult.failedNodes();
                List<TaskNode> failedNodes = graph.nodes().stream()
                    .filter(n -> failedIds.contains(n.id().value()))
                    .toList();

                if (!failedNodes.isEmpty()) {
                    try {
                        // R2-015: 预解析失败节点的 inputs，将上游结果内联到 description
                        // 因为子图执行器的 accumulatedResults 为空，无法解析 ${nodeId.output}
                        List<TaskNode> resolvedNodes = new ArrayList<>();
                        for (TaskNode fn : failedNodes) {
                            Map<String, String> resolvedInputs = new LinkedHashMap<>();
                            for (var entry : fn.inputs().entrySet()) {
                                String val = entry.getValue();
                                if (val.startsWith("${") && val.endsWith("}")) {
                                    String ref = val.substring(2, val.length() - 1);
                                    String[] parts = ref.split("\\.", 2);
                                    // NEW-006: guard against malformed references
                                    if (parts.length < 2 || parts[0].isBlank()) {
                                        resolvedInputs.put(entry.getKey(), val);
                                        continue;
                                    }
                                    Object upstream = completedResults.get(new NodeId(parts[0]));
                                    resolvedInputs.put(entry.getKey(),
                                        upstream != null ? String.valueOf(upstream) : val);
                                } else {
                                    resolvedInputs.put(entry.getKey(), val);
                                }
                            }
                            resolvedNodes.add(new TaskNode(fn.id(), fn.description(),
                                fn.type(), resolvedInputs, fn.expectedOutput(), fn.status()));
                        }

                        TaskGraph subGraph = new TaskGraph(
                            goal.description() + " (局部重做)", resolvedNodes, List.of());
                        // R2-001: 收集到临时 map，成功后才合并
                        Map<NodeId, Object> reExecResults = new ConcurrentHashMap<>();
                        executor.execute(taskId, subGraph, policy, budgetRef.get())
                            .doOnNext(step -> reExecResults.putAll(step.nodeResults()))
                            .blockLast(LLM_TIMEOUT);
                        completedResults.putAll(reExecResults); // 只在完整成功后合并
                    } catch (Exception e) {
                        // NEW-016: 发事件而非静默吞异常
                        sink.next(AgentEvent.of(taskId, EventType.PLAN_REVISED,
                            "LocalReplan re-execution failed: " + e.getMessage()));
                    }
                }

                executeVerifyLoop(taskId, goal, graph, completedResults,
                    policy, budgetRef, planner, verifier, kernel, executor, sink,
                    retryCount + 1, verifyExceeded);
            }
            case ReplanStrategy.GlobalReplan _ -> {
                // 全局重规划：重新调用 Planner
                sink.next(AgentEvent.of(taskId, EventType.PLAN_REVISED,
                    "全局重规划（验证失败后）"));

                // COR-006: 用 resolvePlanner() 而非硬编码 DefaultPlanner
                try {
                    PlanResult newPlan = planner.plan(taskId, goal, policy)
                        .timeout(LLM_TIMEOUT).block();

                    if (newPlan != null && newPlan.isValid() && newPlan.graph() != null) {
                        sink.next(toAlphaEvent(taskId, new AlphaEvent.PlanRevised(
                            taskId, now(), newPlan.graph(), verifyResult.failedNodes(),
                            verifyResult.overallFeedback())));

                        TaskGraph newGraph = newPlan.graph();
                        Map<NodeId, Object> newResults = new ConcurrentHashMap<>();
                        executor.execute(taskId, newGraph, policy, budgetRef.get())
                            .doOnNext(step -> newResults.putAll(step.nodeResults()))
                            .blockLast(LLM_TIMEOUT);

                        executeVerifyLoop(taskId, goal, newGraph, newResults,
                            policy, budgetRef, planner, verifier, kernel, executor, sink,
                            retryCount + 1, verifyExceeded);
                        return;
                    }
                } catch (Exception ignored) {
                    // 重规划或重新执行失败
                }

                // 降级：用旧数据重试
                executeVerifyLoop(taskId, goal, graph, completedResults,
                    policy, budgetRef, planner, verifier, kernel, executor, sink,
                    retryCount + 1, verifyExceeded);
            }
            case ReplanStrategy.AcceptPartial _ -> {
                // 接受部分结果
                sink.next(AgentEvent.of(taskId, EventType.TASK_PAUSED,
                    "接受部分结果（验证多次失败）"));
            }
        }
    }

    // ==================== 组件解析 ====================

    /**
     * 解析 Planner 实现。
     * 优先从 extraContext 获取自定义实现，否则用 DefaultPlanner。
     */
    private Planner resolvePlanner(TaskContext context, AgentKernel kernel) {
        Object custom = context.extraContext().get("planner");
        if (custom instanceof Planner p) return p;
        return new DefaultPlanner(kernel);
    }

    /**
     * 解析 PregelExecutor 实现。
     */
    private PregelExecutor resolveExecutor(TaskContext context) {
        Object custom = context.extraContext().get("executor");
        if (custom instanceof PregelExecutor e) return e;
        return new DefaultPregelExecutor(context);
    }

    /**
     * 解析 Verifier 实现。
     */
    private Verifier resolveVerifier(TaskContext context, AgentKernel kernel) {
        Object custom = context.extraContext().get("verifier");
        if (custom instanceof Verifier v) return v;
        return new DefaultVerifier(kernel);
    }

    // ==================== 辅助方法 ====================

    private PlanGoal buildPlanGoal(TaskContext context) {
        String userInput = context.input().userInput();
        Map<String, Object> params = context.input().parameters();

        // COR-011: 安全的类型提取，防止 ClassCastException
        List<String> successCriteria = List.of();
        if (params.containsKey("successCriteria") && params.get("successCriteria") instanceof List<?> list) {
            successCriteria = list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }

        Set<String> availableTools = Set.of();
        if (params.containsKey("availableTools") && params.get("availableTools") instanceof List<?> list) {
            availableTools = new HashSet<>(list.stream().filter(Objects::nonNull).map(Object::toString).toList());
        }

        Map<String, String> ctx = new HashMap<>();
        if (context.input().metadata() != null) {
            ctx.putAll(context.input().metadata());
        }

        return PlanGoal.of(userInput, successCriteria, availableTools, ctx);
    }

    private ExecutionPolicy resolveExecutionPolicy(TaskContext context) {
        Object policy = context.extraContext().get("executionPolicy");
        return policy instanceof ExecutionPolicy ep ? ep : ExecutionPolicy.productionDefault();
    }

    private void saveCheckpoint(AgentKernel kernel, TaskId taskId, String phase,
                                 int stepIndex, TaskGraph graph) {
        try {
            Checkpoint cp = Checkpoint.of(taskId, phase, stepIndex, serializeGraph(graph));
            kernel.saveCheckpoint(cp).block(LLM_TIMEOUT); // COR-004
        } catch (Exception ignored) {
            // 检查点保存失败不应中断执行
        }
    }

    private String serializeGraph(TaskGraph graph) {
        return graph != null ? graph.goal() : "";
    }

    private String assembleOutput(Map<NodeId, Object> results) {
        StringBuilder sb = new StringBuilder();
        for (var entry : results.entrySet()) {
            sb.append(entry.getKey().value()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    private Instant now() { return Instant.now(); }

    private AgentEvent toAlphaEvent(TaskId taskId, AlphaEvent alpha) {
        return AgentEvent.of(taskId, mapAlphaEventType(alpha), alpha.toString());
    }

    private EventType mapAlphaEventType(AlphaEvent alpha) {
        return switch (alpha) {
            case AlphaEvent.PlanGenerated ig -> EventType.PLAN_GENERATED;
            case AlphaEvent.PlanRevised ir -> EventType.PLAN_REVISED;
            case AlphaEvent.NodeStarted ns -> EventType.NODE_STARTED;
            case AlphaEvent.NodeCompleted nc -> EventType.NODE_COMPLETED;
            case AlphaEvent.NodeFailed nf -> EventType.NODE_FAILED;
            case AlphaEvent.LayerCompleted lc -> EventType.LAYER_COMPLETED;
            case AlphaEvent.VerifyPassed vp -> EventType.VERIFY_PASSED;
            case AlphaEvent.VerifyFailed vf -> EventType.VERIFY_FAILED;
            case AlphaEvent.ApprovalRequired ar -> EventType.APPROVAL_REQUIRED;
            case AlphaEvent.ApprovalGranted ag -> EventType.APPROVAL_GRANTED;
            case AlphaEvent.ApprovalDenied ad -> EventType.APPROVAL_DENIED;
            case AlphaEvent.ConstraintViolated cv -> EventType.CONSTRAINT_VIOLATED;
            case AlphaEvent.AlphaCompleted ac -> EventType.TASK_COMPLETED;
        };
    }

    // COR-001: 清理 ExecutorService 资源
    private void closeQuietly(PregelExecutor executor) {
        if (executor instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }
}
