package com.openjiuwen.runtime.alpha.executor;

import com.openjiuwen.core.alpha.graph.*;
import com.openjiuwen.core.alpha.model.*;
import com.openjiuwen.core.alpha.model.ErrorPolicy;
import com.openjiuwen.core.alpha.executor.SubAgentBudget;
import com.openjiuwen.core.alpha.executor.SuperstepResult;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认 Pregel BSP 执行器——基于 Java 21 虚拟线程的并行执行。
 *
 * 架构：
 * ┌─────────────────────────────────────────────────────┐
 * │                    PregelExecutor                     │
 * │  ┌─────────┐  ┌─────────┐  ┌─────────┐             │
 * │  │Superstep0│→│Superstep1│→│Superstep2│→ ...        │
 * │  │ [A,B,C]  │  │ [D,E]   │  │  [F]    │             │
 * │  │ vthread  │  │ vthread │  │ vthread │             │
 * │  │ vthread  │  │ vthread │  │         │             │
 * │  │ vthread  │  │         │  │         │             │
 * │  └────┬─────┘  └────┬────┘  └────┬────┘             │
 * │       │              │            │                   │
 * │    barrier        barrier     barrier                │
 * └─────────────────────────────────────────────────────┘
 *
 * 每个超步：
 * 1. 获取本层节点列表
 * 2. 为每个节点创建虚拟线程
 * 3. 并行执行所有节点
 * 4. 同步屏障等待所有节点完成
 * 5. 处理失败节点（根据 ErrorPolicy）
 * 6. 收集结果，进入下一个超步
 *
 * 子 Agent 递归限制：
 * - 最大递归深度 3 层
 * - 通过 ThreadLocal 传递递归深度
 * - 超过深度限制时抛出异常
 */
public class DefaultPregelExecutor implements PregelExecutor {

    private static final int MAX_SUB_AGENT_DEPTH = 3;
    private static final long NODE_TIMEOUT_MS = 60_000L; // 单节点超时 60 秒

    private final TaskContext context;
    private final AgentKernel kernel;
    private final ExecutorService virtualThreadExecutor;
    private final ErrorPolicy errorPolicy;
    private final SubAgentBudget subAgentBudgetStrategy;
    private final ThreadLocal<Integer> currentDepth = ThreadLocal.withInitial(() -> 0);

    public DefaultPregelExecutor(TaskContext context) {
        this(context, new ErrorPolicy.Retry(3, 1000L, new ErrorPolicy.FailFast()),
             new SubAgentBudget.Proportional(0.3));
    }

    public DefaultPregelExecutor(TaskContext context, ErrorPolicy errorPolicy,
                                  SubAgentBudget subAgentBudgetStrategy) {
        this.context = context;
        this.kernel = context.kernel();
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.errorPolicy = errorPolicy;
        this.subAgentBudgetStrategy = subAgentBudgetStrategy;
    }

    @Override
    public Flux<SuperstepResult> execute(TaskId taskId, TaskGraph graph,
                                          ExecutionPolicy policy, BudgetLimits budget) {
        List<List<TaskNode>> layers = graph.executionLayers();

        return Flux.<SuperstepResult>create(sink -> {
            try {
                Map<NodeId, Object> accumulatedResults = new ConcurrentHashMap<>();
                BudgetLimits currentBudget = budget;

                for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
                    // 预算检查
                    if (currentBudget.isExceeded()) {
                        sink.error(new RuntimeException("预算耗尽，在第 " + layerIdx + " 层中止"));
                        return;
                    }

                    List<TaskNode> layer = layers.get(layerIdx);
                    int maxParallelism = policy.maxParallelism();

                    // 执行一个超步
                    SuperstepResult result = executeSuperstep(
                        taskId, layer, accumulatedResults, currentBudget,
                        maxParallelism, layerIdx
                    );

                    sink.next(result);

                    // 合并结果
                    accumulatedResults.putAll(result.nodeResults());

                    // 处理失败
                    if (result.hasFailures()) {
                        ErrorHandlingOutcome outcome = handleError(
                            taskId, result, graph, accumulatedResults,
                            currentBudget, layerIdx, layers
                        );

                        if (outcome.shouldStop()) {
                            sink.complete();
                            return;
                        }

                        // PartialReplan：重新执行失败节点
                        if (outcome.retryNodes() != null && !outcome.retryNodes().isEmpty()) {
                            accumulatedResults.putAll(outcome.retryResults());
                        }
                    }

                    // 更新预算
                    currentBudget = currentBudget.withElapsed(System.currentTimeMillis());
                }

                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== 超步执行 ====================

    /**
     * 执行一个超步（同层节点并行执行）。
     *
     * 使用 Java 21 虚拟线程：
     * - 每个节点一个虚拟线程
     * - 通过 Semaphore 控制最大并行度
     * - 通过 CompletableFuture.allOf() 实现屏障同步
     */
    private SuperstepResult executeSuperstep(
            TaskId taskId, List<TaskNode> layer,
            Map<NodeId, Object> accumulatedResults,
            BudgetLimits budget, int maxParallelism, int superstepIndex) {

        Map<NodeId, Object> nodeResults = new ConcurrentHashMap<>();
        Set<NodeId> failedNodes = ConcurrentHashMap.newKeySet();
        Set<NodeId> skippedNodes = ConcurrentHashMap.newKeySet();
        Semaphore semaphore = new Semaphore(maxParallelism);
        AtomicInteger llmTokensUsed = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (TaskNode node : layer) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        Object result = executeNode(taskId, node, accumulatedResults, budget);
                        nodeResults.put(node.id(), result);
                    } finally {
                        semaphore.release();
                    }
                } catch (Exception e) {
                    failedNodes.add(node.id());
                    nodeResults.put(node.id(), "FAILED: " + e.getMessage());
                }
            }, virtualThreadExecutor));
        }

        // 同步屏障：等待所有节点完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(NODE_TIMEOUT_MS * layer.size(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时的节点标记为失败
            for (TaskNode node : layer) {
                if (!nodeResults.containsKey(node.id()) && !failedNodes.contains(node.id())) {
                    failedNodes.add(node.id());
                    skippedNodes.add(node.id());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("执行被中断", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("执行异常", e);
        }

        return new SuperstepResult(superstepIndex, nodeResults, failedNodes, skippedNodes);
    }

    // ==================== 节点执行 ====================

    private Object executeNode(TaskId taskId, TaskNode node,
                               Map<NodeId, Object> accumulatedResults,
                               BudgetLimits budget) {
        return switch (node.type()) {
            case TOOL_CALL -> executeToolNode(node, accumulatedResults, budget);
            case LLM_CALL  -> executeLLMNode(node, accumulatedResults, budget);
            case SUB_AGENT -> executeSubAgentNode(node, accumulatedResults, budget);
        };
    }

    /**
     * 执行工具调用节点。
     */
    private Object executeToolNode(TaskNode node, Map<NodeId, Object> results,
                                    BudgetLimits budget) {
        Map<String, Object> resolvedArgs = resolveInputs(node.inputs(), results);
        ToolName toolName = new ToolName(node.description());
        ToolResult result = kernel.invokeTool(toolName, resolvedArgs, budget).block();
        if (result != null && result.success()) {
            return result.result();
        }
        throw new RuntimeException("工具调用失败: " + (result != null ? result.error() : "null"));
    }

    /**
     * 执行 LLM 调用节点。
     */
    private Object executeLLMNode(TaskNode node, Map<NodeId, Object> results,
                                   BudgetLimits budget) {
        String prompt = resolveTemplate(node.description(), results);
        return kernel.think(prompt, budget).block();
    }

    /**
     * 执行子 Agent 节点——递归 PEV。
     *
     * 递归深度限制：最深 3 层。
     * 预算分配：从父 Agent 剩余预算中按策略分配。
     * 结果回传：子 Agent 的最终输出作为本节点的输出。
     */
    private Object executeSubAgentNode(TaskNode node, Map<NodeId, Object> results,
                                        BudgetLimits budget) {
        int depth = currentDepth.get();
        if (depth >= MAX_SUB_AGENT_DEPTH) {
            throw new RuntimeException(
                "子 Agent 递归深度已达上限 " + MAX_SUB_AGENT_DEPTH + " 层");
        }

        currentDepth.set(depth + 1);
        try {
            String subGoal = resolveTemplate(node.description(), results);

            // 分配子 Agent 预算
            Budget subBudget = allocateSubBudget(budget);
            TaskId subTaskId = TaskId.generate();
            TaskContext subContext = context.forSubTask(subTaskId, TaskInput.of(subGoal));

            // 子 Agent 也用 Alpha 策略（PEV 递归）
            // 简化实现：直接调用 kernel.think() 模拟子 Agent
            String subResult = kernel.think(
                "子任务: " + subGoal + "\n请直接执行并返回结果。",
                BudgetLimits.start(subBudget)
            ).block();

            return subResult;
        } finally {
            currentDepth.set(depth);
        }
    }

    /**
     * 为子 Agent 分配预算。
     */
    private Budget allocateSubBudget(BudgetLimits parentBudget) {
        return switch (subAgentBudgetStrategy) {
            case SubAgentBudget.Proportional p -> {
                long remaining = parentBudget.budget().maxTokens() - parentBudget.usedTokens();
                yield new Budget.Fixed(
                    Math.max(1, (int)(parentBudget.budget().maxLLMCalls() * p.ratio())),
                    Math.max(1, (int)(parentBudget.budget().maxToolCalls() * p.ratio())),
                    Math.max(1000, (long)(remaining * p.ratio())),
                    60_000L
                );
            }
            case SubAgentBudget.Fixed f -> f.budget();
            case SubAgentBudget.Inherit _ -> {
                long remaining = parentBudget.budget().maxTokens() - parentBudget.usedTokens();
                yield new Budget.Fixed(
                    parentBudget.budget().maxLLMCalls(),
                    parentBudget.budget().maxToolCalls(),
                    Math.max(1000, remaining),
                    parentBudget.budget().timeoutMillis()
                );
            }
        };
    }

    // ==================== 错误处理 ====================

    private ErrorHandlingOutcome handleError(
            TaskId taskId, SuperstepResult result,
            TaskGraph graph, Map<NodeId, Object> accumulatedResults,
            BudgetLimits budget, int currentLayer,
            List<List<TaskNode>> remainingLayers) {

        return switch (errorPolicy) {
            case ErrorPolicy.FailFast _ -> {
                // 一个失败，全部终止
                yield ErrorHandlingOutcome.stop();
            }

            case ErrorPolicy.Retry retry -> {
                Map<NodeId, Object> retryResults = new HashMap<>();
                boolean allRecovered = true;

                for (NodeId failedId : result.failedNodes()) {
                    Object retryResult = retryNode(taskId, failedId, graph,
                        accumulatedResults, budget, retry.maxRetries(), retry.backoffMs());
                    if (retryResult != null) {
                        retryResults.put(failedId, retryResult);
                    } else {
                        allRecovered = false;
                        // 重试耗尽，使用 onExhausted 策略
                        if (retry.onExhausted() instanceof ErrorPolicy.FailFast) {
                            yield ErrorHandlingOutcome.stop();
                        }
                    }
                }

                yield allRecovered
                    ? ErrorHandlingOutcome.continueWith(retryResults)
                    : ErrorHandlingOutcome.stop();
            }

            case ErrorPolicy.Degrade degrade -> {
                // 降级：用默认值替代失败节点
                Map<NodeId, Object> degraded = new HashMap<>();
                for (NodeId failedId : result.failedNodes()) {
                    degraded.put(failedId, degrade.defaultValue());
                }
                yield ErrorHandlingOutcome.continueWith(degraded);
            }

            case ErrorPolicy.PartialReplan replan -> {
                // 局部重规划：找下游节点，重置为 PENDING，重新执行
                // 简化实现：重试失败节点
                Map<NodeId, Object> retryResults = new HashMap<>();
                for (NodeId failedId : result.failedNodes()) {
                    Object retryResult = retryNode(taskId, failedId, graph,
                        accumulatedResults, budget, 1, 0L);
                    if (retryResult != null) {
                        retryResults.put(failedId, retryResult);
                    }
                }
                yield retryResults.size() == result.failedNodes().size()
                    ? ErrorHandlingOutcome.continueWith(retryResults)
                    : ErrorHandlingOutcome.stop();
            }
        };
    }

    /**
     * 重试单个节点。
     * 使用指数退避：第 i 次重试等待 backoffMs * 2^i 毫秒。
     */
    private Object retryNode(TaskId taskId, NodeId nodeId, TaskGraph graph,
                             Map<NodeId, Object> results, BudgetLimits budget,
                             int maxRetries, long backoffMs) {
        TaskNode node = graph.nodes().stream()
            .filter(n -> n.id().equals(nodeId))
            .findFirst()
            .orElseThrow();

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                if (attempt > 0 && backoffMs > 0) {
                    Thread.sleep(backoffMs * (1L << (attempt - 1))); // 指数退避
                }
                return executeNode(taskId, node, results, budget);
            } catch (Exception e) {
                // 继续重试
            }
        }
        return null; // 重试耗尽
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> resolveInputs(Map<String, String> inputs,
                                               Map<NodeId, Object> results) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : inputs.entrySet()) {
            String value = entry.getValue();
            if (value.startsWith("${") && value.endsWith("}")) {
                String ref = value.substring(2, value.length() - 1);
                String[] parts = ref.split("\\.", 2);
                NodeId refNodeId = new NodeId(parts[0]);
                Object nodeResult = results.get(refNodeId);
                if (nodeResult != null) {
                    resolved.put(entry.getKey(), nodeResult);
                }
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private String resolveTemplate(String template, Map<NodeId, Object> results) {
        String resolved = template;
        for (var entry : results.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey().value() + ".output}",
                String.valueOf(entry.getValue()));
        }
        return resolved;
    }

    // ==================== 错误处理结果 ====================

    private record ErrorHandlingOutcome(
        boolean shouldStop,
        Map<NodeId, Object> retryResults,
        Set<NodeId> retryNodes
    ) {
        static ErrorHandlingOutcome stop() {
            return new ErrorHandlingOutcome(true, Map.of(), Set.of());
        }

        static ErrorHandlingOutcome continueWith(Map<NodeId, Object> results) {
            return new ErrorHandlingOutcome(false, results, results.keySet());
        }
    }
}
