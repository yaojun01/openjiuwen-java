package com.openjiuwen.runtime.core.dispatch;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.core.kernel.model.AgentEvent;
import com.openjiuwen.core.kernel.model.Checkpoint;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;

/**
 * 自适应策略路由——根据 AutonomyLevel 将任务分发到对应的执行策略。
 *
 * 路由规则：
 * - GUIDED / ASSISTED → AlphaStrategy（PEV 显式控制）
 * - META / AUTONOMOUS  → BetaStrategy（LLM 自主编排）
 *
 * 这是调度层唯一的 Spring @Component，是策略选择的唯一入口。
 * 不包含任何业务逻辑——只做路由。
 */
@Component
public class AdaptiveStrategy implements ExecutionStrategy {

    private final Map<String, ExecutionStrategy> strategyMap;

    /**
     * 构造时注入所有 ExecutionStrategy 实现。
     * Spring 自动收集所有实现了 ExecutionStrategy 的 @Component。
     * 过滤掉自身（adaptive），只保留实际策略（alpha、beta等）。
     *
     * @param strategies 所有已注册的策略实现
     */
    public AdaptiveStrategy(Map<String, ExecutionStrategy> strategies) {
        // 过滤掉自身，只保留 alpha/beta 等实际策略
        Map<String, ExecutionStrategy> filtered = new java.util.LinkedHashMap<>();
        for (var entry : strategies.entrySet()) {
            if (!entry.getValue().name().equals("adaptive")) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        this.strategyMap = filtered;
    }

    @Override
    public String name() {
        return "adaptive";
    }

    @Override
    public Flux<AgentEvent> execute(TaskContext context) {
        ExecutionStrategy delegate = selectStrategy(context);
        return delegate.execute(context);
    }

    @Override
    public Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint) {
        ExecutionStrategy delegate = selectStrategy(context);
        return delegate.resume(context, checkpoint);
    }

    /**
     * 根据 AutonomyLevel 选择策略。
     *
     * 路由逻辑：
     * - GUIDED / ASSISTED → alpha（确定性 PEV 控制）
     * - META / AUTONOMOUS  → beta（LLM 自主决策）
     *
     * @throws IllegalArgumentException 如果没有找到对应的策略实现
     */
    private ExecutionStrategy selectStrategy(TaskContext context) {
        AutonomyLevel level = context.autonomyLevel();
        String strategyName = switch (level) {
            case GUIDED, ASSISTED -> "alpha";
            case META, AUTONOMOUS -> "beta";
        };

        ExecutionStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            throw new IllegalStateException(
                "未找到策略实现: " + strategyName + "，请确保对应的 @Component 已注册");
        }
        return strategy;
    }
}
