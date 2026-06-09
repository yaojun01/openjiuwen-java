package com.openjiuwen.runtime.core.meta;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.core.kernel.model.AgentName;
import com.openjiuwen.core.kernel.model.Budget;
import com.openjiuwen.core.meta.AgentDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 元Agent 配方——动态生成 Agent 的模板。
 *
 * 元Agent 是 META/AUTONOMOUS 级别 Agent 的核心能力：
 * 它可以根据当前任务的需要，动态生成一个专门的子 Agent。
 *
 * MetaAgentManifest 描述了这个"配方"：
 * - 基于哪个 Agent（继承系统提示和工具集）
 * - 专注的目标
 * - 可用的工具子集
 * - 预算限制
 * - 生命周期（用完即销毁 vs 持久化）
 */
public record MetaAgentManifest(
    String manifestId,
    String name,
    String description,
    String basedOn,
    String specializedGoal,
    List<String> allowedTools,
    Budget budget,
    AutonomyLevel autonomyLevel,
    Lifecycle lifecycle,
    Map<String, Object> configuration,
    Instant createdAt
) {

    public MetaAgentManifest {
        if (manifestId == null || manifestId.isBlank()) {
            manifestId = java.util.UUID.randomUUID().toString();
        }
        if (autonomyLevel == null) autonomyLevel = AutonomyLevel.GUIDED;
        if (lifecycle == null) lifecycle = Lifecycle.EPHEMERAL;
        if (createdAt == null) createdAt = Instant.now();
    }

    /**
     * 子 Agent 生命周期。
     */
    public enum Lifecycle {
        /** 临时：完成任务后销毁 */
        EPHEMERAL,
        /** 持久：注册到 AgentRegistry，可复用 */
        PERSISTENT
    }

    /**
     * 从 Manifest 创建 AgentDefinition。
     * 用于注册到 AgentRegistry。
     */
    public AgentDefinition toAgentDefinition() {
        return new AgentDefinition(
            new com.openjiuwen.core.kernel.model.AgentName(name),
            description,
            "你是专门负责以下任务的 Agent: " + specializedGoal,
            allowedTools.stream()
                .map(tool -> new AgentDefinition.ToolDefinition(tool, "", List.of()))
                .toList(),
            autonomyLevel,
            budget,
            null,
            null,
            basedOn,
            configuration
        );
    }
}
