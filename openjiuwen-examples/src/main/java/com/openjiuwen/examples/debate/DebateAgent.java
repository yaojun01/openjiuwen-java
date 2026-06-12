package com.openjiuwen.examples.debate;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;

/**
 * 示例 3：多角色辩论 Agent — 对标 AgentScope conversational_agents/multiagent_debate。
 *
 * 范式：正反立论 → 互相反驳 → 主持人仲裁收敛。
 *
 * 用 Alpha PEV 的「对抗轮次拓扑」实现（菱形 DAG）：
 * <pre>
 *   [A: 正方立论]   [B: 反方立论]            ← 立论层（同层并行）
 *        │   ╲     ╱   │
 *        │    ╲   ╱    │
 *   [C: 正方反驳]  [D: 反方反驳]            ← 反驳层（同层并行，各自读双方立论）
 *        │   ╲     ╱   │
 *        └─────╳───────┘
 *            [E: 主持人仲裁]                ← 综合裁决
 * </pre>
 *
 * <p><b>诚实标注的局限</b>：openjiuwen 当前用「单 Agent 内多角色节点」模拟辩论，
 * 而非真正多 @Agent 编排。因为：① 共享同一 systemPrompt，正反方是角色扮演、对抗力度
 * 弱于独立 agent；② 节点间只见上游的 output 文本，看不见彼此的完整推理链；③ 静态图
 * 不支持 N&gt;2 轮动态乒乓；④ 真正的多 Agent 对抗需要 Beta SubAgent（当前 P0 未完成）。
 * 待 Beta 成熟后可升级为真·多 Agent 版本。
 *
 * 纯推理（无 @Tool），辩题自包含在任务文本中。
 */
@Agent(
    name = "debate-agent",
    description = "结构化辩论 Agent — 正反立论、互相反驳、主持人仲裁（纯推理，多角色节点模拟）",
    autonomyLevel = AutonomyLevel.ASSISTED,
    systemPrompt = """
        你是一场结构化辩论的导演，也是其中的各方辩手与主持人。你没有外部工具，
        所有辩论基于辩题本身与逻辑推理。

        请把辩论构建为「立论 → 反驳 → 仲裁」三轮的子任务图：
        1. 立论层（2 个并行节点）：
           - 正方节点：从「支持」立场提出 2-3 个核心论点及论据；
           - 反方节点：从「反对」立场提出 2-3 个核心论点及论据。
        2. 反驳层（2 个并行节点）：
           - 正方反驳节点：针对反方立论逐条反驳，需同时引用正方立论与反方立论；
           - 反方反驳节点：针对正方立论逐条反驳，需同时引用反方立论与正方立论。
        3. 仲裁节点（1 个）：
           - 主持人综合双方立论与反驳，给出中立裁决、判定胜负倾向，并点明关键分歧。

        数据流约定：节点 inputs 引用上游输出写作 ${节点id.output}（例如正方反驳节点
        inputs 中写 pro=${正方立论id.output}, con=${反方立论id.output}）。
        """
)
public class DebateAgent {
    // 纯推理：不定义任何 @Tool。各方立场由节点描述指定。
}
