package com.openjiuwen.core.kernel.model;

/**
 * 事件类型枚举。
 * 不管底层用 Alpha 策略还是 Beta 策略，事件类型统一。
 * 上层（SDK / 前端）只需要处理这些类型，不需要知道底层策略细节。
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
    CONSTRAINT_VIOLATED,// 约束被违反

    // --- Beta 策略专属 ---
    GOAL_ANALYZED,      // 目标分析完成
    DECISION_MADE,      // LLM 做出决策
    GUARDRAIL_TRIGGERED,// 护栏触发
    SELF_REFLECTION,    // 自我反思
    CONTEXT_COMPACTED,  // 上下文被压缩
    GOAL_REPRIORITIZED, // 目标被重新排序
    SPAWN_SUB_AGENT,    // 生成子 Agent
    SUB_AGENT_COMPLETED, // 子 Agent 完成
    REPLAN_REQUESTED,   // LLM 发起重规划
    REPLAN_ASSESSED,    // 重规划可行性评估完成
    GOAL_DRIFT_DETECTED, // 目标漂移检测
    CRITERIA_VERIFIED,  // 成功标准验证完成
    KNOWLEDGE_DEPOSITED // 知识沉淀完成
}
