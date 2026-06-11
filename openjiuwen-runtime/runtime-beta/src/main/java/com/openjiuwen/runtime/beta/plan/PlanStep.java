package com.openjiuwen.runtime.beta.plan;

/**
 * 计划中的一个步骤。
 *
 * 与 Alpha 的 TaskNode 不同：没有类型（TOOL_CALL/LLM_CALL），
 * 没有 inputs/expectedOutput，没有 ${nodeId.output} 引用。
 * 步骤只是一个描述，由 LLM 自主决定如何完成。
 *
 * @param index       0-based 在列表中的位置
 * @param description 步骤描述（简短的动词短语）
 * @param status      PENDING / DONE / SKIPPED
 */
public record PlanStep(int index, String description, StepStatus status) {

    public PlanStep {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
    }

    public enum StepStatus { PENDING, DONE, SKIPPED }

    public PlanStep withStatus(StepStatus newStatus) {
        return new PlanStep(index, description, newStatus);
    }
}
