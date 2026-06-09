package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.CriteriaVerificationResult;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;
import com.openjiuwen.runtime.criteria.model.VerifiedCriterion;

import java.util.List;
import java.util.Map;

/**
 * 完整交互流程示例——"质量分析Agent"场景。
 *
 * 场景：制造企业的开发者创建一个"产品质量分析Agent"，
 * 用于分析产品缺陷数据，给出改进建议。
 *
 * 展示完整闭环：
 *   提案 → 用户选择 → GoalSpec构建 → Agent执行（模拟） → 验证 → 知识沉淀
 */
public class QualityAnalysisAgentScenarioTest {

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  Openjiuwen SuccessCriteria 完整闭环示例");
        System.out.println("  场景：制造企业「产品质量分析Agent」");
        System.out.println("========================================\n");

        // ===== 初始化编排器 =====
        CriteriaOrchestrator orchestrator = new CriteriaOrchestrator();

        // ===== 步骤1: 三层提案 =====
        System.out.println("--- 步骤1: CriteriaProposer 三层提案 ---");
        String taskDescription = "分析本季度产品缺陷数据，识别主要缺陷模式，给出改进建议";
        List<CriteriaProposal> proposals = orchestrator.propose(taskDescription, Industry.MANUFACTURING);

        System.out.println("提案数量: " + proposals.size());
        for (int i = 0; i < proposals.size(); i++) {
            CriteriaProposal p = proposals.get(i);
            System.out.printf("  [%d] %-12s | %-8s | %s%n",
                i + 1, p.dimension(), p.source(), p.description());
        }
        System.out.println();

        // ===== 步骤2: 用户做选择题 =====
        System.out.println("--- 步骤2: 用户做选择题 ---");
        // 模拟用户选择：选中前4个（包括模板默认选中的 + LLM推理的1个）
        List<CriteriaProposal> selected = proposals.stream()
            .limit(4)
            .toList();

        System.out.println("用户选中了 " + selected.size() + " 条标准:");
        selected.forEach(p -> System.out.println("  [x] " + p.dimension()));
        System.out.println();

        // ===== 步骤3: 确认 + 构建 GoalSpec =====
        System.out.println("--- 步骤3: 确认 + 构建 GoalSpec ---");
        List<VerifiedCriterion> verified = orchestrator.confirm(selected);
        GoalSpec goal = orchestrator.buildGoalSpec(taskDescription, verified);

        System.out.println("GoalSpec.goal: " + goal.goal());
        System.out.println("GoalSpec.successCriteria:");
        goal.successCriteria().forEach(c -> System.out.println("  - " + c));
        System.out.println();

        // ===== 步骤4: Agent 执行（模拟） =====
        System.out.println("--- 步骤4: Agent 执行（模拟） ---");
        // 模拟 Agent 执行完成后的输出
        String agentOutput = """
            产品质量分析报告：

            1. 质量标准分析：本季度产品合格率为 97.3%，符合 ISO 9001 质量管理体系要求。

            2. 工艺参数范围：热处理工序温度偏差较大（+/- 15°C），超出安全操作窗口（+/- 10°C），
               建议校准温控系统。

            3. 追溯性：缺陷主要来自 3 号产线（批次 B2026-Q2-0315），
               根因追溯至冲压工序模具磨损。

            4. 安全操作：建议的模具更换操作已确认符合生产安全规程 GB/T 30871。
            """;
        String executionLog = "工具调用: query_defect_data() -> 1256条记录\n"
            + "工具调用: statistical_analysis() -> 完成\n"
            + "工具调用: query_equipment_health(产线3) -> 模具磨损率 82%";

        System.out.println("Agent 输出（摘要）:");
        System.out.println(agentOutput.substring(0, 100) + "...");
        System.out.println();

        // ===== 步骤5: 逐条验证 =====
        System.out.println("--- 步骤5: CriteriaVerifier 逐条验证 ---");
        List<CriteriaVerificationResult> results = orchestrator.verify(verified, agentOutput, executionLog);

        for (CriteriaVerificationResult r : results) {
            String status = r.isSatisfied() ? "PASS" : "FAIL";
            System.out.printf("  %-12s | %s | %s%n",
                r.criterion().dimension(), status, r.method());
        }
        System.out.println();

        // ===== 步骤6: 沉淀 =====
        System.out.println("--- 步骤6: KnowledgeAccumulator 知识沉淀 ---");
        orchestrator.accumulate(verified, results, Industry.MANUFACTURING);

        System.out.println("已沉淀 " + verified.size() + " 条知识");
        System.out.println("验证通过率: " + (orchestrator.isAllSatisfied(results) ? "100%" : "部分未通过"));

        // 查看沉淀后的知识
        var knowledge = orchestrator.accumulator().queryByIndustry(Industry.MANUFACTURING);
        System.out.println("当前知识库条目数: " + knowledge.size());
        knowledge.forEach(e ->
            System.out.printf("  %s | 使用%d次 | 成功率%.0f%% | 评分%.2f%n",
                e.dimension(), e.totalUsage(), e.successRate() * 100, e.compositeScore()));
        System.out.println();

        // ===== 步骤7: 知识维护 =====
        System.out.println("--- 步骤7: 知识维护 ---");
        orchestrator.maintain();
        System.out.println("维护完成（淘汰低质、合并重复）");

        // ===== 闭环验证：第二次任务会用到沉淀的知识 =====
        System.out.println("\n========================================");
        System.out.println("  第二次任务：验证知识复用");
        System.out.println("========================================\n");

        // 第二次同类型任务
        List<CriteriaProposal> proposals2 = orchestrator.propose(
            "分析下季度产品良率趋势，预测风险点", Industry.MANUFACTURING);
        System.out.println("第二次提案数量: " + proposals2.size());

        // 检查是否有来自本体（知识沉淀）的提案
        long ontologyCount = proposals2.stream()
            .filter(p -> p.source() == CriteriaProposal.Source.ONTOLOGY)
            .count();
        System.out.println("其中来自本体（知识沉淀）的提案: " + ontologyCount);

        System.out.println("\n========================================");
        System.out.println("  闭环验证完成");
        System.out.println("========================================");
    }
}
