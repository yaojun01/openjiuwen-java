package com.openjiuwen.runtime.alpha.planner;

import com.openjiuwen.core.alpha.model.*;
import com.openjiuwen.core.alpha.graph.TaskGraph;
import com.openjiuwen.core.kernel.model.TaskId;
import reactor.core.publisher.Mono;

/**
 * Planner 接口——PEV 第一阶段：把目标分解成 TaskGraph。
 *
 * 职责边界：
 * 1. 接收 PlanGoal，调用 LLM 生成 TaskGraph
 * 2. 校验 TaskGraph（环检测、依赖合理性、预算可行性）
 * 3. 校验失败时自纠错（最多 N 次，由 maxCorrectionRounds 控制）
 * 4. 根据 PlanningMode 触发审批流
 *
 * 不负责：
 * - 执行 TaskGraph（那是 Executor 的事）
 * - 验证执行结果（那是 Verifier 的事）
 *
 * 可替换性：开发者可以实现此接口替换默认的 LLM Planner。
 * 例如：
 * - 规则 Planner：不调 LLM，用规则引擎直接生成 TaskGraph
 * - 混合 Planner：简单任务用规则，复杂任务调 LLM
 */
public interface Planner {

    /**
     * 规划：把目标分解成 TaskGraph。
     *
     * 完整流程：
     * 1. 调用 LLM 生成 TaskGraph 的 JSON
     * 2. 解析 JSON → TaskGraph 对象
     * 3. 校验：环检测 + 依赖合理性 + 预算可行性
     * 4. 校验失败 → 将错误反馈给 LLM → 重新生成
     * 5. 校验通过 → 返回 PlanResult
     *
     * @param taskId 任务 ID（用于事件追踪）
     * @param goal   规划目标
     * @param policy 执行策略（决定是否需要审批等）
     * @return 规划结果
     */
    Mono<PlanResult> plan(TaskId taskId, PlanGoal goal, ExecutionPolicy policy);

    /**
     * 校验 TaskGraph 的合法性。
     *
     * 校验项：
     * 1. DAG 环检测（Tarjan 算法）
     * 2. 节点依赖合理性（前置工具是否已注册？）
     * 3. 预算可行性（预估 Token 消耗 vs 预算上限）
     * 4. 约束检查（MaxSteps / RequiredTool）
     *
     * 此方法独立暴露，方便开发者在自定义 Planner 中复用校验逻辑。
     *
     * @param graph      待校验的 TaskGraph
     * @param goal       原始目标（用于合理性检查）
     * @param constraints 约束列表
     * @return 校验结果
     */
    PlanResult validate(TaskGraph graph, PlanGoal goal, java.util.List<Constraint> constraints);
}
