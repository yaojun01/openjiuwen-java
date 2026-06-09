package com.openjiuwen.core.alpha.model;

import com.openjiuwen.core.alpha.graph.TaskGraph;

import java.util.Map;

/**
 * Plan 审批决策——人类对 TaskGraph 的审批结果。
 *
 * 审批流：
 * - MANUAL 模式：整个 TaskGraph 必须审批
 * - SEMI_AUTO 模式：只有标记了 approvalRequired=true 的节点需要审批
 * - AUTO 模式：不触发审批
 *
 * 审批者可以：
 * - APPROVED：原样通过
 * - MODIFIED：修改 TaskGraph 后通过（如删除节点、修改描述）
 * - DENIED：拒绝执行
 */
public record ApprovalDecision(
    ApprovalStatus status,
    TaskGraph modifiedGraph,   // MODIFIED 时携带修改后的图，否则 null
    String reviewerComment,
    Map<String, String> nodeComments  // 对特定节点的注释
) {

    public enum ApprovalStatus {
        APPROVED,
        MODIFIED,
        DENIED
    }

    /** 原样通过 */
    public static ApprovalDecision approved() {
        return new ApprovalDecision(ApprovalStatus.APPROVED, null, null, Map.of());
    }

    /** 通过并附带注释 */
    public static ApprovalDecision approved(String comment) {
        return new ApprovalDecision(ApprovalStatus.APPROVED, null, comment, Map.of());
    }

    /** 修改后通过 */
    public static ApprovalDecision modified(TaskGraph modifiedGraph, String comment) {
        return new ApprovalDecision(ApprovalStatus.MODIFIED, modifiedGraph, comment, Map.of());
    }

    /** 拒绝 */
    public static ApprovalDecision denied(String reason) {
        return new ApprovalDecision(ApprovalStatus.DENIED, null, reason, Map.of());
    }
}
