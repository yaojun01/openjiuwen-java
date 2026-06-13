package com.openjiuwen.examples.claims;

import com.openjiuwen.core.alpha.executor.SuperstepResult;
import com.openjiuwen.core.alpha.graph.TaskGraph;
import com.openjiuwen.core.alpha.model.ExecutionPolicy;
import com.openjiuwen.core.alpha.model.PlanningMode;
import com.openjiuwen.core.alpha.model.VerifyMode;
import com.openjiuwen.core.kernel.model.Budget;
import com.openjiuwen.core.kernel.model.BudgetLimits;
import com.openjiuwen.core.kernel.model.NodeId;
import com.openjiuwen.core.kernel.model.TaskId;
import com.openjiuwen.core.kernel.model.TaskInput;
import com.openjiuwen.runtime.alpha.executor.DefaultPregelExecutor;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例 #7 启动类：保险理赔核赔全案复审（双变体）。
 *
 * <p>两种编排方式同框演示（共享 {@link ClaimAdjudicationAgent} 的 3 个域工具）：
 * <ul>
 *   <li>变体 A（auto）：LLM 自动规划——{@code AgentClient.invokeStream} → AlphaStrategy 的 Plan-Execute-Verify；</li>
 *   <li>变体 B（static）：手工静态图——{@link ClaimAdjudicationGraph} 直接交 DefaultPregelExecutor 执行。</li>
 * </ul>
 *
 * <p>切换：{@code --claim.variant=auto|static|both}（默认 both）。两变体相互隔离（各自 try/catch），
 * 一者异常不影响另一者。
 */
@SpringBootApplication
public class ClaimAdjudicationExample {

    public static void main(String[] args) {
        SpringApplication.run(ClaimAdjudicationExample.class, args);
    }

    @Bean
    CommandLineRunner claimDemo(AgentClient agentClient, AgentKernel kernel,
                                @Value("${claim.variant:both}") String variant) {
        return args -> {
            // 减赔用例：理算比例错误，最能体现复审价值（计算额偏高 + 命中医疗 5 万上级复核阈值）。
            String caseNo = "CLM-2026-REDUCE";
            System.out.println("=== 保险理赔核赔全案复审（#7）— variant=" + variant + " ===");

            if ("auto".equals(variant) || "both".equals(variant)) {
                try {
                    runVariantAuto(agentClient, caseNo);
                } catch (Exception e) {
                    System.out.println("[A] 变体 A 异常（不影响变体 B）：" + e);
                }
            }
            if ("static".equals(variant) || "both".equals(variant)) {
                try {
                    runVariantStatic(kernel, caseNo);
                } catch (Exception e) {
                    System.out.println("[B] 变体 B 异常：" + e);
                }
            }
            System.out.println("=== 示例完成 ===");
        };
    }

    /** 变体 A：LLM 自动规划图（AlphaStrategy 全流程 Plan→Execute→Verify）。 */
    private void runVariantAuto(AgentClient agentClient, String caseNo) {
        System.out.println("\n--- 变体 A：LLM 自动规划（invokeStream → PEV）---");
        // task 正文须给出工具签名（参数名 caseNo）：框架 planner 只向 LLM 暴露工具名，不暴露参数签名，
        // 不在文案补签名的话，LLM 生成的 TOOL_CALL inputs key 会与 @Tool 注入名（param.getName()=caseNo）不符 → 工具收 null。
        String task = """
                对案件 %s 做核赔全案复审，给出决策建议（准赔 / 减赔 / 挂起待查 / 拒赔）并附关键依据。

                ## 可用工具（TOOL_CALL 节点的 description 填工具方法名；inputs 的 key 必须用括号内参数名）
                - getCaseStatus(caseNo)：查询案件状态、基础信息与定责结论
                - getCaseDocuments(caseNo)：查询材料完整性、理算书与医审核定
                - scoreFraudRisk(caseNo)：评估欺诈风险分与指标
                其中 caseNo 为案号字面量（本案件：%s）。

                ## 交付要求
                先并行调用上述 3 个工具取数，再综合材料齐全性 / 立案合理性 / 医审准确性 / 理算正确性 / 流程合规
                五维复核，最后给出核赔决策建议。命中大额阈值（医疗≥5万 / 重疾≥10万 / 意外≥3万）须提示上级复核。
                """.formatted(caseNo, caseNo);

        // 关键：availableTools 告知 planner 可用工具名（必须与 @Tool 方法名一致）；单参 invokeStream(String)
        // 不传 availableTools，规划期 LLM 将看不到任何工具名（AgentClient 不自动从 @Agent 抽取工具注入 availableTools）。
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userInput", task);
        parameters.put("availableTools", List.of("getCaseStatus", "getCaseDocuments", "scoreFraudRisk"));

        agentClient.invokeStream("claim-adjudication-agent", parameters)
                .doOnNext(event -> System.out.println("[A 事件] "
                        + event.getClass().getSimpleName() + " → " + event))
                .blockLast();
    }

    /** 变体 B：手工静态 TaskGraph，直接交 DefaultPregelExecutor 执行（绕过 planner / verify）。 */
    private void runVariantStatic(AgentKernel kernel, String caseNo) {
        System.out.println("\n--- 变体 B：手工静态图（DefaultPregelExecutor 直接执行）---");
        TaskGraph graph = ClaimAdjudicationGraph.buildReviewGraph(caseNo);
        // 复用 Spring 注入的 AgentKernel（其内已注册本 Agent 的 3 个 @Tool）。
        // 复用同一 taskId：ctx 与 execute 共用，避免事件 sink 归属分裂（DefaultAgentKernel 按 taskId 持有 SinkHolder）。
        TaskId taskId = TaskId.generate();
        TaskContext ctx = new TaskContext(
                taskId, null, TaskInput.of("核赔全案复审：" + caseNo), null,
                kernel, Budget.Fixed.developmentDefault(), null, Map.of());
        // DefaultPregelExecutor 静态执行路径仅消费 maxParallelism（=5）；verifyMode / planningMode /
        // maxRetries / enableAdaptiveReplanning 均不读取（重试由构造期 ErrorPolicy.Retry(3) 决定）。
        // Verify 是 AlphaStrategy 的 Phase 3，本路径绕过它，故 verifyMode=NONE 在此为空操作而非"跳过校验"。
        ExecutionPolicy policy = new ExecutionPolicy(PlanningMode.AUTO, VerifyMode.NONE, 1, 5, false);
        try (DefaultPregelExecutor executor = new DefaultPregelExecutor(ctx)) {
            // block 超时须 ≥ 执行器层超时（LAYER_TIMEOUT_MS=300s），否则慢 LLM 会被 reactor 提前掐断、
            // 误报"变体 B 异常"并吞掉正常决策输出。developmentDefault 的 timeoutMillis=0（不做内部兜底）。
            List<SuperstepResult> steps = executor.execute(
                            taskId, graph, policy,
                            BudgetLimits.start(Budget.Fixed.developmentDefault()))
                    .collectList().block(Duration.ofSeconds(300));
            System.out.println("[B] 超步数（层数）=" + (steps == null ? 0 : steps.size()));
            if (steps != null) {
                for (SuperstepResult s : steps) {
                    System.out.println("    层" + s.superstepIndex()
                            + "：成功=" + s.allSucceeded()
                            + " 节点数=" + s.nodeResults().size()
                            + (s.hasFailures() ? " 失败=" + s.failedNodes() : ""));
                }
                // 收敛决策节点输出；未产出（如 LLM 未配置/失败致 FailFast 停在 L1、或决策节点自身失败）则显式告警，
                // 避免静默"看似完成却无决策"（与变体 A 的失败可观测性对齐）。
                Object decision = steps.stream().reduce((a, b) -> b)
                        .map(s -> s.nodeResults().get(new NodeId("decision")))
                        .orElse(null);
                if (decision instanceof String dec && !dec.startsWith("FAILED:")) {
                    System.out.println("[B] 决策建议：" + dec);
                } else {
                    System.out.println("[B] 未产出决策（可能 LLM 未配置/失败：需配 spring.ai.openai.api-key 才能产出真实决策；"
                            + "确定性数据流见 ClaimAdjudicationGraphTest）");
                }
            }
        }
    }
}
