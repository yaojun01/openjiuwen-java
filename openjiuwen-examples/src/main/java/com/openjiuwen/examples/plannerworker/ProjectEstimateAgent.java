package com.openjiuwen.examples.plannerworker;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;
import com.openjiuwen.runtime.spring.Param;
import com.openjiuwen.runtime.spring.Tool;

/**
 * 示例 2：Planner-Worker Agent — 对标 AgentScope alias Planner-Worker 模式。
 *
 * 范式：规划分解 → Worker 调真实工具执行子任务 → 汇总。
 *
 * 用 Alpha PEV 的「工具链拓扑」实现，并 <b>首次端到端验证 Alpha 的 TOOL_CALL 执行路径</b>：
 * <pre>
 *   [A: 任务分解（LLM_CALL）—— 列出子任务并评定复杂度]
 *        │
 *   [B1: estimateHours(...)] [B2: estimateHours(...)] ...   ← TOOL_CALL 并行
 *        │
 *   [C: 成本汇总（LLM_CALL）—— 读所有 B*.output，按费率算总成本]
 * </pre>
 *
 * <p>本示例依赖两项前置修复（见 plan）：
 * <ol>
 *   <li>根 pom 补 {@code -parameters}——否则 @Tool 参数名反射为 arg0/arg1，注入失败；
 *   <li>{@link com.openjiuwen.runtime.spring.AgentClient#invokeStream(String, java.util.Map)}
 *       重载——用于把 availableTools 传入 planner prompt。
 * </ol>
 *
 * <p>汇总节点 C 故意用 LLM_CALL 而非再加 computeCost @Tool，规避「多 String 参数 +
 * 上游 Object 输出」的注入难题（见 plan 风险章节）。
 */
@Agent(
    name = "project-estimate-agent",
    description = "项目工时与成本估算 Agent — Planner-Worker 模式，分解子任务后用 estimateHours 工具估算工时",
    autonomyLevel = AutonomyLevel.ASSISTED,
    systemPrompt = """
        你是项目经理 Agent，负责软件项目的工时与成本估算。你有一个可用工具：
        - estimateHours(task, complexity)：估算单个子任务的工时（人天）。

        你的工作方式是 Planner-Worker：
        1. 规划：把项目分解为若干具体子任务，并为每个子任务评定复杂度（1-5 整数）；
        2. 执行：对每个子任务分别调用 estimateHours 工具获取工时；
        3. 汇总：综合所有子任务工时，按给定费率计算总成本，输出成本汇总表。

        规划子任务图时注意：
        - 每个调用工具的节点 type 为 TOOL_CALL，其 description 就是工具名 estimateHours，
          inputs 为该子任务的具体字面量参数：task=子任务描述, complexity=复杂度数字；
        - 工具节点之间互不依赖，可同层并行；
        - 最后一个汇总节点为 LLM_CALL，inputs 引用所有工具节点的输出（各 ${工具节点id.output}）。
        """
)
public class ProjectEstimateAgent {

    /**
     * 工时估算表：复杂度 1..5 → 基础人天数。
     * 真实确定性计算（非 mock），结果可复现、可验证。
     */
    private static final double[] DAYS_BY_COMPLEXITY = {2, 4, 7, 12, 20};

    @Tool("估算单个软件子任务的工时（人天）。复杂度 1-5：1=简单(2天) 2=较易(4天) 3=中等(7天) 4=较难(12天) 5=极复杂(20天)")
    public String estimateHours(
            @Param("任务描述") String task,
            @Param("复杂度1-5") String complexity) {
        double parsed;
        try {
            parsed = Double.parseDouble(complexity.trim());
        } catch (NumberFormatException e) {
            parsed = 3; // 无法解析时按中等
        }
        int level = (int) Math.max(1, Math.min(5, Math.round(parsed)));
        double days = DAYS_BY_COMPLEXITY[level - 1];
        String safeTask = task == null ? "" : task.replace("\"", "'").replace("\n", " ").trim();
        return "{\"task\": \"" + safeTask + "\", \"complexity\": " + level
            + ", \"estimatedDays\": " + days + "}";
    }
}
