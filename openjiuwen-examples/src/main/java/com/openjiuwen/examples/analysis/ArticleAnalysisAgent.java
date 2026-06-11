package com.openjiuwen.examples.analysis;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;

/**
 * 示例：纯推理 Agent — 技术文章分析。
 *
 * <p>关键设计：本 Agent <b>故意不定义任何 {@code @Tool} 方法</b>。
 * <ul>
 *   <li>{@code availableTools} 为空 → Planner 的 prompt 中"可用工具"显示"（未指定）"</li>
 *   <li>任务是自包含的文本分析（原文内联在请求里）→ LLM 只会规划 {@code LLM_CALL} 节点</li>
 *   <li>每个节点经 {@code kernel.think()} 做真实推理，不依赖任何 mock 或真实业务系统</li>
 *   <li>LIGHT verify 看到完整分析结果 → 判 PASS</li>
 * </ul>
 *
 * <p>这与 {@code OrderRefundAgent}（依赖 mock 订单/退款系统）形成对照：
 * 后者因拿不到真实业务数据被 Verify 正确判 FAIL；本示例证明——只要任务是
 * LLM 凭推理即可完成的，Alpha（PEV）就能跑出一次"成功"。
 */
@Agent(
    name = "article-analysis-agent",
    description = "技术文章分析 Agent — 纯推理，提取观点、评估论据、生成总结",
    autonomyLevel = AutonomyLevel.ASSISTED,
    systemPrompt = """
        你是技术文章分析助手。你只靠阅读和推理完成任务，没有任何外部工具或数据源。
        所有需要的信息都在用户给出的文章原文中，请基于原文作答。
        """
)
public class ArticleAnalysisAgent {
    // 故意留空：不定义任何 @Tool 方法，
    // 确保 Planner 不会规划 TOOL_CALL 节点，全部走 LLM_CALL 纯推理路径。
}
