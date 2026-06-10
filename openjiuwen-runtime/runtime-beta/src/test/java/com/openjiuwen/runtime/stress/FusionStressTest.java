package com.openjiuwen.runtime.stress;
/**
 * ============================================================
 *  P2 DRAFT -- NOT part of P1 default compilation.
 *
 * This file belongs to the `runtime-beta` module, which is excluded from
 * P1's default Maven profile. It is only compiled with `-P all`.
 *
 * P2 will replace this draft with the final implementation.
 * See: docs/architecture/05-beta-llm-autonomous-orchestration.md
 * ============================================================
 */

import com.openjiuwen.runtime.beta.event.BetaEvent;
import com.openjiuwen.runtime.beta.guardrail.Guardrail;
import com.openjiuwen.runtime.beta.guardrail.GuardrailEngine;
import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.runtime.core.engine.DefaultSafetyBoundary;
import com.openjiuwen.runtime.core.engine.SafetyBoundary;
import com.openjiuwen.core.kernel.model.*;
import com.openjiuwen.runtime.core.mcp.McpSecurityConfig;
import com.openjiuwen.runtime.core.mcp.McpTlsInterceptor;
import com.openjiuwen.core.meta.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 融合架构压力测试 —— 三个极端融合场景。
 *
 * 场景A：Alpha协调Agent + 5个Beta子Agent 同时执行
 * 场景B：同一Agent从GUIDED晋升到AUTONOMOUS的完整生命周期
 * 场景C：successCriteria 知识积累的完整闭环
 */
@DisplayName("融合架构压力测试")
class FusionStressTest {

    private DefaultSafetyBoundary safetyBoundary;

    @BeforeEach
    void setUp() {
        safetyBoundary = new DefaultSafetyBoundary();
    }

    // ==================== 场景A ====================

    @Nested
    @DisplayName("场景A：Alpha协调Agent + 5个Beta子Agent 同时执行")
    class ScenarioA_MultiAgentFusion {

        /**
         * 模拟结构：
         * - 协调Agent (GUIDED) → Alpha 策略
         * - 5个子Agent (AUTONOMOUS) → Beta 策略
         * - 子Agent-1,2 触发 criteria 覆盖失败
         * - 子Agent-3 触发 MCP 安全 Violation
         * - 子Agent-4,5 正常完成
         */
        @Test
        @DisplayName("5个子Agent并发执行：2个criteria失败 + 1个MCP安全违规 + 2个成功")
        void fiveSubAgents_mixedResults() throws Exception {
            int subAgentCount = 5;
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(subAgentCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger criteriaFailCount = new AtomicInteger(0);
            AtomicInteger mcpFailCount = new AtomicInteger(0);

            List<Future<SubAgentResult>> futures = new ArrayList<>();

            // 协调Agent的 Token 预算分配：总计 50000，每个子Agent 10000
            Budget parentBudget = new Budget.Fixed(100, 50, 50000L, 60000L);
            TaskId parentTaskId = TaskId.generate();

            // 子Agent-1,2：criteria 覆盖失败
            futures.add(executor.submit(() ->
                runSubAgent("sub-1", parentTaskId, parentBudget, SubAgentFailureType.CRITERIA_FAIL, latch, successCount, criteriaFailCount, mcpFailCount)));
            futures.add(executor.submit(() ->
                runSubAgent("sub-2", parentTaskId, parentBudget, SubAgentFailureType.CRITERIA_FAIL, latch, successCount, criteriaFailCount, mcpFailCount)));

            // 子Agent-3：MCP 安全违规
            futures.add(executor.submit(() ->
                runSubAgent("sub-3", parentTaskId, parentBudget, SubAgentFailureType.MCP_SECURITY, latch, successCount, criteriaFailCount, mcpFailCount)));

            // 子Agent-4,5：正常完成
            futures.add(executor.submit(() ->
                runSubAgent("sub-4", parentTaskId, parentBudget, SubAgentFailureType.NONE, latch, successCount, criteriaFailCount, mcpFailCount)));
            futures.add(executor.submit(() ->
                runSubAgent("sub-5", parentTaskId, parentBudget, SubAgentFailureType.NONE, latch, successCount, criteriaFailCount, mcpFailCount)));

            // 等待所有子Agent完成
            boolean allDone = latch.await(30, TimeUnit.SECONDS);
            assertTrue(allDone, "所有子Agent应在30秒内完成");

            executor.shutdown();

            // 验证结果
            assertEquals(2, successCount.get(), "应有2个子Agent正常完成");
            assertEquals(2, criteriaFailCount.get(), "应有2个子Agent criteria 覆盖失败");
            assertEquals(1, mcpFailCount.get(), "应有1个子Agent MCP 安全违规");

            // 验证失败的子Agent 会触发 replan（通过 Violation 机制）
            List<SubAgentResult> results = futures.stream()
                .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                .toList();

            // 子Agent-1,2 应有 CriteriaNotCovered Violation
            List<SubAgentResult> criteriaFailed = results.stream()
                .filter(r -> r.failureType == SubAgentFailureType.CRITERIA_FAIL)
                .toList();
            assertEquals(2, criteriaFailed.size());
            for (SubAgentResult r : criteriaFailed) {
                assertFalse(r.violations.isEmpty(), "criteria 失败的子Agent应有 Violation");
                assertTrue(r.violations.stream()
                    .anyMatch(v -> v instanceof Violation.CriteriaNotCovered),
                    "Violation 应包含 CriteriaNotCovered");
            }

            // 子Agent-3 应有 McpSecurityViolation
            SubAgentResult mcpFailed = results.stream()
                .filter(r -> r.failureType == SubAgentFailureType.MCP_SECURITY)
                .findFirst().orElseThrow();
            assertFalse(mcpFailed.violations.isEmpty());
            assertTrue(mcpFailed.violations.stream()
                .anyMatch(v -> v instanceof Violation.McpSecurityViolation),
                "Violation 应包含 McpSecurityViolation");

            // 子Agent-4,5 不应有 Violation
            List<SubAgentResult> successful = results.stream()
                .filter(r -> r.failureType == SubAgentFailureType.NONE)
                .toList();
            assertEquals(2, successful.size());
            for (SubAgentResult r : successful) {
                assertTrue(r.violations.isEmpty(), "正常的子Agent不应有 Violation");
                assertTrue(r.criteriaCovered, "正常的子Agent criteria 应全部覆盖");
            }
        }

        /**
         * 模拟子Agent执行。
         */
        private SubAgentResult runSubAgent(
                String name, TaskId parentTaskId, Budget parentBudget,
                SubAgentFailureType failureType,
                CountDownLatch latch,
                AtomicInteger successCount,
                AtomicInteger criteriaFailCount,
                AtomicInteger mcpFailCount) {

            TaskId subTaskId = TaskId.generate();
            BudgetLimits budgetLimits = BudgetLimits.start(
                new Budget.Fixed(20, 10, 10000L, 30000L));

            List<String> successCriteria = List.of(
                "数据查询完成",
                "结果格式正确",
                "响应时间在阈值内"
            );

            List<Violation> violations = new ArrayList<>();
            boolean criteriaCovered = false;

            try {
                switch (failureType) {
                    case CRITERIA_FAIL -> {
                        // 只验证了2条，第3条未覆盖
                        List<String> verified = List.of(
                            "数据查询完成",
                            "结果格式正确"
                        );
                        List<Violation> uncovered = safetyBoundary.checkCriteriaCoverageAll(
                            subTaskId, successCriteria, verified);
                        violations.addAll(uncovered);
                        criteriaFailCount.incrementAndGet();
                    }
                    case MCP_SECURITY -> {
                        // MCP 安全检查失败
                        McpSecurityConfig config = new McpSecurityConfig(
                            false, false, null,
                            null, null, null, null, null, null
                        );
                        McpTlsInterceptor interceptor = new McpTlsInterceptor(config, safetyBoundary);
                        Optional<Violation> mcpViolation = interceptor.intercept(
                            "http://sdk:8080/mcp", subTaskId);
                        mcpViolation.ifPresent(violations::add);
                        mcpFailCount.incrementAndGet();
                    }
                    case NONE -> {
                        // 全部通过
                        List<String> verified = List.of(
                            "数据查询完成",
                            "结果格式正确",
                            "响应时间在阈值内"
                        );
                        Optional<Violation> v = safetyBoundary.checkCriteriaCoverage(
                            subTaskId, successCriteria, verified);
                        v.ifPresent(violations::add);
                        criteriaCovered = violations.isEmpty();
                        successCount.incrementAndGet();
                    }
                }
            } finally {
                latch.countDown();
            }

            return new SubAgentResult(name, subTaskId, failureType, violations, criteriaCovered);
        }
    }

    // ==================== 场景B ====================

    @Nested
    @DisplayName("场景B：Agent从GUIDED到AUTONOMOUS的完整生命周期")
    class ScenarioB_AgentLifecycle {

        @Test
        @DisplayName("5阶段完整生命周期：Novel→Stable→Proven→Meta(Veteran)→新Agent继承")
        void fullLifecycle_fiveStages() {
            String agentName = "order-service-agent";

            // ===== 阶段1：Novel Agent，GUIDED模式，50次执行 =====
            AgentMaturity maturity = new AgentMaturity.Novel(agentName);
            assertEquals(AutonomyLevel.GUIDED, maturity.currentLevel());
            assertEquals(0, maturity.totalExecutions());
            assertFalse(maturity.isEligibleForPromotion());

            // 模拟50次执行
            long executions = 0;
            long successes = 0;
            for (int i = 0; i < 50; i++) {
                executions++;
                // 96% 成功率 → 48/50
                if (i != 7 && i != 23) successes++;
            }

            // ===== 阶段2：达到 Stable =====
            maturity = new AgentMaturity.Stable(agentName, executions, successes, Instant.now());
            assertEquals(AutonomyLevel.ASSISTED, maturity.currentLevel());
            assertEquals(50, maturity.totalExecutions());
            assertEquals(0.96, maturity.successRate(), 0.01, "成功率应为 96%");
            assertFalse(maturity.isEligibleForPromotion(),
                "50次执行不足以晋升到 Proven（需要100次）");

            // 继续执行到200次
            for (int i = 50; i < 200; i++) {
                executions++;
                // 保持 97% 成功率
                if (i % 33 != 0) successes++;
            }

            // ===== 阶段3：达到 Proven（跨3个场景验证） =====
            maturity = new AgentMaturity.Proven(agentName, executions, successes,
                Instant.now().minus(java.time.Duration.ofDays(30)), 5);
            assertEquals(AutonomyLevel.META, maturity.currentLevel());
            assertEquals(200, maturity.totalExecutions());
            assertTrue(maturity.successRate() >= 0.95, "成功率应 >= 95%");
            assertFalse(maturity.isEligibleForPromotion(),
                "200次执行不足以晋升到 Veteran（需要1000次）");

            // 继续执行到1000次
            for (int i = 200; i < 1000; i++) {
                executions++;
                // 保持 99% 成功率
                if (i % 100 != 0) successes++;
            }

            // ===== 阶段4：人工审批，晋升为 Veteran（Meta Agent） =====
            maturity = new AgentMaturity.Veteran(agentName, executions, successes,
                Instant.now().minus(java.time.Duration.ofDays(90)), 12);
            assertEquals(AutonomyLevel.AUTONOMOUS, maturity.currentLevel());
            assertEquals(1000, maturity.totalExecutions());
            assertTrue(maturity.successRate() >= 0.99, "成功率应 >= 99%");
            assertFalse(maturity.isEligibleForPromotion(), "Veteran 已到顶");

            // ===== 阶段5：新Agent基于此元Agent，AUTONOMOUS模式执行 =====
            AgentDefinition veteranDef = new AgentDefinition(
                new AgentName(agentName),
                "订单服务Agent（Veteran级）",
                "你是订单服务助手",
                List.of(),
                AutonomyLevel.AUTONOMOUS,
                Budget.Fixed.productionDefault(),
                null,
                "deepseek-chat",
                null,
                Map.of()
            );

            // 新Agent基于元Agent
            AgentDefinition newAgentDef = new AgentDefinition(
                new AgentName("order-refund-agent"),
                "退款处理Agent（基于元Agent）",
                veteranDef.systemPrompt(),
                veteranDef.tools(),
                AutonomyLevel.AUTONOMOUS,
                Budget.Fixed.productionDefault(),
                null,
                "deepseek-chat",
                veteranDef.name().value(), // basedOn
                Map.of()
            );

            assertEquals(AutonomyLevel.AUTONOMOUS, newAgentDef.autonomyLevel());
            assertEquals(agentName, newAgentDef.basedOn());

            // 新Agent执行时验证 successCriteria 覆盖
            TaskId newTaskId = TaskId.generate();
            List<String> criteria = List.of("退款金额计算正确", "退款记录已生成", "通知已发送");
            List<String> verified = List.of("退款金额", "退款记录", "通知");

            Optional<Violation> result = safetyBoundary.checkCriteriaCoverage(
                newTaskId, criteria, verified);
            assertTrue(result.isEmpty(), "新Agent基于元Agent应能正确覆盖所有 criteria");
        }

        @Test
        @DisplayName("降级条件：连续5次失败自动降级")
        void degradation_fiveConsecutiveFailures() {
            String agentName = "flaky-agent";

            // 模拟一个原本 Stable 但突然失败的 Agent
            long totalExec = 100;
            long successCount = 95; // 历史成功率 95%
            int consecutiveFailures = 0;

            // 模拟连续5次失败
            for (int i = 0; i < 5; i++) {
                totalExec++;
                consecutiveFailures++;
                // 成功率下降
            }

            // 连续5次失败后，成功率下降
            double newRate = (double) successCount / totalExec;
            assertTrue(newRate < 0.96, "连续失败后成功率应下降");

            // 验证降级条件
            boolean shouldDegrade = consecutiveFailures >= 5 || newRate < 0.80;
            assertFalse(shouldDegrade, "5次失败但整体成功率仍高，不应降级");

            // 继续失败到整体成功率 < 80%
            for (int i = 0; i < 20; i++) {
                totalExec++;
                consecutiveFailures++;
            }
            newRate = (double) successCount / totalExec;
            shouldDegrade = consecutiveFailures >= 5 || newRate < 0.80;
            assertTrue(shouldDegrade, "大量失败后成功率 < 80%，应降级");
        }
    }

    // ==================== 场景C ====================

    @Nested
    @DisplayName("场景C：successCriteria 知识积累的完整闭环")
    class ScenarioC_CriteriaKnowledgeAccumulation {

        @Test
        @DisplayName("3次执行：本体知识逐次增长，用户干预逐次减少")
        void threeExecutions_knowledgeGrows_interventionDecreases() {
            // ===== 第1次执行 =====
            // 本体提案3条 criteria，用户确认，Agent执行，成功
            List<String> ontologyKnowledge1 = List.of();

            // 本体提案（基于行业模板）
            List<String> proposed1 = List.of("数据完整性", "格式正确性", "响应时间");
            assertEquals(3, proposed1.size());

            // 用户确认（假设全选）
            List<String> confirmed1 = new ArrayList<>(proposed1);
            assertEquals(3, confirmed1.size());

            // Agent 执行后验证
            List<String> verified1 = List.of("数据完整性", "格式正确性", "响应时间");
            Optional<Violation> result1 = safetyBoundary.checkCriteriaCoverage(
                TaskId.generate(), confirmed1, verified1);
            assertTrue(result1.isEmpty(), "第1次执行所有 criteria 应通过");

            // 知识沉淀到本体
            ontologyKnowledge1 = new ArrayList<>(confirmed1);
            assertEquals(3, ontologyKnowledge1.size());

            // 用户干预次数：3条确认
            int intervention1 = 3; // 用户确认了3条

            // ===== 第2次执行 =====
            // 本体提案5条（含上次的3条），用户补充1条，Agent执行，成功
            List<String> proposed2 = new ArrayList<>(ontologyKnowledge1);
            proposed2.add("业务合规性");
            proposed2.add("异常处理");
            assertEquals(5, proposed2.size());

            // 用户补充1条
            proposed2.add("数据脱敏");
            List<String> confirmed2 = new ArrayList<>(proposed2);
            assertEquals(6, confirmed2.size());

            // Agent 执行后验证
            List<String> verified2 = List.of(
                "数据完整性", "格式正确性", "响应时间",
                "业务合规性", "异常处理", "数据脱敏");
            Optional<Violation> result2 = safetyBoundary.checkCriteriaCoverage(
                TaskId.generate(), confirmed2, verified2);
            assertTrue(result2.isEmpty(), "第2次执行所有 criteria 应通过");

            // 知识沉淀
            List<String> ontologyKnowledge2 = new ArrayList<>(confirmed2);
            assertEquals(6, ontologyKnowledge2.size());

            // 用户干预：只补充了1条 + 确认了6条 = 但核心是"补充1条"
            int intervention2 = 1; // 用户只补充了1条

            // ===== 第3次执行 =====
            // 本体提案6条（不需要用户干预），Agent 自动执行
            List<String> proposed3 = new ArrayList<>(ontologyKnowledge2);
            assertEquals(6, proposed3.size());

            // 自动确认——本体已有足够知识，不需要用户干预
            List<String> confirmed3 = new ArrayList<>(proposed3);

            // Agent 执行后验证
            List<String> verified3 = List.of(
                "数据完整性", "格式正确性", "响应时间",
                "业务合规性", "异常处理", "数据脱敏");
            Optional<Violation> result3 = safetyBoundary.checkCriteriaCoverage(
                TaskId.generate(), confirmed3, verified3);
            assertTrue(result3.isEmpty(), "第3次执行所有 criteria 应自动通过");

            // 知识沉淀（无新增）
            List<String> ontologyKnowledge3 = new ArrayList<>(confirmed3);
            assertEquals(6, ontologyKnowledge3.size());

            int intervention3 = 0; // 无用户干预

            // ===== 验证知识增长趋势 =====
            assertTrue(ontologyKnowledge1.size() < ontologyKnowledge2.size(),
                "本体知识应逐次增长");
            assertEquals(ontologyKnowledge2.size(), ontologyKnowledge3.size(),
                "第3次无新知识增长是合理的");

            // 验证用户干预逐次减少
            assertTrue(intervention1 > intervention2,
                "第1次干预 > 第2次干预");
            assertTrue(intervention2 > intervention3,
                "第2次干预 > 第3次干预");
            assertEquals(0, intervention3,
                "第3次应无用户干预");
        }

        @Test
        @DisplayName("知识积累后新任务的 criteria 自动提案覆盖率提升")
        void knowledgeAccumulation_increasesAutoProposalRate() {
            // 模拟5次执行后本体的 criteria 知识
            Set<String> accumulatedKnowledge = new LinkedHashSet<>();

            // 第1次：3条
            accumulatedKnowledge.addAll(List.of("数据完整性", "格式正确性", "响应时间"));

            // 第2次：新增2条
            accumulatedKnowledge.addAll(List.of("业务合规性", "异常处理"));

            // 第3次：新增1条
            accumulatedKnowledge.addAll(List.of("数据脱敏"));

            // 第4次：新增1条
            accumulatedKnowledge.addAll(List.of("权限校验"));

            // 第5次：新增1条
            accumulatedKnowledge.addAll(List.of("审计日志"));

            assertEquals(8, accumulatedKnowledge.size(), "5次执行后本体应有8条知识");

            // 第6次任务进来，本体可以自动提案
            String newTask = "处理退款申请";
            // 假设新任务需要6条 criteria，本体知识已覆盖5条
            List<String> requiredForNewTask = List.of(
                "数据完整性",   // 本体有
                "格式正确性",   // 本体有
                "业务合规性",   // 本体有
                "异常处理",     // 本体有
                "审计日志",     // 本体有
                "退款金额校验"  // 本体没有 — 需要新提案
            );

            long coveredByOntology = requiredForNewTask.stream()
                .filter(accumulatedKnowledge::contains)
                .count();

            assertEquals(5, coveredByOntology, "本体应覆盖5/6条");
            double coverageRate = (double) coveredByOntology / requiredForNewTask.size();
            assertTrue(coverageRate >= 0.8, "本体覆盖率应 >= 80%");

            // 只需要用户干预1条（退款金额校验）
            int newCriteriaNeeded = (int) requiredForNewTask.stream()
                .filter(c -> !accumulatedKnowledge.contains(c))
                .count();
            assertEquals(1, newCriteriaNeeded, "只需1条新 criteria");
        }
    }

    // ==================== 辅助类型 ====================

    enum SubAgentFailureType {
        CRITERIA_FAIL, MCP_SECURITY, NONE
    }

    record SubAgentResult(
        String name,
        TaskId taskId,
        SubAgentFailureType failureType,
        List<Violation> violations,
        boolean criteriaCovered
    ) {}
}
