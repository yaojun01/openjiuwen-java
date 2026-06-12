package com.openjiuwen.runtime.core.engine;

import com.openjiuwen.core.kernel.model.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentKernel 默认实现。
 *
 * 职责：
 * 1. 聚合 SafetyBoundary，在每个系统调用前后执行安全检查
 * 2. 管理 LLM 调用（通过 Spring AI ChatModel 注入）
 * 3. 管理工具调用（通过工具注册表注入）
 * 4. 管理检查点存储（内存 / Redis / JDBC）
 * 5. 管理事件流（通过 Reactor Sinks.Many 广播）
 *
 * 线程安全：所有状态都是 ConcurrentHashMap / CopyOnWriteArrayList，
 * 配合虚拟线程可安全支持万级并发。
 */
public class DefaultAgentKernel implements AgentKernel {

    /** LLM 调用函数。实际由 Spring AI ChatModel 实现。 */
    private final LLMProvider llmProvider;

    /** 工具注册表。toolName → 工具执行函数。 */
    private final Map<ToolName, ToolExecutor> toolExecutors;

    /** 检查点存储 */
    private final CheckpointStore checkpointStore;

    /** 安全边界 */
    private final SafetyBoundary safetyBoundary;

    /** 事件广播：每个 taskId 一个 Sink */
    private final Map<TaskId, Sinks.Many<AgentEvent>> eventSinks = new ConcurrentHashMap<>();

    /** 运行时结果存储：taskId → nodeId → result */
    private final Map<TaskId, Map<NodeId, Object>> taskResults = new ConcurrentHashMap<>();

    public DefaultAgentKernel(
        LLMProvider llmProvider,
        Map<ToolName, ToolExecutor> toolExecutors,
        CheckpointStore checkpointStore,
        SafetyBoundary safetyBoundary
    ) {
        this.llmProvider = Objects.requireNonNull(llmProvider);
        // 持有共享 Map 引用而非防御性拷贝：@Tool 由 AgentBeanPostProcessor 在 kernel 构造后
        // 动态注册到该 Spring 单例 Map（OpenjiuwenAutoConfiguration#toolExecutors +
        // AgentBeanPostProcessor#registerToolExecutor）。拷贝会切断关联，使运行期注册的工具
        // 对 kernel 不可见（invokeTool 在拷贝快照里查不到 → "工具未注册"），导致所有真实
        // @Tool 的 TOOL_CALL 执行失败。调用方应传入 ConcurrentHashMap 以支持并发注册与查找。
        this.toolExecutors = Objects.requireNonNull(toolExecutors);
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.safetyBoundary = Objects.requireNonNull(safetyBoundary);
    }

    /** think 的 idle-timeout：流式调用时若连续 IDLE_TIMEOUT_SECONDS 无 chunk 则判卡死，快速失败。
     *  健康慢（持续 decode）每来一个 chunk 重置计时，永不撞此阈值 —— 治本 LLM 慢响应适配。
     *  详见 memory [[hierarchy1-streaming-refactor-plan]]。 */
    private static final long IDLE_TIMEOUT_SECONDS = 30L;

    /** 并发生产者（并行节点虚拟线程）争用同一 sink 时的 emit 重试策略：忙等至多 1s，
     *  覆盖 FAIL_NON_SERIALIZED（并发争用）/FAIL_OVERFLOW（订阅者短暂滞后），超时才放弃（极端死锁兜底）。 */
    private static final Sinks.EmitFailureHandler EMIT_HANDLER =
        Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1));

    // ==================== 系统调用 1: think ====================

    @Override
    public Mono<String> think(String prompt, BudgetLimits budget) {
        // 2 参入口退化为不带身份的 4 参调用：taskId==null 时 4 参实现跳过流式事件发布，
        // 行为与历史 2 参实现完全一致（仅流式聚合 + idle-timeout + 安全检查）。
        return think(prompt, budget, null, null);
    }

    @Override
    public Mono<String> think(String prompt, BudgetLimits budget, TaskId taskId, NodeId nodeId) {
        return Mono.fromCallable(() -> {
                // 前置检查：预算
                List<Violation> budgetViolations = safetyBoundary.checkBudget(budget);
                if (!budgetViolations.isEmpty()) {
                    throw new SafetyViolationException("预算不足", budgetViolations);
                }
                return true; // 仅触发检查，返回值驱动后续 flatMap
            })
            .flatMap(ignored -> {
                // 流式三段式（对齐 AgentScope v2 *_BLOCK_START/_DELTA/_END）：
                // 块头带 nodeId，前端按块分桶，扇出并行节点的 chunk 不串。
                // taskId==null（2 参入口/测试）时跳过事件发布，零行为变化。
                boolean emit = taskId != null;
                if (emit) emitChunk(taskId, EventType.THINKING_BLOCK_START, "", nodeId);
                return llmProvider.stream(prompt)
                    .timeout(Duration.ofSeconds(IDLE_TIMEOUT_SECONDS))   // idle-timeout 治本
                    .doOnNext(chunk -> { if (emit) emitChunk(taskId, EventType.THINKING_DELTA, chunk, null); })
                    .reduce(new StringBuilder(), StringBuilder::append)
                    .map(StringBuilder::toString)
                    .doOnSuccess(v -> { if (emit) emitChunk(taskId, EventType.THINKING_BLOCK_END, "", nodeId); })
                    .doOnError(e -> { if (emit) emitChunk(taskId, EventType.THINKING_BLOCK_END,
                        "ERROR: " + e.getMessage(), nodeId); });
            })
            .flatMap(output -> {
                // 后置检查：输出安全性
                List<Violation> outputViolations = safetyBoundary.checkLLMOutput(output);
                boolean hasCritical = outputViolations.stream()
                    .anyMatch(v -> v.severity() == Violation.Severity.CRITICAL);
                if (hasCritical) {
                    return Mono.error(new SafetyViolationException("LLM 输出违规", outputViolations));
                }
                // 非 CRITICAL 放行（同原行为）
                return Mono.just(output);
            });
    }

    // ==================== 系统调用 2: invokeTool ====================

    @Override
    public Mono<ToolResult> invokeTool(ToolName toolName, Map<String, Object> arguments, BudgetLimits budget) {
        return Mono.fromCallable(() -> {
            // 前置检查：预算 + 工具权限
            List<Violation> violations = safetyBoundary.checkToolCall(toolName, arguments, budget);
            if (!violations.isEmpty()) {
                boolean hasCritical = violations.stream()
                    .anyMatch(v -> v.severity() == Violation.Severity.CRITICAL);
                if (hasCritical) {
                    throw new SafetyViolationException("工具调用被拒绝", violations);
                }
            }

            // 查找工具
            ToolExecutor executor = toolExecutors.get(toolName);
            if (executor == null) {
                return ToolResult.fail(toolName, "工具未注册: " + toolName);
            }

            // 执行工具
            try {
                Object result = executor.execute(arguments);
                return ToolResult.ok(toolName, result);
            } catch (Exception e) {
                return ToolResult.fail(toolName, "工具执行失败: " + e.getMessage());
            }
        });
    }

    // ==================== 系统调用 3: observe ====================

    @Override
    public Mono<Map<NodeId, Object>> observe(TaskId taskId, Set<NodeId> nodeIds) {
        return Mono.fromCallable(() -> {
            Map<NodeId, Object> results = taskResults.getOrDefault(taskId, Map.of());
            if (nodeIds == null || nodeIds.isEmpty()) {
                return Collections.unmodifiableMap(results);
            }
            Map<NodeId, Object> filtered = new LinkedHashMap<>();
            for (NodeId nid : nodeIds) {
                if (results.containsKey(nid)) {
                    filtered.put(nid, results.get(nid));
                }
            }
            return Collections.unmodifiableMap(filtered);
        });
    }

    // ==================== 系统调用 4: saveCheckpoint ====================

    @Override
    public Mono<CheckpointId> saveCheckpoint(Checkpoint checkpoint) {
        return checkpointStore.save(checkpoint).thenReturn(checkpoint.checkpointId());
    }

    // ==================== 系统调用 5: restoreCheckpoint ====================

    @Override
    public Mono<Checkpoint> restoreCheckpoint(TaskId taskId) {
        return checkpointStore.loadLatest(taskId)
            .doOnNext(cp -> {
                // 恢复结果存储
                // 实际的反序列化逻辑由上层策略处理
            });
    }

    // ==================== 系统调用 6: yield ====================

    @Override
    public Mono<CheckpointId> yield(TaskId taskId, YieldReason reason, String currentState) {
        Checkpoint checkpoint = Checkpoint.of(taskId, "YIELDED", 0, currentState);
        return saveCheckpoint(checkpoint)
            .doOnNext(cpId -> emit(AgentEvent.of(taskId, EventType.TASK_PAUSED,
                reason.description(), Map.of("reason", reason.getClass().getSimpleName()))).subscribe());
    }

    // ==================== 系统调用 7: emit ====================

    @Override
    public Mono<Void> emit(AgentEvent event) {
        return Mono.fromRunnable(() -> {
            Sinks.Many<AgentEvent> sink = eventSinks.computeIfAbsent(
                event.taskId(), k -> Sinks.many().multicast().onBackpressureBuffer());
            // emitNext + busyLoop：并发生产者（think 流式 chunk 来自并行虚拟线程、NodeCompleted 来自
            // executor 线程）争用同一 sink 时，tryEmitNext 会因 FAIL_NON_SERIALIZED 静默丢事件。
            // busyLoop 在 FAIL_NON_SERIALIZED/FAIL_OVERFLOW 上忙等重试，保证不丢。
            sink.emitNext(event, EMIT_HANDLER);
        });
    }

    @Override
    public Flux<AgentEvent> observeEvents(TaskId taskId) {
        return eventSinks.computeIfAbsent(
            taskId, k -> Sinks.many().multicast().onBackpressureBuffer()).asFlux();
    }

    /**
     * 流式 chunk 事件直发——不经 {@link #emit(AgentEvent)} 的 Mono.fromRunnable 包装，
     * 避免在 think 的 doOnNext/doOnSuccess 热路径上每个 chunk 分配一个 Mono 再 subscribe。
     *
     * <p>并发安全：扇出并行节点的虚拟线程会并发调用（A/B/C 同时 think），
     * {@code emitNext + busyLoop} 在 FAIL_NON_SERIALIZED（并发争用）和 FAIL_OVERFLOW（订阅者滞后）
     * 上忙等重试——不丢事件，并对快产慢消形成天然背压。实测修复前 tryEmitNext 在并行节点下丢失
     * BLOCK_START（仅 A 的 START 到达，B/C 的丢失）。
     *
     * @param nodeId 非 null 时作为 metadata.nodeId 携带（仅 BLOCK_START/END；DELTA 传 null 保持裸 chunk）
     */
    private void emitChunk(TaskId taskId, EventType type, String data, NodeId nodeId) {
        Sinks.Many<AgentEvent> sink = eventSinks.computeIfAbsent(taskId,
            k -> Sinks.many().multicast().onBackpressureBuffer());
        Map<String, String> meta = (nodeId != null) ? Map.of("nodeId", nodeId.value()) : Map.of();
        sink.emitNext(AgentEvent.of(taskId, type, data, meta), EMIT_HANDLER);
    }

    // ==================== 内部辅助：注册执行结果 ====================

    /**
     * 记录节点的执行结果，供 observe() 查询。
     * 由策略层在节点执行完成后调用。
     */
    public void recordNodeResult(TaskId taskId, NodeId nodeId, Object result) {
        taskResults.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>()).put(nodeId, result);
    }

    // ==================== 内部接口 ====================

    /**
     * LLM 调用抽象。由 Spring AI ChatModel 实现。
     *
     * <p>call() 为非流式整包调用；stream() 为流式调用（每 chunk 一个 String）。
     * think() 默认走 stream() 以获得 idle-timeout 语义（健康慢不被误杀、卡死快速失败）。
     * 无流式能力的 provider（如测试 MockChatModel）只需实现 call()，stream() 退化为单元素流。
     */
    public interface LLMProvider {
        String call(String prompt);

        /** 流式调用。默认退化为单元素流（兼容无流式能力的 provider）。 */
        default Flux<String> stream(String prompt) {
            return Flux.just(call(prompt));
        }
    }

    /**
     * 工具执行抽象。@Tool 方法或 MCP 远程工具。
     */
    @FunctionalInterface
    public interface ToolExecutor {
        Object execute(Map<String, Object> arguments) throws Exception;
    }

    /**
     * 检查点存储抽象。
     */
    public interface CheckpointStore {
        Mono<Void> save(Checkpoint checkpoint);
        Mono<Checkpoint> loadLatest(TaskId taskId);
        Flux<Checkpoint> list(TaskId taskId);
    }

    /**
     * 安全违规异常——不用于系统 bug，只用于 Agent 行为越界。
     */
    public static class SafetyViolationException extends RuntimeException {
        private final List<Violation> violations;

        public SafetyViolationException(String message, List<Violation> violations) {
            super(message);
            this.violations = violations;
        }

        public List<Violation> violations() { return violations; }
    }
}
