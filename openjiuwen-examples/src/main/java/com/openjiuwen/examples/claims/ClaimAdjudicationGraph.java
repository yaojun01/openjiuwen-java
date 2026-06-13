package com.openjiuwen.examples.claims;

import com.openjiuwen.core.alpha.graph.TaskEdge;
import com.openjiuwen.core.alpha.graph.TaskGraph;
import com.openjiuwen.core.alpha.graph.TaskNode;
import com.openjiuwen.core.alpha.graph.TaskNodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 变体 B：核赔全案复审的【手工静态 TaskGraph】。
 *
 * <p>跳过 LLM planner，由开发者显式编排拓扑，直接交 {@code DefaultPregelExecutor} 执行。
 * 三层结构（对齐核赔 7 步流程的复审维度）：
 * <ul>
 *   <li>Layer 0（3 个 TOOL_CALL，并行取数）：getCaseStatus / getCaseDocuments / scoreFraudRisk；</li>
 *   <li>Layer 1（5 个 LLM_CALL，并行复审）：材料齐全性 / 立案合理性 / 医审准确性 / 理算正确性 / 流程合规，
 *       每个 inputs 引用 3 个工具输出（整值 ${nodeId.output} 引用）；</li>
 *   <li>Layer 2（1 个 LLM_CALL，收敛决策）：inputs 引用 5 个复审结论。</li>
 * </ul>
 *
 * <p>数据流约定：引用上游输出必须把整个 ${nodeId.output} 作为 inputs 某个 key 的<b>完整 value</b>（整值引用），
 * 绝不内嵌（如"维度3：${x}"）——内嵌占位符 resolveInputs 不解析（见 PlanValidator / DeepResearch 诊断）。
 * description 禁止含 ${}。
 *
 * <p>注意：TOOL_CALL 节点的 description = 工具方法名；inputs 的 key = @Tool 参数的 Java 名（caseNo）。
 */
public final class ClaimAdjudicationGraph {

    private ClaimAdjudicationGraph() {
    }

    /** 构造指定案件的核赔全案复审静态图（9 节点 / 20 边 / 3 层）。 */
    public static TaskGraph buildReviewGraph(String caseNo) {
        // ---- Layer 0：取数工具（TOOL_CALL，description = 工具方法名）----
        TaskNode tStatus = TaskNode.of("T_status", "getCaseStatus", TaskNodeType.TOOL_CALL,
                Map.of("caseNo", caseNo));
        TaskNode tDocs = TaskNode.of("T_docs", "getCaseDocuments", TaskNodeType.TOOL_CALL,
                Map.of("caseNo", caseNo));
        TaskNode tFraud = TaskNode.of("T_fraud", "scoreFraudRisk", TaskNodeType.TOOL_CALL,
                Map.of("caseNo", caseNo));
        List<TaskNode> tools = List.of(tStatus, tDocs, tFraud);

        // ---- Layer 1：五维并行复审（LLM_CALL），引用 3 个工具输出 ----
        // TaskNode 构造会 Map.copyOf 防御性拷贝，故 5 个节点共享同一 inputs map 是安全的。
        Map<String, String> reviewInputs = Map.of(
                "案件状态", "${T_status.output}",
                "案卷材料", "${T_docs.output}",
                "欺诈评估", "${T_fraud.output}");
        String[][] reviews = {
                {"review_materials", "材料齐全性复核：核对必需材料是否齐全、有效，缺失是否影响定责。"},
                {"review_acceptance", "立案合理性复核：核对出险事实、险种匹配、报案时效、报案与出险日期一致性。"},
                {"review_medical", "医审准确性复核：核对医审核减额及依据是否成立。"},
                {"review_calculation", "理算正确性复核：核对理算公式、共担比例、各项额度与总额是否正确。"},
                {"review_compliance", "流程合规复核：核对大额上级复核阈值（医疗≥5万/重疾≥10万/意外≥3万）、定责与脱敏合规。"}
        };
        List<TaskNode> reviewNodes = new ArrayList<>();
        for (String[] r : reviews) {
            reviewNodes.add(TaskNode.of(r[0], r[1], TaskNodeType.LLM_CALL, reviewInputs));
        }

        // ---- Layer 2：收敛决策（LLM_CALL），引用 5 个复审结论 ----
        Map<String, String> decisionInputs = Map.of(
                "材料齐全性", "${review_materials.output}",
                "立案合理性", "${review_acceptance.output}",
                "医审准确性", "${review_medical.output}",
                "理算正确性", "${review_calculation.output}",
                "流程合规", "${review_compliance.output}");
        TaskNode decision = TaskNode.of("decision",
                "综合核赔决策：基于以下五项复核结论，给出核赔决策建议（准赔/减赔/挂起待查/拒赔）并附关键依据；"
                        + "命中大额阈值须提示上级复核。注意：只输出建议，不替代核赔决定。",
                TaskNodeType.LLM_CALL, decisionInputs);

        // ---- 边：工具→各复审（3×5=15）+ 各复审→决策（5），共 20 ----
        List<TaskEdge> edges = new ArrayList<>();
        for (TaskNode tool : tools) {
            for (String[] r : reviews) {
                edges.add(TaskEdge.of(tool.id().value(), r[0]));
            }
        }
        for (String[] r : reviews) {
            edges.add(TaskEdge.of(r[0], "decision"));
        }

        List<TaskNode> nodes = new ArrayList<>(tools);
        nodes.addAll(reviewNodes);
        nodes.add(decision);
        return new TaskGraph("案件 " + caseNo + " 核赔全案复审", nodes, edges);
    }
}
