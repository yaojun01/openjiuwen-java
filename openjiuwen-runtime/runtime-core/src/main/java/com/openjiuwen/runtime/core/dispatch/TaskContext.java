package com.openjiuwen.runtime.core.dispatch;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.*;
import com.openjiuwen.core.meta.AgentDefinition;

import java.util.Map;

/**
 * 任务上下文——每次执行创建一个新的，不共享。
 *
 * Agent 无状态的设计保证：Agent 本身不持有任何执行状态，
 * 所有状态都在 TaskContext 中。这意味着同一个 Agent 可以
 * 并发处理 10000 个 Task，每个 Task 有独立的 TaskContext。
 *
 * TaskContext 持有：
 * - 任务元信息（taskId, agentName, input）
 * - Agent 定义（系统提示、工具列表）
 * - 执行资源（AgentKernel、Budget、SafetyBoundary）
 * - 自主度（决定策略行为）
 */
public record TaskContext(
    TaskId taskId,
    AgentName agentName,
    TaskInput input,
    AgentDefinition agentDefinition,
    AgentKernel kernel,
    Budget budget,
    AutonomyLevel autonomyLevel,
    Map<String, Object> extraContext
) {

    /**
     * 创建子任务上下文（用于递归执行或子 Agent）。
     * 继承父任务的 AgentKernel 和 Budget，但使用新的 taskId。
     */
    public TaskContext forSubTask(TaskId subTaskId, TaskInput subInput) {
        return new TaskContext(
            subTaskId,
            agentName,
            subInput,
            agentDefinition,
            kernel,
            budget,
            autonomyLevel,
            extraContext
        );
    }

    /** 获取当前预算追踪（从 kernel 中查询） */
    public BudgetLimits currentBudgetLimits() {
        return BudgetLimits.start(budget);
    }
}
