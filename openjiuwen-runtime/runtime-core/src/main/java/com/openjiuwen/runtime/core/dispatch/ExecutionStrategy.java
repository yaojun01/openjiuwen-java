package com.openjiuwen.runtime.core.dispatch;

import com.openjiuwen.core.kernel.model.AgentEvent;
import com.openjiuwen.core.kernel.model.Checkpoint;
import com.openjiuwen.core.kernel.model.TaskId;
import reactor.core.publisher.Flux;

/**
 * 执行策略接口。
 *
 * 调度层的核心抽象。不同的 AutonomyLevel 路由到不同的策略实现：
 * - GUIDED/ASSISTED → AlphaStrategy（PEV 显式控制）
 * - META/AUTONOMOUS  → BetaStrategy（LLM 自主编排）
 *
 * 契约：
 * - execute() 返回的事件流必须以 TASK_COMPLETED 或 TASK_FAILED 终结
 * - resume() 从检查点恢复，返回的事件流与 execute() 格式一致
 * - 策略实现无状态，所有上下文通过 TaskContext 传入
 */
public interface ExecutionStrategy {

    /**
     * 策略名称。
     * 用于路由和日志。如 "alpha", "beta"。
     */
    String name();

    /**
     * 执行任务。
     *
     * 语义：从零开始执行一个完整的任务，返回事件流。
     * 事件流是有序的：TASK_CREATED → ... → TASK_COMPLETED/TASK_FAILED
     *
     * @param context 任务上下文（输入、预算、工具列表等）
     * @return 事件流
     */
    Flux<AgentEvent> execute(TaskContext context);

    /**
     * 从检查点恢复执行。
     *
     * 语义：加载检查点中保存的状态，从断点继续执行。
     * 检查点中包含了已完成的步骤和中间结果。
     *
     * @param context    任务上下文
     * @param checkpoint 恢复点
     * @return 事件流（从恢复点开始的事件）
     */
    Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint);
}
