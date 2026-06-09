package com.openjiuwen.core.alpha.event;

import com.openjiuwen.core.alpha.graph.TaskGraph;
import com.openjiuwen.core.alpha.graph.TaskNode;
import com.openjiuwen.core.alpha.model.ApprovalGate;
import com.openjiuwen.core.kernel.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Alpha 策略事件——PEV 模型的执行过程事件。
 *
 * sealed interface 覆盖 Plan-Execute-Verify 的所有关键节点：
 * - 规划阶段：PlanGenerated, PlanRevised
 * - 执行阶段：NodeStarted, NodeCompleted, NodeFailed, LayerCompleted
 * - 验证阶段：VerifyPassed, VerifyFailed
 * - 审批阶段：ApprovalRequired, ApprovalGranted, ApprovalDenied
 * - 约束阶段：ConstraintViolated
 *
 * 所有事件共享 taskId 和 timestamp，便于事件溯源和排序。
 */
public sealed interface AlphaEvent
    permits AlphaEvent.PlanGenerated,
            AlphaEvent.PlanRevised,
            AlphaEvent.NodeStarted,
            AlphaEvent.NodeCompleted,
            AlphaEvent.NodeFailed,
            AlphaEvent.LayerCompleted,
            AlphaEvent.VerifyPassed,
            AlphaEvent.VerifyFailed,
            AlphaEvent.ApprovalRequired,
            AlphaEvent.ApprovalGranted,
            AlphaEvent.ApprovalDenied,
            AlphaEvent.ConstraintViolated,
            AlphaEvent.AlphaCompleted {

    TaskId taskId();
    Instant timestamp();

    /**
     * 规划完成：TaskGraph 已生成。
     */
    record PlanGenerated(TaskId taskId, Instant timestamp, TaskGraph plan) implements AlphaEvent {}

    /**
     * 规划修订：验证失败后局部重新规划。
     */
    record PlanRevised(TaskId taskId, Instant timestamp, TaskGraph revisedPlan,
                       Set<String> failedNodes, String feedback) implements AlphaEvent {}

    /**
     * 节点开始执行。
     */
    record NodeStarted(TaskId taskId, Instant timestamp, NodeId nodeId, String description) implements AlphaEvent {}

    /**
     * 节点执行完成。
     */
    record NodeCompleted(TaskId taskId, Instant timestamp, NodeId nodeId, Object result) implements AlphaEvent {}

    /**
     * 节点执行失败。
     */
    record NodeFailed(TaskId taskId, Instant timestamp, NodeId nodeId, String error) implements AlphaEvent {}

    /**
     * 一层节点全部执行完成。
     */
    record LayerCompleted(TaskId taskId, Instant timestamp, int layerIndex,
                          java.util.List<NodeId> completedNodes) implements AlphaEvent {}

    /**
     * 验证通过。
     */
    record VerifyPassed(TaskId taskId, Instant timestamp, String feedback) implements AlphaEvent {}

    /**
     * 验证失败。
     */
    record VerifyFailed(TaskId taskId, Instant timestamp, String feedback,
                        Set<String> failedNodes) implements AlphaEvent {}

    /**
     * 需要人工审批。
     */
    record ApprovalRequired(TaskId taskId, Instant timestamp, ApprovalGate gate) implements AlphaEvent {}

    /**
     * 审批通过。
     */
    record ApprovalGranted(TaskId taskId, Instant timestamp, String gateId) implements AlphaEvent {}

    /**
     * 审批拒绝。
     */
    record ApprovalDenied(TaskId taskId, Instant timestamp, String gateId, String reason) implements AlphaEvent {}

    /**
     * 约束被违反。
     */
    record ConstraintViolated(TaskId taskId, Instant timestamp,
                              com.openjiuwen.core.alpha.model.Constraint constraint) implements AlphaEvent {}

    /**
     * Alpha 策略执行完成。
     */
    record AlphaCompleted(TaskId taskId, Instant timestamp, String output,
                          TaskGraph plan, boolean verified) implements AlphaEvent {}
}
