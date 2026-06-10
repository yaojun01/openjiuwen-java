package com.openjiuwen.runtime.beta.event;

import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.beta.reflection.GoalAlignmentCheck;
import com.openjiuwen.runtime.beta.reflection.ReplanFeasibilityCheck;
import com.openjiuwen.core.kernel.model.TaskId;
import com.openjiuwen.core.kernel.model.Violation;

import java.time.Instant;
import java.util.List;

/**
 * Beta 策略事件 v2——LLM 自主编排的执行过程事件。
 *
 * v2 新增：
 * - ReplanRequested: LLM 发起重规划
 * - ReplanAssessed: 重规划可行性评估结果
 * - GoalDriftDetected: 目标漂移检测
 * - CriteriaVerificationCompleted: 标准验证完成
 * - KnowledgeDeposited: 知识沉淀
 */
public sealed interface BetaEvent
    permits BetaEvent.GoalAnalyzed,
            BetaEvent.DecisionMade,
            BetaEvent.GuardrailTriggered,
            BetaEvent.SelfReflection,
            BetaEvent.ContextCompacted,
            BetaEvent.GoalReprioritized,
            BetaEvent.SubAgentSpawned,
            BetaEvent.BetaCompleted,
            BetaEvent.ReplanRequested,
            BetaEvent.ReplanAssessed,
            BetaEvent.GoalDriftDetected,
            BetaEvent.CriteriaVerificationCompleted,
            BetaEvent.KnowledgeDeposited {

    TaskId taskId();
    Instant timestamp();

    /**
     * 目标分析完成。
     */
    record GoalAnalyzed(TaskId taskId, Instant timestamp, GoalSpec goal,
                        List<String> requiredCapabilities) implements BetaEvent {}

    /**
     * LLM 做出决策。
     */
    record DecisionMade(TaskId taskId, Instant timestamp, LLMDecision decision,
                        int iterationCount) implements BetaEvent {}

    /**
     * 护栏被触发（决策被拒绝或修改）。
     */
    record GuardrailTriggered(TaskId taskId, Instant timestamp,
                              String guardrailName, String reason) implements BetaEvent {}

    /**
     * 自我反思触发。
     */
    record SelfReflection(TaskId taskId, Instant timestamp,
                          String reflectionPrompt, String reflectionResult) implements BetaEvent {}

    /**
     * 上下文被压缩。
     */
    record ContextCompacted(TaskId taskId, Instant timestamp,
                            int messagesBefore, int messagesAfter) implements BetaEvent {}

    /**
     * 目标被重新排序。
     */
    record GoalReprioritized(TaskId taskId, Instant timestamp,
                             List<GoalSpec> reprioritizedGoals) implements BetaEvent {}

    /**
     * 子 Agent 被生成。
     */
    record SubAgentSpawned(TaskId taskId, Instant timestamp,
                           TaskId subTaskId, GoalSpec subGoal) implements BetaEvent {}

    /**
     * Beta 策略执行完成。
     */
    record BetaCompleted(TaskId taskId, Instant timestamp,
                         String output, boolean success) implements BetaEvent {}

    /**
     * LLM 发起重规划。
     */
    record ReplanRequested(TaskId taskId, Instant timestamp,
                           String reason, String newApproach,
                           int replanCount, int maxReplanCount) implements BetaEvent {}

    /**
     * 重规划可行性评估结果。
     */
    record ReplanAssessed(TaskId taskId, Instant timestamp,
                          ReplanFeasibilityCheck.FeasibilityResult result) implements BetaEvent {}

    /**
     * 目标漂移检测。
     */
    record GoalDriftDetected(TaskId taskId, Instant timestamp,
                             GoalAlignmentCheck.AlignmentResult result) implements BetaEvent {}

    /**
     * 标准验证完成。
     */
    record CriteriaVerificationCompleted(TaskId taskId, Instant timestamp,
                                         List<Violation> violations,
                                         boolean allPassed) implements BetaEvent {}

    /**
     * 知识沉淀。
     */
    record KnowledgeDeposited(TaskId taskId, Instant timestamp,
                              String goalPattern,
                              String executionSummary) implements BetaEvent {}
}
