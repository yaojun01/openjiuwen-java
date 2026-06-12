package com.openjiuwen.runtime.spring;

import com.openjiuwen.core.alpha.model.ExecutionPolicy;
import com.openjiuwen.runtime.core.dispatch.AdaptiveStrategy;
import com.openjiuwen.runtime.core.dispatch.AgentRegistry;
import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.*;
import com.openjiuwen.core.meta.AgentDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentClient——开发者直接注入使用的 SDK 门面。
 *
 * 这是开发者与 Runtime 交互的唯一入口。
 * 所有复杂度（策略选择、预算分配、安全检查）都被封装在内部。
 *
 * 用法：
 * <pre>
 * {@code @RestController}
 * public class OrderController {
 *     private final AgentClient agentClient; // 自动注入
 *
 *     {@code @PostMapping("/refund")}
 *     public String refund({@code @RequestBody} RefundRequest req) {
 *         // 一行调用，Runtime 自动处理策略路由、安全检查、预算控制
 *         return agentClient.invoke("order-refund", req.toJson()).block();
 *     }
 * }
 * </pre>
 *
 * 契约："像写 @Service 一样自然"
 */
public class AgentClient {

    private final AgentKernel kernel;
    private final AgentRegistry registry;
    private final AdaptiveStrategy adaptiveStrategy;
    private final OpenjiuwenProperties properties;

    /** 活跃任务追踪：taskId → 状态 */
    private final Map<TaskId, TaskStatus> activeTasks = new ConcurrentHashMap<>();

    public AgentClient(AgentKernel kernel,
                       AgentRegistry registry,
                       AdaptiveStrategy adaptiveStrategy,
                       OpenjiuwenProperties properties) {
        this.kernel = kernel;
        this.registry = registry;
        this.adaptiveStrategy = adaptiveStrategy;
        this.properties = properties;
    }

    // ==================== 核心 API ====================

    /**
     * 调用 Agent 执行任务——最常用的方法。
     *
     * @param agentName Agent 名称（对应 @Agent 注解的 name）
     * @param input     用户输入文本
     * @return Mono<String> Agent 的最终输出
     */
    public Mono<String> invoke(String agentName, String input) {
        return invoke(agentName, TaskInput.of(input));
    }

    /**
     * 调用 Agent 执行任务——带参数版本。
     *
     * @param agentName  Agent 名称
     * @param parameters 结构化参数
     * @return Mono<String> Agent 的最终输出
     */
    public Mono<String> invoke(String agentName, Map<String, Object> parameters) {
        String userInput = (String) parameters.getOrDefault("userInput", "");
        return invoke(agentName, TaskInput.of(userInput, parameters));
    }

    /**
     * 调用 Agent 执行任务——完整 TaskInput 版本。
     *
     * @param agentName Agent 名称
     * @param input     任务输入
     * @return Mono<String> Agent 的最终输出
     */
    public Mono<String> invoke(String agentName, TaskInput input) {
        TaskId taskId = new TaskId(UUID.randomUUID().toString());
        TaskContext context = buildTaskContext(taskId, agentName, input);

        activeTasks.put(taskId, new TaskStatus.Executing());

        return adaptiveStrategy.execute(context)
            .filter(event -> event.type() == EventType.TASK_COMPLETED
                         || event.type() == EventType.TASK_FAILED)
            .map(AgentEvent::data)
            .doFinally(signal -> activeTasks.remove(taskId))
            .next();
    }

    /**
     * 调用 Agent 并获取完整事件流（用于 SSE 推送到前端）。
     *
     * @param agentName Agent 名称
     * @param input     用户输入
     * @return Flux<AgentEvent> 完整事件流
     */
    public Flux<AgentEvent> invokeStream(String agentName, String input) {
        TaskId taskId = new TaskId(UUID.randomUUID().toString());
        TaskContext context = buildTaskContext(taskId, agentName, TaskInput.of(input));

        activeTasks.put(taskId, new TaskStatus.Executing());

        // 合流 strategy 事件流与 kernel 事件流（think 三段式 chunk + NodeCompleted 等经 kernel.emit 走 eventSinks）。
        // observeEvents 是热源 multicast sink，永不自动 complete，故用 takeUntil 在终止事件处截断，
        // 否则 merge 永不结束、SSE 挂死。终止事件后 doFinally 仍清理 activeTasks。
        return Flux.merge(adaptiveStrategy.execute(context), kernel.observeEvents(taskId))
            .takeUntil(e -> e.type() == EventType.TASK_COMPLETED
                         || e.type() == EventType.TASK_FAILED)
            .doFinally(signal -> activeTasks.remove(taskId));
    }

    /**
     * 调用 Agent 并获取完整事件流（带参数版本）。
     *
     * 与 {@link #invoke(String, Map)} 对称：允许通过 parameters 传入结构化上下文，
     * 例如 Alpha 规划所需的 {@code availableTools}（可用工具名列表）和 {@code successCriteria}。
     *
     * @param agentName  Agent 名称
     * @param parameters 结构化参数；其中的 "userInput" 作为用户输入文本，其余进入 TaskInput.parameters()
     * @return Flux&lt;AgentEvent&gt; 完整事件流
     */
    public Flux<AgentEvent> invokeStream(String agentName, Map<String, Object> parameters) {
        String userInput = (String) parameters.getOrDefault("userInput", "");
        TaskInput input = TaskInput.of(userInput, parameters);

        TaskId taskId = new TaskId(UUID.randomUUID().toString());
        TaskContext context = buildTaskContext(taskId, agentName, input);

        activeTasks.put(taskId, new TaskStatus.Executing());

        // 合流 strategy 事件流与 kernel 事件流（think 三段式 chunk + NodeCompleted 等经 kernel.emit 走 eventSinks）。
        // observeEvents 是热源 multicast sink，永不自动 complete，故用 takeUntil 在终止事件处截断，
        // 否则 merge 永不结束、SSE 挂死。终止事件后 doFinally 仍清理 activeTasks。
        return Flux.merge(adaptiveStrategy.execute(context), kernel.observeEvents(taskId))
            .takeUntil(e -> e.type() == EventType.TASK_COMPLETED
                         || e.type() == EventType.TASK_FAILED)
            .doFinally(signal -> activeTasks.remove(taskId));
    }

    /**
     * 获取活跃任务数量。
     */
    public int activeTaskCount() {
        return activeTasks.size();
    }

    /**
     * 检查指定 Agent 是否已注册。
     */
    public boolean hasAgent(String agentName) {
        return registry.contains(new AgentName(agentName));
    }

    // ==================== 内部方法 ====================

    /**
     * 构建 TaskContext。
     * 合并全局配置和 Agent 级别配置覆盖。
     */
    private TaskContext buildTaskContext(TaskId taskId, String agentName, TaskInput input) {
        AgentName name = new AgentName(agentName);

        // 从配置覆盖获取 Agent 级别参数，回退到全局默认
        OpenjiuwenProperties.AgentConfig agentConfig =
            properties.getAgents().get(agentName);

        AutonomyLevel autonomyLevel = resolveAutonomyLevel(agentConfig);
        Budget budget = resolveBudget(agentConfig);
        AgentDefinition agentDef = resolveAgentDefinition(name, agentConfig);

        Map<String, Object> extraContext = new HashMap<>();
        extraContext.put("executionPolicy", properties.getAlpha().toExecutionPolicy());
        if (agentConfig != null && agentConfig.getModel() != null) {
            extraContext.put("model", agentConfig.getModel());
        }

        return new TaskContext(
            taskId, name, input, agentDef,
            kernel, budget, autonomyLevel, extraContext
        );
    }

    private AutonomyLevel resolveAutonomyLevel(OpenjiuwenProperties.AgentConfig agentConfig) {
        if (agentConfig != null && agentConfig.getAutonomyLevel() != null) {
            return agentConfig.getAutonomyLevel();
        }
        return properties.getKernel().getDefaultAutonomyLevel();
    }

    private Budget resolveBudget(OpenjiuwenProperties.AgentConfig agentConfig) {
        if (agentConfig != null && agentConfig.getBudget() != null) {
            return agentConfig.getBudget().toBudget();
        }
        return properties.getKernel().getDefaultBudget().toBudget();
    }

    private AgentDefinition resolveAgentDefinition(AgentName name,
                                                    OpenjiuwenProperties.AgentConfig agentConfig) {
        // 优先从 AgentRegistry 获取（@Agent 扫描注册的）
        if (registry.contains(name)) {
            return registry.get(name);
        }

        // 否则从配置构建 AgentDefinition
        String description = agentConfig != null ? agentConfig.getDescription() : null;
        String systemPrompt = agentConfig != null ? agentConfig.getSystemPrompt() : "你是一个 AI 助手。";
        String model = agentConfig != null ? agentConfig.getModel() : null;

        return new AgentDefinition(
            name, description, systemPrompt,
            List.of(), resolveAutonomyLevel(agentConfig),
            resolveBudget(agentConfig),
            properties.getAlpha().toExecutionPolicy(),
            model, null, Map.of()
        );
    }
}
