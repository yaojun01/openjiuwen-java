package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.orchestrator.AutonomousOrchestrator;
import com.openjiuwen.runtime.core.dispatch.ExecutionStrategy;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.core.kernel.model.AgentEvent;
import com.openjiuwen.core.kernel.model.Checkpoint;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Beta 策略——LLM 自主编排。
 *
 * 薄门面：委托给 {@link AutonomousOrchestrator} 执行实际的决策循环。
 * 本类只负责 Spring 组件注册（@Component("beta")）和 Orchestrator 构建。
 *
 * 安全网由 Orchestrator 内部通过 GuardrailEngine、SafetyBoundary、Budget 控制。
 */
@Component("beta")
public class BetaStrategy implements ExecutionStrategy {

    private final AutonomousOrchestrator orchestrator;

    public BetaStrategy(GuardrailEngine guardrailEngine) {
        this.orchestrator = new AutonomousOrchestrator(guardrailEngine);
    }

    @Override
    public String name() {
        return "beta";
    }

    @Override
    public Flux<AgentEvent> execute(TaskContext context) {
        return orchestrator.execute(context);
    }

    @Override
    public Flux<AgentEvent> resume(TaskContext context, Checkpoint checkpoint) {
        return orchestrator.resume(context, checkpoint);
    }
}
