package com.openjiuwen.runtime.beta;

import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.beta.reflection.DefaultGoalAlignmentCheck;
import com.openjiuwen.runtime.beta.reflection.DefaultReplanFeasibilityCheck;
import com.openjiuwen.runtime.beta.reflection.GoalAlignmentCheck;
import com.openjiuwen.runtime.beta.reflection.ReplanFeasibilityCheck;
import com.openjiuwen.core.kernel.model.Budget;
import com.openjiuwen.core.kernel.model.BudgetLimits;
import com.openjiuwen.core.kernel.model.ToolName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 反思组件测试——DefaultGoalAlignmentCheck 和 DefaultReplanFeasibilityCheck。
 */
@DisplayName("反思组件测试")
class ReflectionComponentTest {

    // ==================== GoalAlignmentCheck ====================

    @Nested
    @DisplayName("DefaultGoalAlignmentCheck: 目标漂移检测")
    class GoalAlignmentCheckTest {

        private DefaultGoalAlignmentCheck check;

        @BeforeEach
        void setUp() {
            check = new DefaultGoalAlignmentCheck(5);
        }

        @Test
        @DisplayName("非检查间隔时跳过，返回满分")
        void skipOnNonCheckInterval() {
            var result = check.checkAlignment(null, "处理订单", List.of(), 3);
            assertEquals(1.0, result.score(), "非检查间隔应返回满分");
        }

        @Test
        @DisplayName("步数 0 时（检查间隔）执行检查")
        void checkAtStepZero() {
            var result = check.checkAlignment(null, "处理订单", List.of(), 0);
            // 步数 0 % 5 == 0，会执行检查，但主 Agent 步数 <= 15 时跳过
            assertEquals(1.0, result.score(), "主 Agent 步数未超阈值应跳过");
        }

        @Test
        @DisplayName("主 Agent 步数 > 15 且对齐时返回高分")
        void mainAgentHighScore() {
            // 关键词提取：目标按空格/标点分词，"处理 订单 退款" → keywords={"处理","订单","退款"}
            // 所以目标需要包含空格或标点才能正确分词
            LLMDecision.CallTool toolCall = new LLMDecision.CallTool(
                new ToolName("query_order"), Map.of(), "查询 订单 状态");
            LLMDecision.ContinueThinking thought = new LLMDecision.ContinueThinking(
                "分析 订单 数据，准备 处理 退款", "");

            var result = check.checkAlignment(null, "处理 订单 退款",
                List.of(toolCall, thought, toolCall, thought, toolCall), 20);

            assertTrue(result.score() > 0.5,
                "对齐的决策历史应返回高分，实际=" + result.score());
        }

        @Test
        @DisplayName("严重偏离时 needsIntervention=true 且有 injection")
        void severeDrift_needsIntervention() {
            LLMDecision.CallTool unrelatedCall = new LLMDecision.CallTool(
                new ToolName("browse_news"), Map.of(), "浏览 新闻 资讯");
            LLMDecision.ContinueThinking unrelatedThought = new LLMDecision.ContinueThinking(
                "今天 天气 不错，适合 出游", "");

            var result = check.checkAlignment(null, "处理 订单 退款",
                List.of(unrelatedCall, unrelatedThought, unrelatedCall, unrelatedThought, unrelatedCall),
                20);

            assertTrue(result.needsIntervention(),
                "严重偏离应需要干预，score=" + result.score());
            assertNotNull(result.injection(),
                "严重偏离应有注入提示");
        }

        @Test
        @DisplayName("子 Agent 检查不受步数阈值限制")
        void subAgentCheckedRegardlessOfStep() {
            LLMDecision.CallTool alignedCall = new LLMDecision.CallTool(
                new ToolName("process_refund"), Map.of(), "处理退款");

            var result = check.checkAlignment("父目标：处理退款",
                "处理退款", List.of(alignedCall), 0);

            // 步数 0 % 5 == 0，子 Agent 有 parentGoal，会执行检查
            assertTrue(result.score() > 0.5,
                "子 Agent 对齐的调用应返回高分");
        }

        @Test
        @DisplayName("大量 Replan 降低稳定性得分")
        void excessiveReplanLowersScore() {
            // 加不相关的 tool/thought 来拉低综合分
            List<LLMDecision> history = List.of(
                new LLMDecision.CallTool(new ToolName("browse"), Map.of(), "浏览 新闻"),
                new LLMDecision.ContinueThinking("思考 天气 情况", ""),
                new LLMDecision.Replan("策略A不行", "换策略B", ""),
                new LLMDecision.Replan("策略B不行", "换策略C", ""),
                new LLMDecision.Replan("策略C不行", "换策略D", ""),
                new LLMDecision.Replan("策略D不行", "换策略E", ""),
                new LLMDecision.Replan("策略E不行", "换策略F", "")
            );

            var result = check.checkAlignment(null, "处理 订单 退款", history, 20);
            assertTrue(result.score() < 0.6,
                "不相关操作 + 5次 replan 应导致低分，score=" + result.score());
        }
    }

    // ==================== ReplanFeasibilityCheck ====================

    @Nested
    @DisplayName("DefaultReplanFeasibilityCheck: 重规划可行性")
    class ReplanFeasibilityCheckTest {

        private DefaultReplanFeasibilityCheck check;

        @BeforeEach
        void setUp() {
            check = new DefaultReplanFeasibilityCheck();
        }

        @Test
        @DisplayName("新策略与上次高度相似时不可行")
        void duplicateApproach_notFeasible() {
            GoalSpec goal = GoalSpec.of("任务", List.of(), 5);
            // 先记录一次 replan
            goal = goal.withReplan(new GoalSpec.ReplanRecord(5, "原因", "用工具A查询数据库"));

            LLMDecision.Replan replan = new LLMDecision.Replan(
                "策略无效", "用工具A查询数据库", "换个思路");

            var result = check.assess(replan, goal, List.of(),
                BudgetLimits.start(Budget.Fixed.productionDefault()));

            assertFalse(result.feasible(), "高度相似的策略应不可行");
            assertTrue(result.reason().contains("相似"),
                "原因应提到相似性");
        }

        @Test
        @DisplayName("全新的策略在资源充足时可行")
        void newApproach_feasible() {
            GoalSpec goal = GoalSpec.of("任务", List.of(), 5);
            goal = goal.withReplan(new GoalSpec.ReplanRecord(5, "原因", "旧策略"));

            LLMDecision.Replan replan = new LLMDecision.Replan(
                "完全不同的新策略描述", "改用全新的方法", "全新思路");

            var result = check.assess(replan, goal, List.of(),
                BudgetLimits.start(Budget.Fixed.productionDefault()));

            assertTrue(result.feasible(), "全新的策略应可行");
        }

        @Test
        @DisplayName("超限时返回 exceededAction")
        void exceededLimit_returnsAction() {
            GoalSpec goal = GoalSpec.of("任务", List.of(), 1);
            goal = goal.withReplan(new GoalSpec.ReplanRecord(5, "原因", "旧策略"));

            LLMDecision.Replan replan = new LLMDecision.Replan(
                "新原因", "新策略", "");

            var result = check.assess(replan, goal, List.of(),
                BudgetLimits.start(Budget.Fixed.productionDefault()));

            assertFalse(result.feasible(), "超限应不可行");
            assertNotNull(result.exceededAction(),
                "超限应返回处理策略");
        }

        @Test
        @DisplayName("资源不足时不可行")
        void resourceDepleted_notFeasible() {
            GoalSpec goal = GoalSpec.of("任务", List.of(), 5);

            // 消耗大量预算
            BudgetLimits lowBudget = BudgetLimits.start(new Budget.Fixed(100, 10, 1000L, 60000L));
            for (int i = 0; i < 9; i++) {
                lowBudget = lowBudget.recordLLMCall(100);
            }

            LLMDecision.Replan replan = new LLMDecision.Replan(
                "需要重规划", "全新的策略描述", "");

            var result = check.assess(replan, goal, List.of(), lowBudget);

            assertFalse(result.feasible(), "资源不足应不可行");
        }
    }
}
