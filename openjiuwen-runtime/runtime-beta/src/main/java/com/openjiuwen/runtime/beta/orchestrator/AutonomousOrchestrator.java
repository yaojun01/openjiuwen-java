package com.openjiuwen.runtime.beta.orchestrator;

import com.openjiuwen.runtime.beta.context.ContextWindowManager;
import com.openjiuwen.runtime.beta.context.SelfReflectionTrigger;
import com.openjiuwen.runtime.beta.event.BetaEvent;
import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.beta.plan.BetaPlan;
import com.openjiuwen.runtime.beta.plan.PlanGenerator;
import com.openjiuwen.runtime.beta.reflection.DefaultGoalAlignmentCheck;
import com.openjiuwen.runtime.beta.reflection.GoalAlignmentCheck;
import com.openjiuwen.runtime.beta.verification.CriteriaVerifier;
import com.openjiuwen.runtime.beta.verification.DecisionHistoryCriteriaVerifier;
import com.openjiuwen.runtime.criteria.CriteriaOrchestrator;
import com.openjiuwen.runtime.criteria.CriteriaGuardrail;
import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.*;
import com.openjiuwen.runtime.core.dispatch.ExecutionStrategy;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Beta 策略 v2：LLM 自主编排循环。
 *
 * 核心循环（每一步）：
 * 1. 检查预算 → 2. 检查反思触发 → 3. 构造 System Prompt →
 * 4. LLM 推理 → 5. 解析 LLMDecision → 6. GuardrailEngine 检查 →
 * 7. 执行决策 → 8. 更新上下文 → 9. 检查 successCriteria（仅 Complete 前）→
 * 10. 循环或终止
 *
 * 与 v1 的差异：
 * - Replan 作为一等决策类型，有独立计数和超限处理
 * - DecisionParser 替代了硬编码的 parseDecision
 * - DecisionPromptBuilder 封装了 prompt 构造逻辑
 * - CriteriaVerifier 封装了 successCriteria 验证逻辑
 * - GoalAlignmentCheck 在子 Agent 场景下检测目标漂移
 */
public class AutonomousOrchestrator implements ExecutionStrategy {

    private static final int MAX_ITERATIONS = 50;
    private static final int CONTEXT_WINDOW_TOKENS = 128_000;
    /** 目标漂移检查间隔：每 N 步检查一次（B4 决策） */
    private static final int ALIGNMENT_CHECK_INTERVAL = 5;
    /** 计划进度提醒间隔：每 N 步注入一次 */
    private static final int PLAN_REVISION_INTERVAL = 5;

    /** 专用 Scheduler，避免与系统默认 boundedElastic 线程池竞争 */
    private static final reactor.core.scheduler.Scheduler DECISION_LOOP_SCHEDULER =
        Schedulers.newBoundedElastic(
            Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
            Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
            "beta-decision-loop");

    private final GuardrailEngine guardrailEngine;
    private final DecisionParser decisionParser;
    private final DecisionPromptBuilder promptBuilder;
    private final CriteriaVerifier criteriaVerifier;
    private final GoalAlignmentCheck alignmentCheck;
    /** Criteria 生命周期编排器。null 表示不接入 criteria 模块（向后兼容）。 */
    private final CriteriaOrchestrator criteriaOrchestrator;

    /** SEC-001/F05: execute() 构建的增强 guardrail，resume() 复用 */
    private volatile GuardrailEngine cachedEffectiveGuardrail;
    /** SEC-001/F05: execute() 确认的 verifiedCriteria，resume() 复用 */
    private volatile List<VerifiedCriterion> cachedVerifiedCriteria = List.of();

    public AutonomousOrchestrator(GuardrailEngine guardrailEngine) {
        this.guardrailEngine = guardrailEngine;
        this.decisionParser = new JsonDecisionParser();
        this.promptBuilder = new DefaultDecisionPromptBuilder();
        this.criteriaVerifier = new DecisionHistoryCriteriaVerifier();
        this.alignmentCheck = new DefaultGoalAlignmentCheck(ALIGNMENT_CHECK_INTERVAL);
        this.criteriaOrchestrator = null;
    }

    public AutonomousOrchestrator(
            GuardrailEngine guardrailEngine,
            DecisionParser decisionParser,
            DecisionPromptBuilder promptBuilder,
            CriteriaVerifier criteriaVerifier) {
        this.guardrailEngine = guardrailEngine;
        this.decisionParser = decisionParser;
        this.promptBuilder = promptBuilder;
        this.criteriaVerifier = criteriaVerifier;
        this.alignmentCheck = new DefaultGoalAlignmentCheck(ALIGNMENT_CHECK_INTERVAL);
        this.criteriaOrchestrator = null;
    }

    /**
     * 完整注入构造函数（含 Criteria 生命周期）。
     * BetaStrategy 在 CriteriaOrchestrator 可用时使用此构造函数。
     */
    public AutonomousOrchestrator(
            GuardrailEngine guardrailEngine,
            DecisionParser decisionParser,
            DecisionPromptBuilder promptBuilder,
            CriteriaVerifier criteriaVerifier,
            CriteriaOrchestrator criteriaOrchestrator) {
        this.guardrailEngine = guardrailEngine;
        this.decisionParser = decisionParser;
        this.promptBuilder = promptBuilder;
        this.criteriaVerifier = criteriaVerifier;
        this.alignmentCheck = new DefaultGoalAlignmentCheck(ALIGNMENT_CHECK_INTERVAL);
        this.criteriaOrchestrator = criteriaOrchestrator;
    }

    @Override
    public String name() {
        return "beta";
    }

    @Override
    public Flux<AgentEvent> execute(TaskContext context) {
        return Flux.<AgentEvent>create(sink -> {
            try {
                TaskId taskId = context.taskId();
                AgentKernel kernel = context.kernel();

                AtomicReference<BudgetLimits> budgetRef = new AtomicReference<>(
                    context.currentBudgetLimits());

                // === Pre-loop: propose → auto-confirm → enrich GoalSpec ===
                String taskDescription = context.input().userInput();
                List<VerifiedCriterion> verifiedCriteria = List.of();
                if (criteriaOrchestrator != null) {
                    List<CriteriaProposal> proposals =
                        criteriaOrchestrator.propose(taskDescription, StructuredCriteria.Industry.GENERAL);
                    if (!proposals.isEmpty()) {
                        // 自动确认所有 defaultSelected 的模板标准；若无则取前 3
                        List<CriteriaProposal> autoSelected = proposals.stream()
                            .filter(p -> p instanceof CriteriaProposal.TemplateProposal tp
                                         && tp.defaultSelected())
                            .toList();
                        if (autoSelected.isEmpty()) {
                            autoSelected = proposals.stream().limit(3).toList();
                        }
                        if (!autoSelected.isEmpty()) {
                            verifiedCriteria = criteriaOrchestrator.confirm(autoSelected);
                        }
                    }
                }

                AtomicReference<GoalSpec> goalRef = new AtomicReference<>(
                    verifiedCriteria.isEmpty()
                        ? GoalSpec.of(taskDescription)
                        : criteriaOrchestrator.buildGoalSpec(taskDescription, verifiedCriteria));

                // === 增强 GuardrailEngine：追加 CriteriaGuardrail ===
                // COR-007: 基于原始 guardrailEngine 追加，而非创建新实例（保留自定义护栏）
                GuardrailEngine effectiveGuardrail = verifiedCriteria.isEmpty()
                    ? guardrailEngine
                    : guardrailEngine.withExtra(new CriteriaGuardrail(verifiedCriteria));

                // SEC-001/F05: 缓存供 resume() 复用
                cachedEffectiveGuardrail = effectiveGuardrail;
                cachedVerifiedCriteria = verifiedCriteria;

                // === 生成初始计划（可变步骤列表，不是 Alpha 的 DAG） ===
                PlanGenerator planGenerator = new PlanGenerator();
                AtomicReference<BetaPlan> planRef = new AtomicReference<>(
                    planGenerator.generate(kernel, goalRef.get(), budgetRef.get()));
                if (planRef.get().steps().isEmpty()) {
                    planRef.set(BetaPlan.empty()); // 向后兼容
                } else {
                    emitBetaEvent(sink, taskId, new BetaEvent.PlanGenerated(
                        taskId, now(), planRef.get()));
                }

                ContextWindowManager ctxManager = new ContextWindowManager(CONTEXT_WINDOW_TOKENS);
                SelfReflectionTrigger reflectionTrigger = new SelfReflectionTrigger();

                // 分析目标
                emitBetaEvent(sink, taskId, new BetaEvent.GoalAnalyzed(
                    taskId, now(), goalRef.get(), goalRef.get().successCriteria()));

                // 初始化上下文
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "system", context.agentDefinition().systemPrompt()));
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "user", context.input().userInput()));

                List<LLMDecision> decisionHistory = new ArrayList<>();

                runDecisionLoop(sink, context, kernel, budgetRef, goalRef,
                    ctxManager, reflectionTrigger, decisionHistory, 0,
                    verifiedCriteria, criteriaOrchestrator, effectiveGuardrail,
                    planRef, planGenerator);

            } catch (Exception e) {
                emitEvent(sink, context.taskId(), EventType.TASK_FAILED, e.getMessage());
                sink.complete();
            }
        }).subscribeOn(DECISION_LOOP_SCHEDULER);
    }

    @Override
    public Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint) {
        return Flux.<AgentEvent>create(sink -> {
            try {
                TaskId taskId = context.taskId();
                AgentKernel kernel = context.kernel();

                // 从 checkpoint 恢复状态
                ResumeState state = deserializeCheckpoint(checkpoint, context);

                // 恢复预算：如果有 checkpoint 消耗快照，从快照重建而非从零开始
                AtomicReference<BudgetLimits> budgetRef;
                if (state.budgetConsumption() != null) {
                    var bc = state.budgetConsumption();
                    BudgetLimits fresh = context.currentBudgetLimits();
                    budgetRef = new AtomicReference<>(new BudgetLimits(
                        fresh.budget(), bc.usedLLMCalls(), bc.usedToolCalls(),
                        bc.usedTokens(), 0L));
                } else {
                    budgetRef = new AtomicReference<>(context.currentBudgetLimits());
                }
                AtomicReference<GoalSpec> goalRef = new AtomicReference<>(state.goal());

                // 恢复组件（保留已有的上下文管理器和反思触发器）
                ContextWindowManager ctxManager = state.ctxManager();
                SelfReflectionTrigger reflectionTrigger = new SelfReflectionTrigger();

                // 恢复决策历史
                List<LLMDecision> decisionHistory = new ArrayList<>(state.decisionHistory());
                int iteration = state.iteration();

                emitEvent(sink, taskId, EventType.TASK_STARTED,
                    "从 checkpoint 恢复，步数=" + iteration);

                // 进入主决策循环（与 execute 相同）
                // COR-005: 传递 criteriaOrchestrator 实例（而非 null），保持 criteria 生命周期
                // SEC-001: 复用 execute() 构建的 effectiveGuardrail（含 CriteriaGuardrail）
                // F05: 复用 execute() 确认的 verifiedCriteria，保证 post-criteria 生命周期完整
                // Plan: resume 不恢复 plan（checkpoint 序列化留后续），用 empty plan
                GuardrailEngine resumeGuardrail = cachedEffectiveGuardrail != null
                    ? cachedEffectiveGuardrail : guardrailEngine;
                List<VerifiedCriterion> resumeCriteria = cachedVerifiedCriteria != null
                    ? cachedVerifiedCriteria : List.of();
                runDecisionLoop(sink, context, kernel, budgetRef, goalRef,
                    ctxManager, reflectionTrigger, decisionHistory, iteration,
                    resumeCriteria, criteriaOrchestrator, resumeGuardrail,
                    new AtomicReference<>(BetaPlan.empty()), new PlanGenerator());

            } catch (Exception e) {
                emitEvent(sink, context.taskId(), EventType.TASK_FAILED, e.getMessage());
                sink.complete();
            }
        }).subscribeOn(DECISION_LOOP_SCHEDULER);
    }

    // ==================== Checkpoint 序列化 ====================

    /** checkpoint 反序列化后的恢复状态 */
    private record ResumeState(
        GoalSpec goal,
        ContextWindowManager ctxManager,
        List<LLMDecision> decisionHistory,
        int iteration,
        BudgetConsumption budgetConsumption
    ) {}

    /** 预算消耗快照，用于 checkpoint 恢复 */
    private record BudgetConsumption(
        int usedLLMCalls,
        int usedToolCalls,
        long usedTokens
    ) {}

    private ResumeState deserializeCheckpoint(Checkpoint checkpoint, TaskContext context) {
        String json = checkpoint.stateJson();
        if (json == null || json.isBlank() || json.equals("{}")) {
            // 无有效 checkpoint 状态，从头开始
            return new ResumeState(
                GoalSpec.of(context.input().userInput()),
                new ContextWindowManager(CONTEXT_WINDOW_TOKENS),
                List.of(),
                0,
                null
            );
        }

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);

            // 恢复 GoalSpec
            GoalSpec goal = GoalSpec.of(
                node.has("goal") ? node.get("goal").asText() : context.input().userInput()
            );

            // 恢复步数
            int iteration = node.has("iteration") ? node.get("iteration").asInt() : 0;

            // 恢复上下文管理器
            ContextWindowManager ctxManager = new ContextWindowManager(CONTEXT_WINDOW_TOKENS);
            ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                "system", context.agentDefinition().systemPrompt()));
            if (node.has("contextHistory")) {
                for (var msg : node.get("contextHistory")) {
                    ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                        msg.get("role").asText(), msg.get("content").asText()));
                }
            }

            // 恢复决策历史（序列化决策摘要，用于 criteriaVerifier 的关键词匹配）
            List<LLMDecision> decisionHistory = new ArrayList<>();
            if (node.has("decisionSummaries")) {
                for (var summaryNode : node.get("decisionSummaries")) {
                    String summaryText = summaryNode.path("summary").asText("");
                    if (!summaryText.isBlank()) {
                        // 用 ContinueThinking 承载摘要文本，保留关键词用于 criteriaVerifier
                        decisionHistory.add(new LLMDecision.ContinueThinking(
                            "[恢复] " + summaryText, ""));
                    }
                }
            }

            // 恢复预算消耗
            BudgetConsumption consumption = null;
            if (node.has("budgetConsumption")) {
                var bc = node.get("budgetConsumption");
                consumption = new BudgetConsumption(
                    bc.path("usedLLMCalls").asInt(0),
                    bc.path("usedToolCalls").asInt(0),
                    bc.path("usedTokens").asLong(0L)
                );
            }

            return new ResumeState(goal, ctxManager, decisionHistory, iteration, consumption);
        } catch (Exception e) {
            // F06: 反序列化失败时记录原因，而非静默丢弃
            System.getLogger(AutonomousOrchestrator.class.getName())
                .log(System.Logger.Level.WARNING,
                    "Checkpoint 反序列化失败，从头开始: " + e.getMessage());
            return new ResumeState(
                GoalSpec.of(context.input().userInput()),
                new ContextWindowManager(CONTEXT_WINDOW_TOKENS),
                List.of(),
                0,
                null
            );
        }
    }

    // ==================== 主决策循环（extracted from execute） ====================

    private void runDecisionLoop(
            reactor.core.publisher.FluxSink<AgentEvent> sink,
            TaskContext context,
            AgentKernel kernel,
            AtomicReference<BudgetLimits> budgetRef,
            AtomicReference<GoalSpec> goalRef,
            ContextWindowManager ctxManager,
            SelfReflectionTrigger reflectionTrigger,
            List<LLMDecision> decisionHistory,
            int startIteration,
            List<VerifiedCriterion> verifiedCriteria,
            CriteriaOrchestrator criteriaOrch,
            GuardrailEngine effectiveGuardrail,
            AtomicReference<BetaPlan> planRef,
            PlanGenerator planGenerator) {

        TaskId taskId = context.taskId();
        int iteration = startIteration;

        while (iteration < MAX_ITERATIONS) {
            // ===== 步骤 1: 检查预算 =====
            if (budgetRef.get().isExceeded()) {
                emitEvent(sink, taskId, EventType.TASK_FAILED, "预算耗尽");
                sink.complete();
                return;
            }

            // ===== 步骤 2: 检查反思触发 =====
            String reflectionHint = null;
            BudgetLimits currentBudget = budgetRef.get();
            if (reflectionTrigger.checkBudgetTrigger(
                    currentBudget.usedTokens(), currentBudget.budget().maxTokens())) {
                reflectionHint = "预算消耗已过半。请评估当前进展和剩余预算是否匹配。";
                String reflection = kernel.think(
                    reflectionTrigger.buildReflectionPrompt(goalRef.get().goal()), currentBudget).block();
                // 记录反思 LLM 调用的 token 消耗
                if (reflection != null) {
                    budgetRef.set(budgetRef.get().recordLLMCall(estimateTokens(reflection)));
                }
                emitBetaEvent(sink, taskId, new BetaEvent.SelfReflection(
                    taskId, now(), reflectionHint, reflection));
                reflectionTrigger.reset();
            }

            // ===== 步骤 3: 构造决策 Prompt =====
            DecisionPromptBuilder.DecisionContext promptCtx = buildPromptContext(
                goalRef.get(), decisionHistory, budgetRef.get(), ctxManager,
                reflectionHint, iteration, planRef.get());
            String decisionPrompt = promptBuilder.build(promptCtx);

            // ===== 步骤 4: LLM 推理 =====
            String llmResponse = kernel.think(decisionPrompt, budgetRef.get()).block();
            if (llmResponse == null || llmResponse.isBlank()) {
                emitEvent(sink, taskId, EventType.TASK_FAILED, "LLM 返回空响应");
                sink.complete();
                return;
            }

            // 立即记录 LLM 调用消耗（避免 continue 跳过导致预算追踪失真）
            budgetRef.set(budgetRef.get().recordLLMCall(estimateTokens(llmResponse)));

            // ===== 步骤 5: 解析 LLMDecision =====
            LLMDecision decision = decisionParser.parseOrFallback(
                llmResponse, "LLM 输出无法解析为有效决策");

            // ===== 步骤 6: GuardrailEngine 检查 =====
            Guardrail.GuardrailContext guardCtx = new Guardrail.GuardrailContext(
                taskId, budgetRef.get(), List.copyOf(decisionHistory), Map.of());
            Guardrail.GuardrailResult guardResult = effectiveGuardrail.evaluate(decision, guardCtx);

            if (!guardResult.passed()) {
                emitBetaEvent(sink, taskId, new BetaEvent.GuardrailTriggered(
                    taskId, now(), "engine", guardResult.reason()));
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "system", "你的决策被护栏拒绝: " + guardResult.reason()
                        + "。请重新决策。"));
                iteration++;
                continue;
            }

            if (guardResult.modifiedDecision() != null) {
                decision = guardResult.modifiedDecision();
            }

            // ===== 步骤 7: 记录决策 =====
            decisionHistory.add(decision);
            reflectionTrigger.recordDecision(decision); // F02: 跟踪连续工具调用
            emitBetaEvent(sink, taskId, new BetaEvent.DecisionMade(
                taskId, now(), decision, iteration));

            // ===== 步骤 7.5: 计划进度跟踪 =====
            // 非终止决策后自动推进一步（LLM 看到计划，自主执行，我们信任它前进一步）
            BetaPlan currentPlan = planRef.get();
            if (currentPlan.hasPending()
                    && !(decision instanceof LLMDecision.Replan)
                    && !(decision instanceof LLMDecision.Complete)
                    && !(decision instanceof LLMDecision.GiveUp)) {
                planRef.set(currentPlan.markCurrentDone());
            }

            // ===== 步骤 8: Replan 超限处理（B2: ESCALATE） =====
            if (decision instanceof LLMDecision.Replan replan) {
                GoalSpec currentGoal = goalRef.get();
                if (!currentGoal.canReplan()) {
                    emitBetaEvent(sink, taskId, new BetaEvent.GuardrailTriggered(
                        taskId, now(), "replan-limit",
                        "重规划次数已达上限 " + currentGoal.maxReplanCount()));
                    decision = new LLMDecision.GiveUp(
                        "超过最大重规划次数: " + currentGoal.maxReplanCount()
                            + "。最后的原因: " + replan.replanReason(),
                        extractPartialResult(decisionHistory));
                } else {
                    goalRef.set(currentGoal.withReplan(new GoalSpec.ReplanRecord(
                        iteration, replan.replanReason(), replan.newApproach())));
                    // Replan 后重新生成计划
                    BetaPlan revised = planGenerator.generate(kernel, goalRef.get(), budgetRef.get());
                    if (revised.hasPending()) {
                        planRef.set(revised);
                        emitBetaEvent(sink, taskId, new BetaEvent.PlanRevised(
                            taskId, now(), revised));
                    }
                }
            }

            // ===== 步骤 9: Complete 前检查 =====
            if (decision instanceof LLMDecision.Complete) {
                // COR-P2-006: 传入当前预算给 criteriaVerifier
                if (criteriaVerifier instanceof DecisionHistoryCriteriaVerifier dhcv) {
                    dhcv.setBudgetLimits(budgetRef.get());
                }
                List<Violation> uncovered = criteriaVerifier.verify(
                    goalRef.get(), decisionHistory, decision);

                if (!uncovered.isEmpty()) {
                    String feedback = uncovered.stream()
                        .map(Violation::message)
                        .collect(Collectors.joining("; "));
                    ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                        "system", "你的 Complete 决策被阻止。原因：" + feedback
                            + "。请继续执行或 replan。"));
                    emitBetaEvent(sink, taskId, new BetaEvent.GuardrailTriggered(
                        taskId, now(), "criteria-coverage", feedback));
                    decisionHistory.remove(decisionHistory.size() - 1);
                    iteration++;
                    continue;
                }

                // Complete 前强制检查目标漂移（B4）
                var alignment = alignmentCheck.checkAlignment(
                    null, goalRef.get().goal(), decisionHistory, iteration);
                if (alignment.needsIntervention()) {
                    emitBetaEvent(sink, taskId, new BetaEvent.GoalDriftDetected(
                        taskId, now(), alignment));
                    if (alignment.isSevereDrift()) {
                        // 严重漂移：阻止 Complete，回退到循环继续执行
                        ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                            "system", "你的 Complete 被阻止。原因：目标严重偏离。"
                                + (alignment.injection() != null ? alignment.injection() : "")
                                + "。请重新评估或 replan。"));
                        decisionHistory.remove(decisionHistory.size() - 1);
                        iteration++;
                        continue;
                    } else if (alignment.injection() != null) {
                        // 中度漂移：注入警告但允许继续
                        ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                            "system", alignment.injection()));
                    }
                }
            }

            // ===== 步骤 10: 每 N 步检查目标漂移 + 计划进度提醒（B4） =====
            if (!(decision instanceof LLMDecision.Complete) && iteration > 0
                    && iteration % ALIGNMENT_CHECK_INTERVAL == 0) {
                var alignment = alignmentCheck.checkAlignment(
                    null, goalRef.get().goal(), decisionHistory, iteration);
                if (alignment.needsIntervention() && alignment.injection() != null) {
                    ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                        "system", alignment.injection()));
                    emitBetaEvent(sink, taskId, new BetaEvent.GoalDriftDetected(
                        taskId, now(), alignment));
                }

                // 计划进度提醒
                BetaPlan plan = planRef.get();
                if (plan.hasPending()) {
                    long done = plan.steps().stream()
                        .filter(s -> s.status() == com.openjiuwen.runtime.beta.plan.PlanStep.StepStatus.DONE).count();
                    long pending = plan.steps().stream()
                        .filter(s -> s.status() == com.openjiuwen.runtime.beta.plan.PlanStep.StepStatus.PENDING).count();
                    ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                        "system",
                        String.format("[计划进度提醒] 共 %d 步，已完成 %d 步，剩余 %d 步。请按计划执行，或通过 replan 修改计划。",
                            plan.steps().size(), done, pending)));
                }
            }

            // ===== 步骤 11: 执行决策 =====
            boolean completed = executeDecision(
                decision, context, kernel, budgetRef,
                ctxManager, reflectionTrigger, sink, goalRef.get(), decisionHistory);

            if (completed) {
                // === Post-loop: verify → accumulate → maintain ===
                // COR-006: 在 TASK_COMPLETED 之前运行，保证事件顺序
                // COR-010: GiveUp 也触发知识积累（从失败任务学习）
                if (criteriaOrch != null && !verifiedCriteria.isEmpty()
                        && (decision instanceof LLMDecision.Complete
                            || decision instanceof LLMDecision.GiveUp)) {
                    postExecutionCriteriaLifecycle(
                        sink, taskId, criteriaOrch,
                        verifiedCriteria, decisionHistory);
                }

                // COR-006: 统一在 post-criteria 之后发射完成事件
                if (decision instanceof LLMDecision.Complete complete) {
                    emitBetaEvent(sink, taskId, new BetaEvent.BetaCompleted(
                        taskId, now(), complete.output(), true));
                    emitEvent(sink, taskId, EventType.TASK_COMPLETED, complete.output());
                } else if (decision instanceof LLMDecision.GiveUp giveUp) {
                    emitBetaEvent(sink, taskId, new BetaEvent.BetaCompleted(
                        taskId, now(), giveUp.partialResult(), false));
                    emitEvent(sink, taskId, EventType.TASK_FAILED, giveUp.reason());
                }

                sink.complete();
                return;
            }

            iteration++;
        }

        emitEvent(sink, context.taskId(), EventType.TASK_FAILED,
            "超过最大迭代次数: " + MAX_ITERATIONS);
        sink.complete();
    }

    // ==================== 决策执行 ====================

    /**
     * 执行一个 LLM 决策。
     * @return true 表示任务完成（Complete/GiveUp），false 表示继续循环
     */
    private boolean executeDecision(
            LLMDecision decision, TaskContext context,
            AgentKernel kernel, AtomicReference<BudgetLimits> budgetRef,
            ContextWindowManager ctxManager,
            SelfReflectionTrigger reflectionTrigger,
            reactor.core.publisher.FluxSink<AgentEvent> sink,
            GoalSpec goal, List<LLMDecision> decisionHistory) {

        TaskId taskId = context.taskId();

        return switch (decision) {
            case LLMDecision.CallTool ct -> {
                reflectionTrigger.recordToolCall();
                ToolResult result = kernel.invokeTool(
                    ct.toolName(), ct.arguments(), budgetRef.get()).block();
                // 记录工具调用消耗
                budgetRef.set(budgetRef.get().recordToolCall());
                String resultStr = result != null ? String.valueOf(result.result()) : "null";
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "tool", ct.toolName().value() + " -> " + truncate(resultStr, 2000)));
                emitEvent(sink, taskId, EventType.TOOL_CALL, ct.toolName().value());
                yield false;
            }

            case LLMDecision.ContinueThinking think -> {
                reflectionTrigger.recordNonToolDecision();
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "assistant", think.thought()));
                yield false;
            }

            case LLMDecision.SpawnSubTasks spawn -> {
                reflectionTrigger.recordNonToolDecision();
                // SubAgentSpawner 尚未实现（P3 阶段），当前阻止 SpawnSubTasks 决策
                // 向上下文注入提示，让 LLM 改用其他决策类型
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "system", "子任务分解当前不可用。请改用其他决策方式："
                        + "继续思考（continue_thinking）或直接执行工具（call_tool）。"));
                emitBetaEvent(sink, taskId, new BetaEvent.GuardrailTriggered(
                    taskId, now(), "spawn-not-supported",
                    "SpawnSubTasks 当前不可用，SubAgentSpawner 尚未实现"));
                yield false;
            }

            case LLMDecision.RequestHumanHelp help -> {
                reflectionTrigger.recordNonToolDecision();
                kernel.yield(taskId,
                    new YieldReason.WaitingForExternalInput("human", help.question()),
                    "{}").block();
                emitEvent(sink, taskId, EventType.TASK_PAUSED, help.question());
                yield false;
            }

            case LLMDecision.Replan replan -> {
                reflectionTrigger.recordNonToolDecision();
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "system", "[Replan 记录] 原因: " + replan.replanReason()
                        + " | 新策略: " + replan.newApproach()));
                yield false;
            }

            case LLMDecision.Complete complete -> {
                reflectionTrigger.recordNonToolDecision();
                // COR-006: 不在此处发射 TASK_COMPLETED，由 completed 块统一发射
                yield true;
            }

            case LLMDecision.GiveUp giveUp -> {
                reflectionTrigger.recordNonToolDecision();
                yield true;
            }
        };
    }

    // ==================== Prompt 构造辅助 ====================

    private DecisionPromptBuilder.DecisionContext buildPromptContext(
            GoalSpec goal, List<LLMDecision> history,
            BudgetLimits budgetLimits, ContextWindowManager ctxManager,
            String reflectionHint, int iteration,
            BetaPlan plan) {

        return new DecisionPromptBuilder.DecisionContext(
            goal.goal(),
            formatSuccessCriteria(goal.successCriteria()),
            formatBudget(budgetLimits),
            ctxManager.buildCompressedHistory(),
            formatToolList(goal.context()),
            reflectionHint,
            goal.replanCount(),
            goal.maxReplanCount(),
            iteration,
            plan.formatForPrompt()
        );
    }

    private String formatSuccessCriteria(List<String> criteria) {
        if (criteria == null || criteria.isEmpty()) return "无显式成功标准";
        StringBuilder sb = new StringBuilder("必须满足以下所有条件：\n");
        for (int i = 0; i < criteria.size(); i++) {
            sb.append(i + 1).append(". ").append(criteria.get(i)).append("\n");
        }
        return sb.toString();
    }

    private String formatBudget(BudgetLimits bl) {
        return String.format(
            "Token: %d / %d | LLM调用: %d / %d | 工具调用: %d / %d",
            bl.usedTokens(), bl.budget().maxTokens(),
            bl.usedLLMCalls(), bl.budget().maxLLMCalls(),
            bl.usedToolCalls(), bl.budget().maxToolCalls());
    }

    private String formatToolList(Map<String, String> context) {
        String tools = context.getOrDefault("availableTools", "");
        return tools.isBlank() ? "请参考系统提示中的工具列表" : tools;
    }

    private String extractPartialResult(List<LLMDecision> history) {
        return history.stream()
            .filter(d -> d instanceof LLMDecision.Complete)
            .map(d -> ((LLMDecision.Complete) d).output())
            .reduce((a, b) -> b)
            .orElse("无部分结果");
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "...(truncated)" : s;
    }

    // ==================== Criteria 生命周期辅助 ====================

    /**
     * Post-execution criteria lifecycle: verify → accumulate → maintain。
     * 在 sink.complete() 前调用，可以继续发射事件。
     */
    private void postExecutionCriteriaLifecycle(
            reactor.core.publisher.FluxSink<AgentEvent> sink,
            TaskId taskId,
            CriteriaOrchestrator orchestrator,
            List<VerifiedCriterion> verifiedCriteria,
            List<LLMDecision> decisionHistory) {

        String finalOutput = extractPartialResult(decisionHistory);
        String execLog = buildExecutionLog(decisionHistory);

        // Step 4: 深度验证
        List<CriteriaVerificationResult> results =
            orchestrator.verify(verifiedCriteria, finalOutput, execLog);

        emitBetaEvent(sink, taskId, new BetaEvent.CriteriaVerificationCompleted(
            taskId, now(),
            results.stream()
                .filter(r -> !r.isSatisfied())
                .<Violation>map(r -> new Violation.CriteriaNotCovered(
                    r.criterion().dimension(), r.evidence()))
                .toList(),
            orchestrator.isAllSatisfied(results)));

        // Step 5: 知识沉淀
        if (!results.isEmpty()) {
            orchestrator.accumulate(verifiedCriteria, results,
                StructuredCriteria.Industry.GENERAL);
            emitBetaEvent(sink, taskId, new BetaEvent.KnowledgeDeposited(
                taskId, now(), "criteria-verification",
                "通过率: " + (orchestrator.isAllSatisfied(results) ? "100%" : "部分未通过")));
        }

        // Step 6: 知识维护
        orchestrator.maintain();
    }

    private String buildExecutionLog(List<LLMDecision> history) {
        return history.stream()
            .map(d -> switch (d) {
                case LLMDecision.CallTool ct -> "工具调用: " + ct.toolName().value();
                case LLMDecision.ContinueThinking t -> "思考: " + truncate(t.thought(), 200);
                case LLMDecision.Replan r -> "重规划: " + r.replanReason();
                default -> "";
            })
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining("\n"));
    }

    // ==================== 事件辅助 ====================

    private Instant now() { return Instant.now(); }

    private void emitEvent(reactor.core.publisher.FluxSink<AgentEvent> sink,
                           TaskId taskId, EventType type, String message) {
        sink.next(AgentEvent.of(taskId, type, message));
    }

    private void emitBetaEvent(reactor.core.publisher.FluxSink<AgentEvent> sink,
                               TaskId taskId, BetaEvent beta) {
        sink.next(AgentEvent.of(taskId, mapBetaEventType(beta), beta.toString()));
    }

    private EventType mapBetaEventType(BetaEvent beta) {
        return switch (beta) {
            case BetaEvent.GoalAnalyzed ga              -> EventType.GOAL_ANALYZED;
            case BetaEvent.DecisionMade dm              -> EventType.DECISION_MADE;
            case BetaEvent.GuardrailTriggered gt        -> EventType.GUARDRAIL_TRIGGERED;
            case BetaEvent.SelfReflection sr            -> EventType.SELF_REFLECTION;
            case BetaEvent.ContextCompacted cc          -> EventType.CONTEXT_COMPACTED;
            case BetaEvent.GoalReprioritized gr         -> EventType.GOAL_REPRIORITIZED;
            case BetaEvent.SubAgentSpawned sa           -> EventType.SPAWN_SUB_AGENT;
            case BetaEvent.BetaCompleted bc             -> EventType.TASK_COMPLETED;
            case BetaEvent.ReplanRequested rr           -> EventType.REPLAN_REQUESTED;
            case BetaEvent.ReplanAssessed ra            -> EventType.REPLAN_ASSESSED;
            case BetaEvent.GoalDriftDetected gd         -> EventType.GOAL_DRIFT_DETECTED;
            case BetaEvent.CriteriaVerificationCompleted cv -> EventType.CRITERIA_VERIFIED;
            case BetaEvent.KnowledgeDeposited kd        -> EventType.KNOWLEDGE_DEPOSITED;
            case BetaEvent.PlanGenerated pg             -> EventType.BETA_PLAN_GENERATED;
            case BetaEvent.PlanRevised pr               -> EventType.BETA_PLAN_REVISED;
        };
    }
}
