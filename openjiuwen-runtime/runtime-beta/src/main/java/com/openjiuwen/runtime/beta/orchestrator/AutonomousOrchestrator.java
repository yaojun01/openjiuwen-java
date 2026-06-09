package com.openjiuwen.runtime.beta.orchestrator;

import com.openjiuwen.runtime.beta.context.ContextWindowManager;
import com.openjiuwen.runtime.beta.context.SelfReflectionTrigger;
import com.openjiuwen.runtime.beta.event.BetaEvent;
import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.beta.verification.CriteriaVerifier;
import com.openjiuwen.runtime.beta.verification.DefaultCriteriaVerifier;
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

    private final GuardrailEngine guardrailEngine;
    private final DecisionParser decisionParser;
    private final DecisionPromptBuilder promptBuilder;
    private final CriteriaVerifier criteriaVerifier;

    public AutonomousOrchestrator(GuardrailEngine guardrailEngine) {
        this.guardrailEngine = guardrailEngine;
        this.decisionParser = new JsonDecisionParser();
        this.promptBuilder = new DefaultDecisionPromptBuilder();
        this.criteriaVerifier = new DefaultCriteriaVerifier();
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

                // 使用 AtomicReference 包装可变状态（lambda 内需要重新赋值）
                AtomicReference<BudgetLimits> budgetRef = new AtomicReference<>(
                    context.currentBudgetLimits());
                AtomicReference<GoalSpec> goalRef = new AtomicReference<>(
                    GoalSpec.of(context.input().userInput()));

                // 初始化组件
                ContextWindowManager ctxManager = new ContextWindowManager(CONTEXT_WINDOW_TOKENS);
                SelfReflectionTrigger reflectionTrigger = new SelfReflectionTrigger();

                // 分析目标
                emitBetaEvent(sink, taskId, new BetaEvent.GoalAnalyzed(
                    taskId, now(), goalRef.get(), List.of()));

                // 初始化上下文：系统提示 + 用户输入
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "system", context.agentDefinition().systemPrompt()));
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "user", context.input().userInput()));

                // ========== 主决策循环 ==========
                List<LLMDecision> decisionHistory = new ArrayList<>();
                int iteration = 0;

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
                        emitBetaEvent(sink, taskId, new BetaEvent.SelfReflection(
                            taskId, now(), reflectionHint, reflection));
                        reflectionTrigger.reset();
                    }

                    // ===== 步骤 3: 构造决策 Prompt =====
                    DecisionPromptBuilder.DecisionContext promptCtx = buildPromptContext(
                        goalRef.get(), decisionHistory, budgetRef.get(), ctxManager,
                        reflectionHint, iteration);
                    String decisionPrompt = promptBuilder.build(promptCtx);

                    // ===== 步骤 4: LLM 推理 =====
                    String llmResponse = kernel.think(decisionPrompt, budgetRef.get()).block();
                    if (llmResponse == null || llmResponse.isBlank()) {
                        emitEvent(sink, taskId, EventType.TASK_FAILED, "LLM 返回空响应");
                        sink.complete();
                        return;
                    }

                    // ===== 步骤 5: 解析 LLMDecision =====
                    LLMDecision decision = decisionParser.parseOrFallback(
                        llmResponse, "LLM 输出无法解析为有效决策");

                    // ===== 步骤 6: GuardrailEngine 检查 =====
                    Guardrail.GuardrailContext guardCtx = new Guardrail.GuardrailContext(
                        taskId, budgetRef.get(), List.copyOf(decisionHistory), Map.of());
                    Guardrail.GuardrailResult guardResult = guardrailEngine.evaluate(decision, guardCtx);

                    if (!guardResult.passed()) {
                        // 护栏拦截 → 反馈给 LLM，让它重新决策
                        emitBetaEvent(sink, taskId, new BetaEvent.GuardrailTriggered(
                            taskId, now(), "engine", guardResult.reason()));
                        ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                            "system", "你的决策被护栏拒绝: " + guardResult.reason()
                                + "。请重新决策。"));
                        iteration++;
                        continue;
                    }

                    // 使用护栏可能修改后的决策
                    if (guardResult.modifiedDecision() != null) {
                        decision = guardResult.modifiedDecision();
                    }

                    // ===== 步骤 7: 记录决策 =====
                    decisionHistory.add(decision);
                    emitBetaEvent(sink, taskId, new BetaEvent.DecisionMade(
                        taskId, now(), decision, iteration));

                    // ===== 步骤 8: 执行决策 =====
                    // 对于 Replan 决策，先检查 replan 超限
                    if (decision instanceof LLMDecision.Replan replan) {
                        GoalSpec currentGoal = goalRef.get();
                        if (!currentGoal.canReplan()) {
                            // 超限处理：转降级为 GiveUp
                            emitBetaEvent(sink, taskId, new BetaEvent.GuardrailTriggered(
                                taskId, now(), "replan-limit",
                                "重规划次数已达上限 " + currentGoal.maxReplanCount()));
                            decision = new LLMDecision.GiveUp(
                                "超过最大重规划次数: " + currentGoal.maxReplanCount()
                                    + "。最后的原因: " + replan.replanReason(),
                                extractPartialResult(decisionHistory));
                        } else {
                            // 记录 replan 到 GoalSpec
                            goalRef.set(currentGoal.withReplan(new GoalSpec.ReplanRecord(
                                iteration, replan.replanReason(), replan.newApproach())));
                        }
                    }

                    // 对于 Complete 决策，在执行前检查 successCriteria
                    if (decision instanceof LLMDecision.Complete) {
                        List<Violation> uncovered = criteriaVerifier.verify(
                            goalRef.get(), decisionHistory, decision);

                        if (!uncovered.isEmpty()) {
                            // 有标准未覆盖 → 阻止 Complete，反馈给 LLM
                            String feedback = uncovered.stream()
                                .map(Violation::message)
                                .collect(Collectors.joining("; "));
                            ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                                "system", "你的 Complete 决策被阻止。原因：" + feedback
                                    + "。请继续执行或 replan。"));
                            emitBetaEvent(sink, taskId, new BetaEvent.GuardrailTriggered(
                                taskId, now(), "criteria-coverage", feedback));
                            // 从历史中移除这个被阻止的 Complete
                            decisionHistory.remove(decisionHistory.size() - 1);
                            iteration++;
                            continue;
                        }
                    }

                    // 执行决策并检查是否终止
                    boolean completed = executeDecision(
                        decision, context, kernel, budgetRef.get(),
                        ctxManager, reflectionTrigger, sink, goalRef.get(), decisionHistory);

                    // 更新预算追踪（LLM 调用已消费）
                    budgetRef.set(budgetRef.get().recordLLMCall(estimateTokens(llmResponse)));

                    if (completed) {
                        sink.complete();
                        return;
                    }

                    iteration++;
                }

                // 超过最大迭代次数
                emitEvent(sink, context.taskId(), EventType.TASK_FAILED,
                    "超过最大迭代次数: " + MAX_ITERATIONS);
                sink.complete();

            } catch (Exception e) {
                emitEvent(sink, context.taskId(), EventType.TASK_FAILED, e.getMessage());
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint) {
        // TODO: 从 checkpoint 恢复 decisionHistory 和 goal 状态
        return execute(context);
    }

    // ==================== 决策执行 ====================

    /**
     * 执行一个 LLM 决策。
     * @return true 表示任务完成（Complete/GiveUp），false 表示继续循环
     */
    private boolean executeDecision(
            LLMDecision decision, TaskContext context,
            AgentKernel kernel, BudgetLimits budgetLimits,
            ContextWindowManager ctxManager,
            SelfReflectionTrigger reflectionTrigger,
            reactor.core.publisher.FluxSink<AgentEvent> sink,
            GoalSpec goal, List<LLMDecision> decisionHistory) {

        TaskId taskId = context.taskId();

        return switch (decision) {
            case LLMDecision.CallTool ct -> {
                reflectionTrigger.recordToolCall();
                ToolResult result = kernel.invokeTool(
                    ct.toolName(), ct.arguments(), budgetLimits).block();
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
                for (GoalSpec subGoal : spawn.subGoals()) {
                    TaskId subTaskId = TaskId.generate();
                    emitBetaEvent(sink, taskId, new BetaEvent.SubAgentSpawned(
                        taskId, now(), subTaskId, subGoal));
                    // 子 Agent 的递归执行由 SubAgentSpawner 负责
                }
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
                emitBetaEvent(sink, taskId, new BetaEvent.BetaCompleted(
                    taskId, now(), complete.output(), true));
                emitEvent(sink, taskId, EventType.TASK_COMPLETED, complete.output());
                yield true;
            }

            case LLMDecision.GiveUp giveUp -> {
                reflectionTrigger.recordNonToolDecision();
                emitBetaEvent(sink, taskId, new BetaEvent.BetaCompleted(
                    taskId, now(), giveUp.partialResult(), false));
                emitEvent(sink, taskId, EventType.TASK_FAILED, giveUp.reason());
                yield true;
            }
        };
    }

    // ==================== Prompt 构造辅助 ====================

    private DecisionPromptBuilder.DecisionContext buildPromptContext(
            GoalSpec goal, List<LLMDecision> history,
            BudgetLimits budgetLimits, ContextWindowManager ctxManager,
            String reflectionHint, int iteration) {

        return new DecisionPromptBuilder.DecisionContext(
            goal.goal(),
            formatSuccessCriteria(goal.successCriteria()),
            formatBudget(budgetLimits),
            ctxManager.buildCompressedHistory(),
            formatToolList(goal.context()),
            reflectionHint,
            goal.replanCount(),
            goal.maxReplanCount(),
            iteration
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
        };
    }
}
