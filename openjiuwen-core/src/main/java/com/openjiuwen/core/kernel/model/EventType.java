package com.openjiuwen.core.kernel.model;

/**
 * 事件类型枚举。
 *
 * P1 范围：通用事件 + Alpha（PEV）策略事件。
 * P2 Beta 策略的事件类型不在此处定义——P2 接手时可在此恢复或通过 AgentEvent.metadata
 * 传递策略专属信息。
 */
public enum EventType {

    // --- 通用事件 ---
    TASK_CREATED,       // 任务已创建
    TASK_STARTED,       // 任务开始执行
    THINKING,           // Agent 思考过程
    TOOL_CALL,          // 工具调用开始
    TOOL_RESULT,        // 工具调用结果
    CHECKPOINT_SAVED,   // 检查点已保存
    TASK_COMPLETED,     // 任务完成
    TASK_FAILED,        // 任务失败
    TASK_PAUSED,        // 任务暂停
    TASK_CANCELLED,     // 任务取消

    // --- Alpha 策略专属 ---
    PLAN_GENERATED,     // TaskGraph 规划完成
    PLAN_REVISED,       // TaskGraph 局部修订
    NODE_STARTED,       // 任务图节点开始执行
    NODE_COMPLETED,     // 任务图节点执行完成
    NODE_FAILED,        // 任务图节点执行失败
    LAYER_COMPLETED,    // 一层节点全部执行完成
    VERIFY_PASSED,      // 验证通过
    VERIFY_FAILED,      // 验证失败
    APPROVAL_REQUIRED,  // 需要人工审批
    APPROVAL_GRANTED,   // 审批通过
    APPROVAL_DENIED,    // 审批拒绝
    CONSTRAINT_VIOLATED // 约束被违反
}
