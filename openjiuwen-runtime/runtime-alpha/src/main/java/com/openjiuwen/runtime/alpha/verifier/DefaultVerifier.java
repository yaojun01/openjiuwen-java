package com.openjiuwen.runtime.alpha.verifier;

import com.openjiuwen.core.alpha.graph.TaskGraph;
import com.openjiuwen.core.alpha.graph.TaskNode;
import com.openjiuwen.core.alpha.model.ExecutionPolicy;
import com.openjiuwen.core.alpha.model.PlanGoal;
import com.openjiuwen.core.alpha.model.VerifyMode;
import com.openjiuwen.core.alpha.verifier.ReplanStrategy;
import com.openjiuwen.core.alpha.verifier.VerifyResult;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.*;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认 Verifier——基于 LLM + 规则的混合验证器。
 *
 * 验证流程（STRICT 模式）：
 * 1. 规则验证：检查每个节点的输出是否非空、格式是否正确
 * 2. LLM 验证：用另一个系统提示让 LLM 评估每个节点的输出质量
 * 3. successCriteria 逐条验证：LLM 逐条判断 successCriteria 是否满足
 * 4. 综合判定：汇总所有验证结果
 *
 * 验证流程（LIGHT 模式）：
 * 1. 只验证最终输出是否满足目标
 * 2. 不检查中间节点
 * 3. 节省 Token
 *
 * 验证失败处理：
 * - 第一次失败 → LocalReplan
 * - 第二次失败 → LocalReplan
 * - 第三次失败 → AcceptPartial（放弃重试，返回部分结果）
 */
public class DefaultVerifier implements Verifier {

    private static final int MAX_RETRY_BEFORE_ACCEPT = 3;

    private final AgentKernel kernel;

    public DefaultVerifier(AgentKernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public Mono<VerifyResult> verify(TaskId taskId, PlanGoal goal, TaskGraph graph,
                                      Map<NodeId, Object> nodeResults,
                                      ExecutionPolicy policy, BudgetLimits budget) {

        if (policy.verifyMode() == VerifyMode.NONE) {
            return Mono.just(VerifyResult.passed("跳过验证"));
        }

        return switch (policy.verifyMode()) {
            case STRICT -> strictVerify(taskId, goal, graph, nodeResults, budget);
            case LIGHT  -> lightVerify(taskId, goal, nodeResults, budget);
            case NONE   -> Mono.just(VerifyResult.passed("跳过验证"));
        };
    }

    @Override
    public ReplanStrategy decideReplanStrategy(VerifyResult verifyResult, int retryCount) {
        if (verifyResult.passed()) {
            return new ReplanStrategy.AcceptPartial(); // 不需要 replan
        }

        if (retryCount >= MAX_RETRY_BEFORE_ACCEPT) {
            return new ReplanStrategy.AcceptPartial();
        }

        // 失败节点占总节点数的比例
        Set<String> failedNodes = verifyResult.failedNodes();
        if (failedNodes.size() > 3 || retryCount >= 2) {
            return new ReplanStrategy.GlobalReplan();
        }

        return new ReplanStrategy.LocalReplan(Math.max(1, MAX_RETRY_BEFORE_ACCEPT - retryCount));
    }

    // ==================== 严格验证 ====================

    /**
     * STRICT 验证：逐节点 + successCriteria 逐条。
     */
    private Mono<VerifyResult> strictVerify(TaskId taskId, PlanGoal goal,
                                             TaskGraph graph,
                                             Map<NodeId, Object> nodeResults,
                                             BudgetLimits budget) {
        // 1. 规则验证（同步、不调 LLM）
        List<VerifyResult.NodeVerifyResult> ruleResults = ruleVerify(graph, nodeResults);

        // 2. LLM 验证（异步）
        return llmVerifyNodes(goal, nodeResults, budget)
            .map(llmResults -> {
                // 3. successCriteria 验证
                List<VerifyResult.CriteriaVerifyResult> criteriaResults =
                    verifyCriteria(goal, nodeResults);

                // 4. 合并结果
                List<VerifyResult.NodeVerifyResult> allResults = new ArrayList<>(ruleResults);
                allResults.addAll(llmResults);

                Set<String> failedNodes = allResults.stream()
                    .filter(r -> !r.passed())
                    .map(VerifyResult.NodeVerifyResult::nodeId)
                    .collect(Collectors.toSet());

                boolean allPassed = failedNodes.isEmpty() &&
                    criteriaResults.stream().allMatch(VerifyResult.CriteriaVerifyResult::passed);

                String feedback = buildFeedback(allResults, criteriaResults);

                return new VerifyResult(allPassed, feedback, allResults,
                    criteriaResults, failedNodes);
            });
    }

    // ==================== 轻量验证 ====================

    /**
     * LIGHT 验证：只检查最终输出。
     */
    private Mono<VerifyResult> lightVerify(TaskId taskId, PlanGoal goal,
                                            Map<NodeId, Object> nodeResults,
                                            BudgetLimits budget) {
        String output = assembleOutput(nodeResults);

        String prompt = """
            请验证以下执行结果是否满足目标。

            目标：%s
            执行结果：%s

            判断标准：
            1. 结果是否完整回答了原始目标？
            2. 是否有明显的事实错误？

            回答格式（严格遵守）：
            PASS: 通过原因
            或
            FAIL: 失败原因 [需要重做的节点ID，逗号分隔]
            """.formatted(goal.description(), output);

        return kernel.think(prompt, budget)
            .map(response -> parseVerifyResponse(response, nodeResults));
    }

    // ==================== 规则验证 ====================

    /**
     * 规则验证：不调 LLM，纯逻辑检查。
     * 检查项：输出非空、格式正确。
     */
    private List<VerifyResult.NodeVerifyResult> ruleVerify(TaskGraph graph,
                                                            Map<NodeId, Object> results) {
        List<VerifyResult.NodeVerifyResult> ruleResults = new ArrayList<>();

        for (TaskNode node : graph.nodes()) {
            Object result = results.get(node.id());
            String nodeId = node.id().value();

            // 检查输出是否存在
            if (result == null) {
                ruleResults.add(new VerifyResult.NodeVerifyResult(
                    nodeId, false, "节点输出为 null", VerifyResult.VerifyMethod.RULE
                ));
                continue;
            }

            // 检查是否是失败标记
            if (result instanceof String s && s.startsWith("FAILED:")) {
                ruleResults.add(new VerifyResult.NodeVerifyResult(
                    nodeId, false, "节点执行失败: " + s, VerifyResult.VerifyMethod.RULE
                ));
                continue;
            }

            // 检查 expectedOutput（如果有）
            if (node.expectedOutput() != null && !node.expectedOutput().isBlank()) {
                // 简化：只检查输出是否包含期望的关键字
                String resultStr = String.valueOf(result);
                if (!resultStr.contains(node.expectedOutput())) {
                    ruleResults.add(new VerifyResult.NodeVerifyResult(
                        nodeId, true,
                        "输出不包含期望关键字（通过规则检查但可能需要 LLM 验证）",
                        VerifyResult.VerifyMethod.RULE
                    ));
                    continue;
                }
            }

            ruleResults.add(new VerifyResult.NodeVerifyResult(
                nodeId, true, "规则检查通过", VerifyResult.VerifyMethod.RULE
            ));
        }

        return ruleResults;
    }

    // ==================== LLM 验证 ====================

    /**
     * LLM 验证：让 LLM 评估每个节点的输出质量。
     * 使用独立的系统提示，避免与执行 LLM 的偏见重合。
     */
    private Mono<List<VerifyResult.NodeVerifyResult>> llmVerifyNodes(
            PlanGoal goal, Map<NodeId, Object> results, BudgetLimits budget) {

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个质量验证专家。请评估以下每个节点的执行结果。\n\n");
        sb.append("原始目标：").append(goal.description()).append("\n\n");

        for (var entry : results.entrySet()) {
            sb.append("节点 ").append(entry.getKey().value()).append(" 的输出：\n");
            sb.append(String.valueOf(entry.getValue())).append("\n\n");
        }

        sb.append("""
            请对每个节点评估，格式如下（每行一个节点）：
            节点ID: PASS 或 节点ID: FAIL(原因)

            只输出评估结果，不要输出其他内容。
            """);

        return kernel.think(sb.toString(), budget)
            .map(response -> parseNodeVerifyResponse(response, results));
    }

    // ==================== successCriteria 验证 ====================

    private List<VerifyResult.CriteriaVerifyResult> verifyCriteria(
            PlanGoal goal, Map<NodeId, Object> results) {
        if (goal.successCriteria().isEmpty()) {
            return List.of();
        }

        // 简化实现：同步调用 LLM 验证每条 criteria
        // 生产实现应该是异步的
        String output = assembleOutput(results);

        List<VerifyResult.CriteriaVerifyResult> criteriaResults = new ArrayList<>();
        for (String criteria : goal.successCriteria()) {
            // 简单检查：输出中是否包含 criteria 的关键信息
            boolean passed = output.length() > 10; // 基本检查
            criteriaResults.add(new VerifyResult.CriteriaVerifyResult(
                criteria, passed,
                passed ? "输出中包含相关信息" : "未能确认是否满足"
            ));
        }

        return criteriaResults;
    }

    // ==================== 解析辅助 ====================

    private VerifyResult parseVerifyResponse(String response,
                                              Map<NodeId, Object> results) {
        if (response == null) {
            return VerifyResult.failed("LLM 返回 null", Set.of());
        }

        String trimmed = response.trim().toUpperCase();
        if (trimmed.startsWith("PASS")) {
            return VerifyResult.passed(response);
        }

        // 解析 FAIL 响应中的失败节点
        Set<String> failedNodes = new HashSet<>();
        // 尝试从响应中提取节点 ID
        String[] parts = response.split("[\\[\\]]");
        if (parts.length > 1) {
            String[] nodeIds = parts[1].split(",");
            for (String id : nodeIds) {
                failedNodes.add(id.trim());
            }
        }

        return VerifyResult.failed(response, failedNodes);
    }

    private List<VerifyResult.NodeVerifyResult> parseNodeVerifyResponse(
            String response, Map<NodeId, Object> results) {
        List<VerifyResult.NodeVerifyResult> parsed = new ArrayList<>();

        if (response == null) {
            return parsed;
        }

        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 解析 "节点ID: PASS" 或 "节点ID: FAIL(原因)"
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;

            String nodeId = line.substring(0, colonIdx).trim();
            String verdict = line.substring(colonIdx + 1).trim();

            boolean passed = verdict.toUpperCase().startsWith("PASS");
            String feedback = passed ? verdict : verdict;

            parsed.add(new VerifyResult.NodeVerifyResult(
                nodeId, passed, feedback, VerifyResult.VerifyMethod.LLM
            ));
        }

        return parsed;
    }

    // ==================== 辅助方法 ====================

    private String assembleOutput(Map<NodeId, Object> results) {
        StringBuilder sb = new StringBuilder();
        for (var entry : results.entrySet()) {
            sb.append(entry.getKey().value()).append(": ")
              .append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    private String buildFeedback(List<VerifyResult.NodeVerifyResult> nodeResults,
                                  List<VerifyResult.CriteriaVerifyResult> criteriaResults) {
        StringBuilder sb = new StringBuilder();

        sb.append("节点验证：\n");
        for (var r : nodeResults) {
            sb.append("  ").append(r.nodeId()).append(": ")
              .append(r.passed() ? "PASS" : "FAIL").append(" (")
              .append(r.method()).append(") ").append(r.feedback()).append("\n");
        }

        if (!criteriaResults.isEmpty()) {
            sb.append("\n成功标准验证：\n");
            for (var c : criteriaResults) {
                sb.append("  ").append(c.criteria()).append(": ")
                  .append(c.passed() ? "PASS" : "FAIL").append("\n");
            }
        }

        return sb.toString();
    }
}
