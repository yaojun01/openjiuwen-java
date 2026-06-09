package com.openjiuwen.core.alpha.graph;

import com.openjiuwen.core.kernel.model.NodeId;

import java.util.Map;

/**
 * 任务图节点——一个可执行的子任务。
 *
 * 节点类型：
 * - TOOL_CALL:  调用一个已注册的工具（确定性执行）
 * - LLM_CALL:   调用 LLM 进行推理（需要思考）
 * - SUB_AGENT:  递归执行一个新的 Plan-Execute-Verify（复杂子任务）
 *
 * inputs 中可以引用上游节点的输出，格式：${nodeId.output}
 * 执行时由 AlphaStrategy 解析替换。
 */
public record TaskNode(
    NodeId id,
    String description,
    TaskNodeType type,
    Map<String, String> inputs,
    String expectedOutput,
    TaskNodeStatus status
) {

    public TaskNode {
        if (id == null) throw new IllegalArgumentException("节点 ID 不能为 null");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("节点描述不能为空");
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    /** 便捷构造：默认 PENDING 状态 */
    public static TaskNode of(String id, String description, TaskNodeType type) {
        return new TaskNode(new NodeId(id), description, type, Map.of(), null, TaskNodeStatus.PENDING);
    }

    /** 便捷构造：带输入引用 */
    public static TaskNode of(String id, String description, TaskNodeType type, Map<String, String> inputs) {
        return new TaskNode(new NodeId(id), description, type, inputs, null, TaskNodeStatus.PENDING);
    }

    /** 更新节点状态 */
    public TaskNode withStatus(TaskNodeStatus newStatus) {
        return new TaskNode(id, description, type, inputs, expectedOutput, newStatus);
    }
}
