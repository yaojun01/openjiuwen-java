package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.orchestrator.AutonomousOrchestrator;
import com.openjiuwen.runtime.beta.orchestrator.JsonDecisionParser;
import com.openjiuwen.runtime.beta.orchestrator.DefaultDecisionPromptBuilder;
import com.openjiuwen.runtime.beta.verification.DecisionHistoryCriteriaVerifier;
import com.openjiuwen.runtime.criteria.CriteriaOrchestrator;
import com.openjiuwen.runtime.core.dispatch.ExecutionStrategy;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.core.kernel.model.AgentEvent;
import com.openjiuwen.core.kernel.model.Checkpoint;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
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
public class BetaStrategy implements ExecutionStrategy, DisposableBean {

    private final AutonomousOrchestrator orchestrator;

    /**
     * 单构造函数，用 ObjectProvider 可选注入 CriteriaOrchestrator。
     * INT-001: 消除双构造函数歧义——Spring 无需选择构造函数。
     *
     * @param guardrailEngine 必需，Spring 注入的 GuardrailEngine
     * @param criteriaProvider 可选，Spring 容器中有 CriteriaOrchestrator bean 时自动注入
     */
    public BetaStrategy(GuardrailEngine guardrailEngine,
                        ObjectProvider<CriteriaOrchestrator> criteriaProvider) {
        CriteriaOrchestrator co = criteriaProvider.getIfAvailable();
        if (co != null) {
            this.orchestrator = new AutonomousOrchestrator(
                guardrailEngine,
                new JsonDecisionParser(),
                new DefaultDecisionPromptBuilder(),
                new DecisionHistoryCriteriaVerifier(),
                co);
        } else {
            this.orchestrator = new AutonomousOrchestrator(guardrailEngine);
        }
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

    /** REACT-004: 应用关闭时释放决策循环线程池 */
    @Override
    public void destroy() {
        AutonomousOrchestrator.disposeScheduler();
    }
}
