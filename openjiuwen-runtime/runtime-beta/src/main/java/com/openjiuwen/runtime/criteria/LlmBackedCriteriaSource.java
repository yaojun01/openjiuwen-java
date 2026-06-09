package com.openjiuwen.runtime.criteria;

import com.openjiuwen.runtime.criteria.model.CriteriaProposal;
import com.openjiuwen.runtime.criteria.model.StructuredCriteria.Industry;

import java.util.List;

/**
 * 基于 LLM 的成功标准推理源。
 *
 * 调用 AgentKernel.think() 让 LLM 根据任务描述动态生成成功标准。
 * 返回结构化的 LlmInferredProposal 列表。
 *
 * 注意：实际 LLM 调用需要 AgentKernel 实例，此处提供接口和占位实现。
 * 生产实现需注入 AgentKernel，解析 LLM 返回的 JSON 格式标准列表。
 */
public class LlmBackedCriteriaSource implements LlmCriteriaSource {

    // 生产实现需要注入 AgentKernel
    // private final AgentKernel kernel;

    public LlmBackedCriteriaSource() {
        // this.kernel = kernel;
    }

    @Override
    public List<CriteriaProposal> infer(String taskDescription, Industry industry) {
        /*
         * 生产实现伪代码：
         *
         * String prompt = buildInferencePrompt(taskDescription, industry);
         * String response = kernel.think(prompt, budgetLimits).block();
         * return parseLlmResponse(response);
         *
         * prompt 模板：
         *   "你是一个 {industry.label()} 行业的质量分析专家。
         *    请根据以下任务描述，建议 3-5 条成功标准。
         *    每条标准包含：维度名称、描述、置信度(0-1)。
         *    以 JSON 格式返回。
         *
         *    任务：{taskDescription}"
         */

        // 占位实现：返回空列表，由模板和本体覆盖
        return List.of();
    }
}
