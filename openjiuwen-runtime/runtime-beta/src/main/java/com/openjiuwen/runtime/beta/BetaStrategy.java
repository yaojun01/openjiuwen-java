package com.openjiuwen.runtime.beta;
/**
 * ============================================================
 *  P2 DRAFT -- NOT part of P1 default compilation.
 *
 * This file belongs to the `runtime-beta` module, which is excluded from
 * P1's default Maven profile. It is only compiled with `-P all`.
 *
 * P2 will replace this draft with the final implementation.
 * See: docs/architecture/05-beta-llm-autonomous-orchestration.md
 * ============================================================
 */

import com.openjiuwen.runtime.beta.context.ContextWindowManager;
import com.openjiuwen.runtime.beta.context.SelfReflectionTrigger;
import com.openjiuwen.runtime.beta.event.BetaEvent;
import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.runtime.core.engine.DefaultSafetyBoundary;
import com.openjiuwen.runtime.core.engine.SafetyBoundary;
import com.openjiuwen.core.kernel.model.*;
import com.openjiuwen.runtime.core.dispatch.ExecutionStrategy;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;

/**
 * Beta 策略——LLM 自主编排。
 *
 * 与 Alpha（PEV 显式控制）不同，Beta 策略让 LLM 自主决定：
 * - 调什么工具、传什么参数
 * - 是否需要分解目标
 * - 何时停止、何时请求帮助
 * - 何时自我反思
 *
 * 安全网：
 * - GuardrailEngine 在每个决策前检查
 * - SafetyBoundary 在工具调用前后检查
 * - Budget 限制总资源消耗
 * - SelfReflectionTrigger 防止 LLM 陷入循环
 *
 * 执行模型：
 * - 不是 ReAct 循环，而是"决策循环"
 * - LLM 每次返回一个 LLMDecision
 * - 系统根据决策类型执行对应操作
 * - 循环直到 LLM 返回 Complete 或 GiveUp
 */
@Component("beta")
public class BetaStrategy implements ExecutionStrategy {

    private static final int MAX_ITERATIONS = 50;
    private static final int CONTEXT_WINDOW_TOKENS = 128_000;

    private final GuardrailEngine guardrailEngine;
    private final SafetyBoundary safetyBoundary;

    public BetaStrategy(GuardrailEngine guardrailEngine) {
        this.guardrailEngine = guardrailEngine;
        this.safetyBoundary = new DefaultSafetyBoundary();
    }

    public BetaStrategy(GuardrailEngine guardrailEngine, SafetyBoundary safetyBoundary) {
        this.guardrailEngine = guardrailEngine;
        this.safetyBoundary = safetyBoundary;
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
                BudgetLimits budgetLimits = context.currentBudgetLimits();

                // 初始化上下文管理器和反思触发器
                ContextWindowManager ctxManager = new ContextWindowManager(CONTEXT_WINDOW_TOKENS);
                SelfReflectionTrigger reflectionTrigger = new SelfReflectionTrigger();

                // 分析目标
                GoalSpec goal = GoalSpec.of(context.input().userInput());
                sink.next(toAgentEvent(taskId, new BetaEvent.GoalAnalyzed(
                    taskId, now(), goal, List.of())));

                // 初始化上下文
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "system", context.agentDefinition().systemPrompt()));

                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "user", context.input().userInput()));

                // ========== 决策循环 ==========
                List<LLMDecision> decisionHistory = new ArrayList<>();
                int iteration = 0;

                while (iteration < MAX_ITERATIONS) {
                    // 检查预算
                    if (budgetLimits.isExceeded()) {
                        sink.next(toAgentEvent(taskId, AgentEvent.of(
                            taskId, EventType.TASK_FAILED, "预算耗尽")));
                        sink.complete();
                        return;
                    }

                    // 检查是否需要自我反思
                    if (reflectionTrigger.checkBudgetTrigger(
                            budgetLimits.usedTokens(), budgetLimits.budget().maxTokens())) {
                        String reflectionPrompt = reflectionTrigger.buildReflectionPrompt(goal.goal());
                        String reflection = kernel.think(reflectionPrompt, budgetLimits).block();
                        sink.next(toAgentEvent(taskId, new BetaEvent.SelfReflection(
                            taskId, now(), reflectionPrompt, reflection)));
                        reflectionTrigger.reset();
                    }

                    // 构建决策 prompt
                    String decisionPrompt = buildDecisionPrompt(ctxManager, goal, decisionHistory);

                    // 调用 LLM 获取决策
                    String llmResponse = kernel.think(decisionPrompt, budgetLimits).block();
                    if (llmResponse == null) {
                        sink.next(toAgentEvent(taskId, AgentEvent.of(
                            taskId, EventType.TASK_FAILED, "LLM 返回空响应")));
                        sink.complete();
                        return;
                    }

                    // 解析决策
                    LLMDecision decision = parseDecision(llmResponse);

                    // 护栏检查
                    Guardrail.GuardrailContext guardCtx = new Guardrail.GuardrailContext(
                        taskId, budgetLimits, List.copyOf(decisionHistory), Map.of());
                    Guardrail.GuardrailResult guardResult = guardrailEngine.evaluate(decision, guardCtx);

                    if (!guardResult.passed()) {
                        sink.next(toAgentEvent(taskId, new BetaEvent.GuardrailTriggered(
                            taskId, now(), "engine", guardResult.reason())));
                        // 将拒绝原因反馈给 LLM，让它重新决策
                        ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                            "system", "你的决策被护栏拒绝: " + guardResult.reason() + "。请重新决策。"));
                        iteration++;
                        continue;
                    }

                    // 使用可能的修改后决策
                    if (guardResult.modifiedDecision() != null) {
                        decision = guardResult.modifiedDecision();
                    }

                    // 记录决策
                    decisionHistory.add(decision);
                    sink.next(toAgentEvent(taskId, new BetaEvent.DecisionMade(
                        taskId, now(), decision, iteration)));

                    // 根据决策类型执行
                    boolean completed = executeDecision(decision, context, kernel, budgetLimits,
                                                         ctxManager, reflectionTrigger, sink,
                                                         goal, decisionHistory);
                    if (completed) {
                        sink.complete();
                        return;
                    }

                    iteration++;
                }

                // 超过最大迭代次数
                sink.next(toAgentEvent(taskId, AgentEvent.of(
                    taskId, EventType.TASK_FAILED, "超过最大迭代次数: " + MAX_ITERATIONS)));
                sink.complete();

            } catch (Exception e) {
                sink.next(toAgentEvent(context.taskId(),
                    AgentEvent.of(context.taskId(), EventType.TASK_FAILED, e.getMessage())));
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint) {
        return execute(context);
    }

    // ==================== 决策执行 ====================

    /**
     * 执行一个 LLM 决策。
     * @return true 表示任务完成（Complete/GiveUp），false 表示继续循环
     */
    private boolean executeDecision(LLMDecision decision, TaskContext context,
                                     AgentKernel kernel, BudgetLimits budgetLimits,
                                     ContextWindowManager ctxManager,
                                     SelfReflectionTrigger reflectionTrigger,
                                     reactor.core.publisher.FluxSink<AgentEvent> sink,
                                     GoalSpec goal,
                                     List<LLMDecision> decisionHistory) {
        TaskId taskId = context.taskId();

        return switch (decision) {
            case LLMDecision.CallTool ct -> {
                reflectionTrigger.recordToolCall();
                ToolResult result = kernel.invokeTool(ct.toolName(), ct.arguments(), budgetLimits).block();
                String resultStr = result != null ? String.valueOf(result.result()) : "null";
                ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                    "tool", ct.toolName() + " -> " + resultStr));
                sink.next(toAgentEvent(taskId, AgentEvent.of(taskId, EventType.TOOL_CALL,
                    ct.toolName().value())));
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
                    sink.next(toAgentEvent(taskId, new BetaEvent.SubAgentSpawned(
                        taskId, now(), subTaskId, subGoal)));
                    // 简化：递归执行子目标
                }
                yield false;
            }

            case LLMDecision.RequestHumanHelp help -> {
                reflectionTrigger.recordNonToolDecision();
                kernel.yield(taskId, new YieldReason.WaitingForExternalInput(
                    "human", help.question()), "{}").block();
                sink.next(toAgentEvent(taskId, AgentEvent.of(taskId, EventType.TASK_PAUSED,
                    help.question())));
                yield false; // 暂停后等外部恢复
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

                // === W3 修复：FinalAnswer 前检查 successCriteria 覆盖情况 ===
                if (goal.successCriteria() != null && !goal.successCriteria().isEmpty()) {
                    List<String> verified = extractVerifiedCriteria(decisionHistory);
                    List<Violation> uncovered = safetyBoundary.checkCriteriaCoverageAll(
                        taskId, goal.successCriteria(), verified);

                    if (!uncovered.isEmpty()) {
                        // 有标准未覆盖 → 阻止 Complete，反馈给 LLM
                        String feedback = uncovered.stream()
                            .map(Violation::message)
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("存在未覆盖的成功标准");
                        ctxManager.addMessage(new ContextWindowManager.ContextMessage(
                            "system", "你的 FinalAnswer 被阻止。原因：" + feedback
                                + "。请继续执行或 replan，确保覆盖所有成功标准。"));
                        sink.next(toAgentEvent(taskId, new BetaEvent.GuardrailTriggered(
                            taskId, now(), "criteria-coverage", feedback)));
                        yield false; // 不完成，继续循环
                    }
                }

                sink.next(toAgentEvent(taskId, new BetaEvent.BetaCompleted(
                    taskId, now(), complete.output(), true)));
                sink.next(toAgentEvent(taskId, AgentEvent.of(taskId, EventType.TASK_COMPLETED,
                    complete.output())));
                yield true;
            }

            case LLMDecision.GiveUp giveUp -> {
                reflectionTrigger.recordNonToolDecision();
                sink.next(toAgentEvent(taskId, new BetaEvent.BetaCompleted(
                    taskId, now(), giveUp.partialResult(), false)));
                sink.next(toAgentEvent(taskId, AgentEvent.of(taskId, EventType.TASK_FAILED,
                    giveUp.reason())));
                yield true;
            }
        };
    }

    // ==================== 辅助方法 ====================

    private String buildDecisionPrompt(ContextWindowManager ctxManager, GoalSpec goal,
                                        List<LLMDecision> history) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前上下文:\n");
        for (var msg : ctxManager.messages()) {
            prompt.append(msg.role()).append(": ").append(msg.content()).append("\n");
        }
        prompt.append("\n目标: ").append(goal.goal()).append("\n");
        prompt.append("已做 ").append(history.size()).append(" 次决策。\n");
        prompt.append("请做出下一步决策。\n");
        return prompt.toString();
    }

    private LLMDecision parseDecision(String llmResponse) {
        // 简化实现：实际需要结构化解析 LLM 输出
        // 默认当作完成
        return new LLMDecision.Complete(llmResponse, 0.8, "完成");
    }

    private Instant now() { return Instant.now(); }

    private AgentEvent toAgentEvent(TaskId taskId, BetaEvent beta) {
        return AgentEvent.of(taskId, mapBetaEventType(beta), beta.toString());
    }

    private AgentEvent toAgentEvent(TaskId taskId, AgentEvent event) { return event; }

    private EventType mapBetaEventType(BetaEvent beta) {
        return switch (beta) {
            case BetaEvent.GoalAnalyzed ga -> EventType.GOAL_ANALYZED;
            case BetaEvent.DecisionMade dm -> EventType.DECISION_MADE;
            case BetaEvent.GuardrailTriggered gt -> EventType.GUARDRAIL_TRIGGERED;
            case BetaEvent.SelfReflection sr -> EventType.SELF_REFLECTION;
            case BetaEvent.ContextCompacted cc -> EventType.CONTEXT_COMPACTED;
            case BetaEvent.GoalReprioritized gr -> EventType.GOAL_REPRIORITIZED;
            case BetaEvent.SubAgentSpawned sa -> EventType.SPAWN_SUB_AGENT;
            case BetaEvent.BetaCompleted bc -> EventType.TASK_COMPLETED;
            case BetaEvent.ReplanRequested rr -> EventType.REPLAN_REQUESTED;
            case BetaEvent.ReplanAssessed ra -> EventType.REPLAN_ASSESSED;
            case BetaEvent.GoalDriftDetected gd -> EventType.GOAL_DRIFT_DETECTED;
            case BetaEvent.CriteriaVerificationCompleted cv -> EventType.CRITERIA_VERIFIED;
            case BetaEvent.KnowledgeDeposited kd -> EventType.KNOWLEDGE_DEPOSITED;
        };
    }

    /**
     * 从决策历史中提取已验证的标准。
     *
     * W3 修复核心逻辑：
     * - CallTool 决策中 reasoning 包含标准关键词 → 标记为已验证
     * - ContinueThinking 中 thought 包含标准关键词 → 标记为已验证
     *
     * 生产环境应使用 LLM-as-Judge 做更精确的判断，
     * 这里用字符串匹配作为确定性的 baseline。
     */
    private List<String> extractVerifiedCriteria(List<LLMDecision> history) {
        List<String> verified = new ArrayList<>();
        for (LLMDecision decision : history) {
            switch (decision) {
                case LLMDecision.CallTool ct -> {
                    if (ct.reasoning() != null) verified.add(ct.reasoning());
                    if (ct.toolName() != null) verified.add(ct.toolName().value());
                }
                case LLMDecision.ContinueThinking ct -> {
                    if (ct.thought() != null) verified.add(ct.thought());
                }
                case LLMDecision.Complete c -> {
                    if (c.summary() != null) verified.add(c.summary());
                }
                default -> {}
            }
        }
        return verified;
    }
}
