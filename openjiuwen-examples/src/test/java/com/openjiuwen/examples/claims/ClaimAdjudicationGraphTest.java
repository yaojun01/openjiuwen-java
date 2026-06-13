package com.openjiuwen.examples.claims;

import com.openjiuwen.core.alpha.executor.SuperstepResult;
import com.openjiuwen.core.alpha.graph.TaskGraph;
import com.openjiuwen.core.alpha.model.ExecutionPolicy;
import com.openjiuwen.core.alpha.model.PlanningMode;
import com.openjiuwen.core.alpha.model.VerifyMode;
import com.openjiuwen.core.kernel.model.Budget;
import com.openjiuwen.core.kernel.model.BudgetLimits;
import com.openjiuwen.core.kernel.model.Checkpoint;
import com.openjiuwen.core.kernel.model.NodeId;
import com.openjiuwen.core.kernel.model.TaskId;
import com.openjiuwen.core.kernel.model.TaskInput;
import com.openjiuwen.core.kernel.model.ToolName;
import com.openjiuwen.runtime.alpha.executor.DefaultPregelExecutor;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.runtime.core.engine.DefaultAgentKernel;
import com.openjiuwen.runtime.core.engine.DefaultSafetyBoundary;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变体 B 确定性证明：手工静态 TaskGraph 经生产 {@link DefaultPregelExecutor} 执行（mock LLM，无 API key）。
 *
 * <p>对 3 个用例（准赔 / 减赔 / 挂起）各跑一遍，断言：
 * <ol>
 *   <li>3 层拓扑（3 取数 + 5 复审 + 1 决策 = 9 节点）全部成功、无失败节点；</li>
 *   <li>决策节点产出非空；</li>
 *   <li>决策输出含案号 —— 证明 工具→复审→决策 的完整数据管道
 *       （${nodeId.output} 整值引用链路在 resolveInputs 正确兑现）。</li>
 * </ol>
 *
 * <p>mock LLM 见到案号即回显，使案号经 复审节点 输出流入 决策节点 的 prompt，
 * 决策输出复现案号即证明端到端数据流。工具复用真实 {@link ClaimAdjudicationAgent} 的 @Tool 方法（真实 fixture JSON）。
 */
class ClaimAdjudicationGraphTest {

    private static final List<String> CASES =
            List.of("CLM-2026-APPROVE", "CLM-2026-REDUCE", "CLM-2026-HOLD");

    @Test
    void staticGraph_threeLayers_allSucceed_andDecisionCarriesCaseNo() {
        ClaimAdjudicationAgent agent = new ClaimAdjudicationAgent();

        // mock LLM：见到案号即回显（用于证明数据流到决策节点）。
        DefaultAgentKernel.LLMProvider llm = new DefaultAgentKernel.LLMProvider() {
            @Override
            public String call(String prompt) {
                for (String c : CASES) {
                    if (prompt.contains(c)) {
                        return "[mock-llm] 复审通过，已处理案件 " + c;
                    }
                }
                return "[mock-llm] 复审通过";
            }
        };

        // 复用真实 @Tool 方法（真实 fixture JSON）作为工具执行器。
        Map<ToolName, DefaultAgentKernel.ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(new ToolName("getCaseStatus"),
                args -> agent.getCaseStatus((String) args.get("caseNo")));
        tools.put(new ToolName("getCaseDocuments"),
                args -> agent.getCaseDocuments((String) args.get("caseNo")));
        tools.put(new ToolName("scoreFraudRisk"),
                args -> agent.scoreFraudRisk((String) args.get("caseNo")));

        DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, tools, noOpCheckpointStore(), new DefaultSafetyBoundary());

        for (String caseNo : CASES) {
            TaskGraph graph = ClaimAdjudicationGraph.buildReviewGraph(caseNo);
            TaskContext ctx = new TaskContext(
                    TaskId.generate(), null, TaskInput.of("复审：" + caseNo), null,
                    kernel, Budget.Fixed.developmentDefault(), null, Map.of());
            ExecutionPolicy policy = new ExecutionPolicy(PlanningMode.AUTO, VerifyMode.NONE, 1, 5, false);

            List<SuperstepResult> steps;
            try (DefaultPregelExecutor executor = new DefaultPregelExecutor(ctx)) {
                steps = executor.execute(
                                TaskId.generate(), graph, policy,
                                BudgetLimits.start(Budget.Fixed.developmentDefault()))
                        .collectList().block(Duration.ofSeconds(60));
            }

            assertNotNull(steps, "execute 不应返回 null（案件 " + caseNo + "）");
            int totalNodes = 0;
            String decisionOutput = null;
            for (SuperstepResult s : steps) {
                assertTrue(s.failedNodes().isEmpty(),
                        "案件 " + caseNo + " 层" + s.superstepIndex() + " 不应有失败节点: " + s.failedNodes());
                totalNodes += s.nodeResults().size();
                Object d = s.nodeResults().get(new NodeId("decision"));
                if (d != null) {
                    decisionOutput = String.valueOf(d);
                }
            }
            assertTrue(totalNodes == 9,
                    "案件 " + caseNo + " 应执行 9 个节点（3 取数 + 5 复审 + 1 决策），实际 " + totalNodes);
            assertNotNull(decisionOutput, "案件 " + caseNo + " 决策节点应产出结果");
            assertTrue(decisionOutput.contains(caseNo),
                    "案件 " + caseNo + " 决策输出应含案号（证明 工具→复审→决策 数据流），实际: " + decisionOutput);
        }
    }

    /** no-op 检查点存储（本测试不依赖检查点恢复）。 */
    private static DefaultAgentKernel.CheckpointStore noOpCheckpointStore() {
        return new DefaultAgentKernel.CheckpointStore() {
            @Override
            public Mono<Void> save(Checkpoint checkpoint) {
                return Mono.empty();
            }

            @Override
            public Mono<Checkpoint> loadLatest(TaskId taskId) {
                return Mono.empty();
            }

            @Override
            public Flux<Checkpoint> list(TaskId taskId) {
                return Flux.empty();
            }
        };
    }
}
