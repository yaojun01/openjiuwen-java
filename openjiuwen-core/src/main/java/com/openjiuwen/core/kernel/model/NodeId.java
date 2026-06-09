package com.openjiuwen.core.kernel.model;

/**
 * 任务图中的节点标识。
 * 在 TaskGraph 内唯一，用于描述 DAG 中节点间的依赖关系。
 *
 * @param value 节点 ID，如 "A", "B", "step-1"
 */
public record NodeId(String value) {

    public NodeId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("NodeId 不能为空");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
