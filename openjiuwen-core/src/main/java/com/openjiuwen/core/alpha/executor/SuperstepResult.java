package com.openjiuwen.core.alpha.executor;

import com.openjiuwen.core.alpha.graph.TaskNode;
import com.openjiuwen.core.kernel.model.NodeId;

import java.util.Map;
import java.util.Set;

/**
 * 超步结果——Pregel BSP 模型中一个超步的执行结果。
 *
 * Pregel BSP 模型：
 * - 一个超步 = 同一层所有节点的并行执行
 * - 超步之间有同步屏障（barrier）
 * - 节点间通过消息传递数据
 * - 每个超步结束后才能看到本层所有节点的输出
 *
 * @param superstepIndex 超步序号（0-based）
 * @param nodeResults    节点 ID → 执行结果
 * @param failedNodes    失败的节点 ID 集合
 * @param skippedNodes   被跳过的节点 ID 集合
 */
public record SuperstepResult(
    int superstepIndex,
    Map<NodeId, Object> nodeResults,
    Set<NodeId> failedNodes,
    Set<NodeId> skippedNodes
) {

    public boolean hasFailures() {
        return failedNodes != null && !failedNodes.isEmpty();
    }

    public boolean allSucceeded() {
        return !hasFailures() && (skippedNodes == null || skippedNodes.isEmpty());
    }
}
