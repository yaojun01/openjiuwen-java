package com.openjiuwen.core.alpha.model;

import com.openjiuwen.core.kernel.model.ToolName;

/**
 * 审批门——执行过程中的人工审批节点。
 *
 * 当 ExecutionPolicy 中配置了审批约束时，Alpha 策略在执行到
 * 指定工具调用前会暂停，生成 ApprovalGate，等待人工确认。
 *
 * 审批结果：
 * - APPROVED: 继续执行
 * - DENIED:   中止当前节点，标记为 FAILED
 * - MODIFIED: 修改参数后继续执行
 */
public record ApprovalGate(
    String gateId,
    ToolName toolName,
    String description,
    java.util.Map<String, Object> proposedArguments,
    ApprovalStatus status,
    String reviewerComment
) {

    /** 审批状态 */
    public enum ApprovalStatus {
        PENDING,    // 等待审批
        APPROVED,   // 已批准
        DENIED,     // 已拒绝
        MODIFIED    // 修改参数后批准
    }

    /** 创建待审批的 ApprovalGate */
    public static ApprovalGate pending(String gateId, ToolName toolName,
                                        String description,
                                        java.util.Map<String, Object> proposedArguments) {
        return new ApprovalGate(gateId, toolName, description, proposedArguments,
            ApprovalStatus.PENDING, null);
    }

    /** 批准 */
    public ApprovalGate approve(String comment) {
        return new ApprovalGate(gateId, toolName, description, proposedArguments,
            ApprovalStatus.APPROVED, comment);
    }

    /** 拒绝 */
    public ApprovalGate deny(String reason) {
        return new ApprovalGate(gateId, toolName, description, proposedArguments,
            ApprovalStatus.DENIED, reason);
    }
}
