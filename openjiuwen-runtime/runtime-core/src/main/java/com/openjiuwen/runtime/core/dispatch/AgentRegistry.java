package com.openjiuwen.runtime.core.dispatch;

import com.openjiuwen.core.kernel.model.AgentName;
import com.openjiuwen.core.meta.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册中心。
 *
 * 扫描所有 AgentDefinition 并注册，提供按名称查找的能力。
 * 运行时支持动态注册（用于子 Agent、元Agent 生成）。
 *
 * 线程安全：ConcurrentHashMap 支持并发读写。
 */
@Component
public class AgentRegistry {

    private final Map<AgentName, AgentDefinition> agents = new ConcurrentHashMap<>();

    public AgentRegistry() {}

    /** 用初始 Agent 集合构造（Spring AutoConfiguration 注入） */
    public AgentRegistry(Collection<AgentDefinition> definitions) {
        for (AgentDefinition def : definitions) {
            register(def);
        }
    }

    /**
     * 注册一个 Agent 定义。
     * 如果同名 Agent 已存在，抛出异常（不允许覆盖）。
     */
    public void register(AgentDefinition definition) {
        Objects.requireNonNull(definition, "AgentDefinition 不能为 null");
        AgentName name = definition.name();
        AgentDefinition existing = agents.putIfAbsent(name, definition);
        if (existing != null) {
            throw new IllegalStateException(
                "Agent 已注册: " + name + "。不允许重复注册同名 Agent。");
        }
    }

    /**
     * 按名称查找 Agent 定义。
     *
     * @throws NoSuchElementException 如果 Agent 不存在
     */
    public AgentDefinition get(AgentName name) {
        AgentDefinition def = agents.get(name);
        if (def == null) {
            throw new NoSuchElementException("Agent 未注册: " + name);
        }
        return def;
    }

    /** 按名称字符串查找（便捷方法） */
    public AgentDefinition get(String name) {
        return get(new AgentName(name));
    }

    /** 检查 Agent 是否已注册 */
    public boolean contains(AgentName name) {
        return agents.containsKey(name);
    }

    /** 获取所有已注册的 Agent 名称 */
    public Set<AgentName> agentNames() {
        return Collections.unmodifiableSet(agents.keySet());
    }

    /** 获取所有已注册的 Agent 定义 */
    public Collection<AgentDefinition> allAgents() {
        return Collections.unmodifiableCollection(agents.values());
    }
}
