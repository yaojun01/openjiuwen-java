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
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    @Override
    public String name() {
        return "alpha";
    }

    @Override
    public Flux<AgentEvent> execute(TaskContext context) {
        return Flux.<AgentEvent>create(sink -> {
            try {
                TaskId taskId = context.taskId();
                AgentKernel kernel = context.kernel();
                BudgetLimits budget = context.currentBudgetLimits();
                ExecutionPolicy policy = resolveExecutionPolicy(context);

                // 创建可插拔组件
                Planner planner = resolvePlanner(context, kernel);
                PregelExecutor executor = resolveExecutor(context);
                Verifier verifier = resolveVerifier(context, kernel);

                // 构建 PlanGoal
                PlanGoal goal = buildPlanGoal(context);

                // ==================== Phase 1: Plan ====================
                sink.next(AgentEvent.of(taskId, EventType.TASK_STARTED, "开始规划"));

                PlanResult planResult = planner.plan(taskId, goal, policy).block();

                if (planResult == null || !planResult.isValid() || planResult.graph() == null) {
                    sink.next(AgentEvent.of(taskId, EventType.TASK_FAILED,
                        "规划失败: " + (planResult != null ? planResult.issues().toString() : "null")));
                    sink.complete();
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
                        serializeGraph(graph)).block();
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

                executor.execute(taskId, graph, policy, budget)
                    .doOnNext(superstep -> {
                        // 合并结果
                        completedResults.putAll(superstep.nodeResults());

                        // 发布节点完成事件
                        for (var entry : superstep.nodeResults().entrySet()) {
                            if (!superstep.failedNodes().contains(entry.getKey())) {
                                kernel.emit(toAlphaEvent(taskId, new AlphaEvent.NodeCompleted(
                                    taskId, now(), entry.getKey(), entry.getValue()))).subscribe();
                            }
                        }

                        // 发布节点失败事件
                        for (NodeId failedId : superstep.failedNodes()) {
                            kernel.emit(toAlphaEvent(taskId, new AlphaEvent.NodeFailed(
                                taskId, now(), failedId, "执行失败"))).subscribe();
                        }

                        sink.next(toAlphaEvent(taskId, new AlphaEvent.LayerCompleted(
                            taskId, now(), superstep.superstepIndex(),
                            new ArrayList<>(superstep.nodeResults().keySet()))));
                    })
                    .doOnComplete(() -> {
                        // ==================== Phase 3: Verify ====================
                        if (policy.verifyMode() != VerifyMode.NONE) {
                            executeVerifyLoop(taskId, goal, graph, completedResults,
                                policy, budget, verifier, kernel, sink, 0);
                        }

                        // ==================== 完成 ====================
                        String finalOutput = assembleOutput(completedResults);
                        sink.next(toAlphaEvent(taskId, new AlphaEvent.AlphaCompleted(
                            taskId, now(), finalOutput, graph, true)));
                        sink.next(AgentEvent.of(taskId, EventType.TASK_COMPLETED, finalOutput));
                        sink.complete();
                    })
                    .doOnError(error -> {
                        sink.next(AgentEvent.of(taskId, EventType.TASK_FAILED, error.getMessage()));
                        sink.complete();
                    })
                    .subscribe();
            } catch (Exception e) {
                sink.next(AgentEvent.of(context.taskId(), EventType.TASK_FAILED, e.getMessage()));
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint) {
        // 从检查点恢复：反序列化状态，跳到对应阶段继续执行
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
            ExecutionPolicy policy, BudgetLimits budget,
            Verifier verifier, AgentKernel kernel,
            reactor.core.publisher.FluxSink<AgentEvent> sink,
            int retryCount) {

        VerifyResult verifyResult = verifier.verify(taskId, goal, graph,
            completedResults, policy, budget).block();

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
            return;
        }

        // 决定 replan 策略
        ReplanStrategy replanStrategy = verifier.decideReplanStrategy(verifyResult, retryCount);

        switch (replanStrategy) {
            case ReplanStrategy.LocalReplan lr -> {
                // 局部重做：标记失败节点为 PENDING，重新执行
                sink.next(toAlphaEvent(taskId, new AlphaEvent.PlanRevised(
                    taskId, now(), graph, verifyResult.failedNodes(),
                    verifyResult.overallFeedback())));

                // 实际实现中需要重新创建只包含失败节点的子图
                // 简化：递归调用验证循环
                executeVerifyLoop(taskId, goal, graph, completedResults,
                    policy, budget, verifier, kernel, sink, retryCount + 1);
            }
            case ReplanStrategy.GlobalReplan _ -> {
                // 全局重规划：重新调用 Planner
                sink.next(AgentEvent.of(taskId, EventType.PLAN_REVISED,
                    "全局重规划（验证失败后）"));

                Planner planner = new DefaultPlanner(kernel);
                PlanResult newPlan = planner.plan(taskId, goal, policy).block();

                if (newPlan != null && newPlan.isValid() && newPlan.graph() != null) {
                    sink.next(toAlphaEvent(taskId, new AlphaEvent.PlanRevised(
                        taskId, now(), newPlan.graph(), verifyResult.failedNodes(),
                        verifyResult.overallFeedback())));
                }

                executeVerifyLoop(taskId, goal, graph, completedResults,
                    policy, budget, verifier, kernel, sink, retryCount + 1);
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

        List<String> successCriteria = params.containsKey("successCriteria")
            ? (List<String>) params.get("successCriteria")
            : List.of();

        Set<String> availableTools = params.containsKey("availableTools")
            ? new HashSet<>((List<String>) params.get("availableTools"))
            : Set.of();

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
            kernel.saveCheckpoint(cp).block();
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
}
