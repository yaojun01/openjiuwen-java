package com.openjiuwen.runtime.beta.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Beta 策略的可变步骤计划。
 *
 * <p>与 Alpha 的 TaskGraph（静态 DAG）本质不同：
 * <ul>
 *   <li>扁平有序列表，不是 DAG</li>
 *   <li>可变——步骤可以在执行中被替换（revised）</li>
 *   <li>没有并行——ReAct loop 串行执行</li>
 *   <li>没有数据流引用——步骤之间没有 ${nodeId.output}</li>
 * </ul>
 *
 * <p>计划是 LLM 的<strong>参考</strong>，不是执行的<strong>契约</strong>。
 * LLM 看到计划和进度，自主决定每一步做什么。
 *
 * @param steps         有序步骤列表（immutable copy）
 * @param revisionCount 计划被修订的次数
 */
public record BetaPlan(List<PlanStep> steps, int revisionCount) {

    public BetaPlan {
        steps = List.copyOf(steps);
    }

    /** 空计划——无步骤，不阻塞执行。 */
    public static BetaPlan empty() {
        return new BetaPlan(List.of(), 0);
    }

    /** 渲染计划为 LLM 可读的进度视图。 */
    public String formatForPrompt() {
        if (steps.isEmpty()) return "";

        int current = currentStepIndex();
        StringBuilder sb = new StringBuilder();
        for (PlanStep step : steps) {
            sb.append("[").append(step.index() + 1).append("/").append(steps.size()).append("] ");
            if (step.status() == PlanStep.StepStatus.DONE) {
                sb.append("DONE     - ").append(step.description());
            } else if (step.status() == PlanStep.StepStatus.SKIPPED) {
                sb.append("SKIPPED  - ").append(step.description());
            } else if (step.index() == current) {
                sb.append(">>>      - ").append(step.description());
            } else {
                sb.append("PENDING  - ").append(step.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 标记当前步骤为 DONE，返回新 BetaPlan。 */
    public BetaPlan markCurrentDone() {
        int idx = currentStepIndex();
        if (idx < 0) return this;

        List<PlanStep> updated = new ArrayList<>();
        for (PlanStep s : steps) {
            updated.add(s.index() == idx ? s.withStatus(PlanStep.StepStatus.DONE) : s);
        }
        return new BetaPlan(updated, revisionCount);
    }

    /** 替换整个步骤列表（修订），revisionCount +1。 */
    public BetaPlan withRevisedSteps(List<PlanStep> newSteps) {
        // 重新编号
        List<PlanStep> reindexed = new ArrayList<>();
        for (int i = 0; i < newSteps.size(); i++) {
            PlanStep s = newSteps.get(i);
            reindexed.add(new PlanStep(i, s.description(), s.status()));
        }
        return new BetaPlan(reindexed, revisionCount + 1);
    }

    /** 第一个 PENDING 步骤的 index。-1 表示全部完成或空。 */
    public int currentStepIndex() {
        for (PlanStep step : steps) {
            if (step.status() == PlanStep.StepStatus.PENDING) {
                return step.index();
            }
        }
        return -1;
    }

    /** 是否有未完成的步骤。 */
    public boolean hasPending() {
        return currentStepIndex() >= 0;
    }
}
