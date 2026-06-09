package com.openjiuwen.runtime.alpha.executor;

import com.openjiuwen.core.alpha.graph.TaskGraph;
import com.openjiuwen.core.alpha.model.ExecutionPolicy;
import com.openjiuwen.core.alpha.executor.SuperstepResult;
import com.openjiuwen.core.kernel.model.BudgetLimits;
import com.openjiuwen.core.kernel.model.TaskId;
import reactor.core.publisher.Flux;

/**
 * Pregel BSP 执行器接口——PEV 第二阶段：执行 TaskGraph。
 *
 * 设计模型：Pregel BSP（Bulk Synchronous Parallel）
 * - 超步（superstep）：同一层节点的并行执行
 * - 同步屏障（barrier）：每层节点全部完成后才进入下一层
 * - 消息传递：节点间通过 ${nodeId.output} 引用传递数据
 *
 * 核心特征：
 * 1. 虚拟线程并行：同层节点使用 Java 21 虚拟线程并行执行
 * 2. 子 Agent 生成：SUB_AGENT 节点递归创建子 PEV 流程
 * 3. 错误处理策略：FailFast / Retry / Degrade / PartialReplan
 * 4. 超时和取消：每层节点有独立的超时限制
 * 5. 预算追踪：每个系统调用后更新 BudgetLimits
 *
 * 可替换性：开发者可以实现此接口替换执行器。
 * 例如：
 * - 单线程执行器：用于调试
 * - 分布式执行器：跨 JVM 执行 TaskGraph
 */
public interface PregelExecutor {

    /**
     * 执行 TaskGraph。
     *
     * @param taskId   任务 ID
     * @param graph    待执行的 TaskGraph
     * @param policy   执行策略
     * @param budget   预算追踪（可变，执行过程中更新）
     * @return 超步结果流（每个超步产生一个 SuperstepResult）
     */
    Flux<SuperstepResult> execute(TaskId taskId, TaskGraph graph,
                                   ExecutionPolicy policy, BudgetLimits budget);
}
