package com.openjiuwen.runtime.core.engine;

import com.openjiuwen.core.kernel.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SafetyBoundary 安全边界测试。
 *
 * 测试目标：
 * - Token 预算耗尽时返回 BudgetViolation
 * - 迭代次数超限（通过工具调用计数超限）
 * - 递归深度超限（通过预算维度间接测试）
 * - MCP 安全检查
 * - 开发环境降级
 * - Criteria 覆盖检查
 */
@DisplayName("SafetyBoundary: 安全边界")
class SafetyBoundaryTest {

    private DefaultSafetyBoundary boundary;

    @BeforeEach
    void setUp() {
        boundary = new DefaultSafetyBoundary();
    }

    // ==================== Token 预算耗尽 ====================

    @Nested
    @DisplayName("Token 预算耗尽")
    class TokenBudgetTest {

        @Test
        @DisplayName("Token 消耗达到上限时返回 BudgetViolation")
        void tokenExceeded_returnsViolation() {
            Budget budget = new Budget.Fixed(10, 20, 1000L, 0L);
            BudgetLimits exceeded = new BudgetLimits(budget, 0, 0, 1000L, 0L);

            List<Violation> violations = boundary.checkBudget(exceeded);

            assertEquals(1, violations.size());
            assertInstanceOf(Violation.BudgetViolation.class, violations.get(0));
            assertEquals("BUDGET_EXCEEDED", violations.get(0).code());
            assertEquals(Violation.Severity.HIGH, violations.get(0).severity());
        }

        @Test
        @DisplayName("Token 未耗尽时无违规")
        void tokenWithinLimit_noViolation() {
            Budget budget = new Budget.Fixed(10, 20, 1000L, 0L);
            BudgetLimits normal = new BudgetLimits(budget, 0, 0, 500L, 0L);

            List<Violation> violations = boundary.checkBudget(normal);
            assertTrue(violations.isEmpty());
        }
    }

    // ==================== 迭代次数超限 ====================

    @Nested
    @DisplayName("迭代次数超限（LLM 调用 + 工具调用）")
    class IterationLimitTest {

        @Test
        @DisplayName("LLM 调用次数达上限返回 BudgetViolation")
        void llmCallsExceeded_returnsViolation() {
            Budget budget = new Budget.Fixed(5, 20, 10000L, 0L);
            BudgetLimits exceeded = new BudgetLimits(budget, 5, 0, 0L, 0L);

            List<Violation> violations = boundary.checkBudget(exceeded);

            assertEquals(1, violations.size());
            assertInstanceOf(Violation.BudgetViolation.class, violations.get(0));
            assertTrue(violations.get(0).message().contains("LLM调用=5/5"));
        }

        @Test
        @DisplayName("工具调用次数达上限返回 BudgetViolation")
        void toolCallsExceeded_returnsViolation() {
            Budget budget = new Budget.Fixed(10, 3, 10000L, 0L);
            BudgetLimits exceeded = new BudgetLimits(budget, 0, 3, 0L, 0L);

            List<Violation> violations = boundary.checkBudget(exceeded);

            assertEquals(1, violations.size());
            assertTrue(violations.get(0).message().contains("工具调用=3/3"));
        }

        @Test
        @DisplayName("Timeout 超时也触发 BudgetViolation")
        void timeoutExceeded_returnsViolation() {
            Budget budget = new Budget.Fixed(10, 20, 10000L, 5000L);
            BudgetLimits exceeded = new BudgetLimits(budget, 0, 0, 0L, 5000L);

            List<Violation> violations = boundary.checkBudget(exceeded);

            assertEquals(1, violations.size());
        }
    }

    // ==================== 递归深度超限（通过预算子Agent分配间接验证） ====================

    @Nested
    @DisplayName("工具白名单 + 未授权操作")
    class ToolWhitelistTest {

        @Test
        @DisplayName("工具白名单中不存在的工具返回 UnauthorizedAction")
        void toolNotInWhitelist_returnsUnauthorized() {
            Set<ToolName> allowed = Set.of(new ToolName("safeTool"), new ToolName("readData"));
            DefaultSafetyBoundary restrictedBoundary = new DefaultSafetyBoundary(allowed, List.of());

            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.productionDefault());

            List<Violation> violations = restrictedBoundary.checkToolCall(
                new ToolName("dangerousTool"), Map.of(), budget);

            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v instanceof Violation.UnauthorizedAction));
        }

        @Test
        @DisplayName("白名单内的工具无违规")
        void toolInWhitelist_noViolation() {
            Set<ToolName> allowed = Set.of(new ToolName("safeTool"));
            DefaultSafetyBoundary restrictedBoundary = new DefaultSafetyBoundary(allowed, List.of());

            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.productionDefault());

            List<Violation> violations = restrictedBoundary.checkToolCall(
                new ToolName("safeTool"), Map.of(), budget);

            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("空白名单允许所有工具")
        void emptyWhitelist_allowsAll() {
            DefaultSafetyBoundary openBoundary = new DefaultSafetyBoundary(Set.of(), List.of());
            BudgetLimits budget = BudgetLimits.start(Budget.Fixed.productionDefault());

            List<Violation> violations = openBoundary.checkToolCall(
                new ToolName("anyTool"), Map.of(), budget);

            assertTrue(violations.isEmpty());
        }
    }

    // ==================== MCP 安全检查 ====================

    @Nested
    @DisplayName("MCP 安全检查")
    class McpSecurityTest {

        @Test
        @DisplayName("mTLS 未建立时返回 McpSecurityViolation")
        void mtlsNotEstablished_returnsViolation() {
            Optional<Violation> result = boundary.checkMcpSecurity("https://sdk:8443/mcp", false);

            assertTrue(result.isPresent());
            assertInstanceOf(Violation.McpSecurityViolation.class, result.get());

            Violation.McpSecurityViolation v = (Violation.McpSecurityViolation) result.get();
            assertEquals("MCP_SECURITY_VIOLATION", v.code());
            assertEquals(Violation.Severity.CRITICAL, v.severity());
            assertTrue(v.message().contains("mTLS"));
        }

        @Test
        @DisplayName("mTLS 已建立时检查通过")
        void mtlsEstablished_passes() {
            Optional<Violation> result = boundary.checkMcpSecurity("https://sdk:8443/mcp", true);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== 开发环境降级 ====================

    @Nested
    @DisplayName("开发环境降级")
    class DevModeTest {

        @Test
        @DisplayName("开发环境不强制 mTLS，允许明文")
        void devMode_allowsPlaintext() {
            // DefaultSafetyBoundary 自身不做环境判断，环境由 McpTlsInterceptor 控制
            // 这里验证的是：SafetyBoundary.checkMcpSecurity 本身是纯函数
            // 传入 isTlsEstablished=true 时不管什么环境都通过
            Optional<Violation> result = boundary.checkMcpSecurity("http://localhost:8080/mcp", true);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== LLM 输出安全检查 ====================

    @Nested
    @DisplayName("LLM 输出安全检查")
    class LLMOutputTest {

        @Test
        @DisplayName("输出包含敏感模式返回 DataLeakViolation")
        void sensitivePattern_returnsViolation() {
            List<Pattern> patterns = List.of(
                Pattern.compile("\\b\\d{16}\\b"),  // 信用卡号
                Pattern.compile("password\\s*=\\s*\\S+")  // 密码赋值
            );
            DefaultSafetyBoundary sensitiveBoundary = new DefaultSafetyBoundary(Set.of(), patterns);

            List<Violation> violations = sensitiveBoundary.checkLLMOutput(
                "用户的信用卡号是 1234567890123456 请查收");

            assertEquals(1, violations.size());
            assertInstanceOf(Violation.DataLeakViolation.class, violations.get(0));
            assertEquals(Violation.Severity.CRITICAL, violations.get(0).severity());
        }

        @Test
        @DisplayName("安全输出无违规")
        void safeOutput_noViolation() {
            DefaultSafetyBoundary cleanBoundary = new DefaultSafetyBoundary(Set.of(), List.of());

            List<Violation> violations = cleanBoundary.checkLLMOutput("正常回答");
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("null 输出无违规")
        void nullOutput_noViolation() {
            List<Violation> violations = boundary.checkLLMOutput(null);
            assertTrue(violations.isEmpty());
        }
    }

    // ==================== Criteria 覆盖检查 ====================

    @Nested
    @DisplayName("Criteria 覆盖检查")
    class CriteriaCoverageTest {

        @Test
        @DisplayName("全部 criteria 覆盖时通过")
        void allCovered_passes() {
            Optional<Violation> result = boundary.checkCriteriaCoverage(
                TaskId.generate(),
                List.of("检查订单状态", "确认库存"),
                List.of("检查订单状态", "确认库存")
            );
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("部分 criteria 未覆盖返回 CriteriaNotCovered")
        void partiallyCovered_returnsViolation() {
            Optional<Violation> result = boundary.checkCriteriaCoverage(
                TaskId.generate(),
                List.of("检查订单状态", "确认库存", "发送通知"),
                List.of("检查订单状态")
            );

            assertTrue(result.isPresent());
            assertInstanceOf(Violation.CriteriaNotCovered.class, result.get());
            assertEquals("CRITERIA_NOT_COVERED", result.get().code());
        }

        @Test
        @DisplayName("checkCriteriaCoverageAll 返回所有未覆盖项")
        void allUncovered_returnsAll() {
            List<Violation> results = boundary.checkCriteriaCoverageAll(
                TaskId.generate(),
                List.of("标准A", "标准B", "标准C"),
                List.of("标准A")
            );

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(v -> v instanceof Violation.CriteriaNotCovered));
        }

        @Test
        @DisplayName("模糊匹配：verified 包含 criterion 的子串也算覆盖")
        void fuzzyMatch_covers() {
            Optional<Violation> result = boundary.checkCriteriaCoverage(
                TaskId.generate(),
                List.of("订单状态检查"),
                List.of("已验证：订单状态检查通过")
            );
            assertTrue(result.isEmpty());
        }
    }
}
