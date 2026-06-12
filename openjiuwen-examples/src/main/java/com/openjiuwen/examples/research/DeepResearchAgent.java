package com.openjiuwen.examples.research;

import com.openjiuwen.core.dispatch.AutonomyLevel;
import com.openjiuwen.runtime.spring.Agent;

/**
 * 示例 1：Deep Research Agent — 对标 LangChain Deep Research / AgentScope deep_research。
 *
 * 范式：分解问题 → 多维度并行调研 → 综合报告（divergent → convergent）。
 *
 * 用 Alpha PEV 的 TaskGraph「扇出拓扑」实现：
 * <pre>
 *        [A: 问题分解 —— 产出 N 个互不重叠的研究维度]
 *            │           │           │
 *       [B1: 维度1]  [B2: 维度2]  [B3: 维度3]   ← 同层并行，互不依赖
 *            │           │           │
 *            └─────┬─────┴─────┬─────┘
 *                [C: 综合研究报告]            ← 多输入汇聚
 * </pre>
 *
 * 纯推理（无 @Tool），所有依据来自任务文本中自包含的背景资料，不依赖外部/mock 系统。
 */
@Agent(
    name = "deep-research-agent",
    description = "深度研究 Agent — 分解问题、多角度并行调研、综合成研究报告（纯推理，无外部工具）",
    autonomyLevel = AutonomyLevel.ASSISTED,
    systemPrompt = """
        你是深度研究分析师。你只靠阅读、推理和综合完成任务，没有任何外部工具或联网能力，
        所有依据都在用户给出的背景资料中。

        你的研究方法论是「先发散、后收敛」：
        1. 先把研究问题分解为若干（通常 3 个）互不重叠的分析维度，作为第一个子任务节点；
        2. 对每个维度独立展开调研分析——这些维度节点之间没有依赖，可以并行；
        3. 最后综合所有维度的发现，产出一份结构化研究报告（总体结论 + 关键发现 + 风险与建议）。

        规划子任务图时请遵守数据流约定：
        - 每个维度调研节点在 inputs 中引用分解节点的输出，写作 ${分解节点id.output}；
        - 综合报告节点在 inputs 中引用所有维度调研节点的输出，各写作 ${维度节点id.output}。
        """
)
public class DeepResearchAgent {
    // 纯推理：不定义任何 @Tool。背景资料内嵌在任务文本中。
}
