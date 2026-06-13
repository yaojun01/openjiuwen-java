package com.openjiuwen.runtime.alpha.planner;

import com.openjiuwen.core.alpha.graph.TaskGraph;
import com.openjiuwen.core.alpha.graph.TaskNode;
import com.openjiuwen.core.alpha.graph.TaskNodeType;
import com.openjiuwen.core.alpha.model.PlanGoal;
import com.openjiuwen.core.alpha.model.PlanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlanValidator 占位符卫生检查测试（A层预防——脏图拦截闸门）。
 *
 * 背景：DeepResearchExample VERIFY_FAILED 案例——planner 把 ${} 内嵌进 task 正文，
 * resolveTemplate/resolveInputs 都不解析内嵌占位符，glm 原样 echo 出占位符空模板。
 * checkPlaceholderHygiene 是 A 层硬校验：description 禁 ${}，inputs 只允许整值 ${...} 引用。
 */
@DisplayName("PlanValidator: 占位符卫生检查")
class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();
    private final PlanGoal goal = PlanGoal.of("测试目标");

    @Test
    @DisplayName("description 含内嵌占位符 → 校验失败")
    void description_embeddedPlaceholder_fails() {
        TaskNode d = TaskNode.of("D", "维度3分析：${dim3}\n维度2分析：${dim2}", TaskNodeType.LLM_CALL);
        TaskGraph graph = new TaskGraph("脏图", List.of(d), List.of());

        PlanResult result = validator.validate(graph, goal, List.of());

        assertFalse(result.isValid());
        assertTrue(result.issues().stream()
            .anyMatch(i -> "UNRESOLVED_PLACEHOLDER_IN_DESCRIPTION".equals(i.code())));
    }

    @Test
    @DisplayName("inputs 整值引用 ${x.output} → 放行（约定引用语法）")
    void inputs_wholeReference_passes() {
        TaskNode d = TaskNode.of("D", "综合分析", TaskNodeType.LLM_CALL,
            Map.of("dim1", "${A.output}", "dim2", "${B.output}"));
        TaskGraph graph = new TaskGraph("干净图", List.of(d), List.of());

        PlanResult result = validator.validate(graph, goal, List.of());

        assertTrue(result.isValid());
        assertTrue(result.issues().stream()
            .noneMatch(i -> i.code() != null && i.code().startsWith("UNRESOLVED_PLACEHOLDER")));
    }

    @Test
    @DisplayName("inputs value 内嵌占位符（非整值）→ 校验失败")
    void inputs_embeddedPlaceholder_fails() {
        TaskNode d = TaskNode.of("D", "综合分析", TaskNodeType.LLM_CALL,
            Map.of("field", "维度3：${dim3} 的结果"));
        TaskGraph graph = new TaskGraph("脏图", List.of(d), List.of());

        PlanResult result = validator.validate(graph, goal, List.of());

        assertFalse(result.isValid());
        assertTrue(result.issues().stream()
            .anyMatch(i -> "UNRESOLVED_PLACEHOLDER_IN_INPUT".equals(i.code())));
    }

    @Test
    @DisplayName("干净节点（无占位符）→ 校验通过")
    void cleanNodes_pass() {
        TaskNode a = TaskNode.of("A", "查询订单", TaskNodeType.LLM_CALL);
        TaskNode b = TaskNode.of("B", "汇总分析", TaskNodeType.LLM_CALL);
        TaskGraph graph = new TaskGraph("干净图", List.of(a, b), List.of());

        PlanResult result = validator.validate(graph, goal, List.of());

        assertTrue(result.isValid());
    }
}
