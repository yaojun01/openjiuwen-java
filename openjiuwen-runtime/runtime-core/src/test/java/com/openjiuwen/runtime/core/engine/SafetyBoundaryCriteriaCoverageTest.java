package com.openjiuwen.runtime.core.engine;

import com.openjiuwen.core.kernel.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W3 修复测试：SafetyBoundary.checkCriteriaCoverage 的强制检查。
 *
 * 三个场景：
 * - A: 全部 criteria 通过
 * - B: 1条 criterion 未覆盖，触发 Violation
 * - C: 所有 criteria 都未覆盖
 */
@DisplayName("W3: SafetyBoundary successCriteria 覆盖检查")
class SafetyBoundaryCriteriaCoverageTest {

    private DefaultSafetyBoundary safetyBoundary;
    private TaskId testTaskId;

    @BeforeEach
    void setUp() {
        safetyBoundary = new DefaultSafetyBoundary();
        testTaskId = TaskId.generate();
    }

    // ==================== 场景A：全部通过 ====================

    @Nested
    @DisplayName("场景A：3条 criteria 全部通过")
    class AllCriteriaCovered {

        @Test
        @DisplayName("3条 criteria 精确匹配，全部通过")
        void threeExactMatches_allPass() {
            List<String> successCriteria = List.of(
                "查询订单状态",
                "计算退款金额",
                "生成退款记录"
            );
            List<String> verifiedCriteria = List.of(
                "查询订单状态",
                "计算退款金额",
                "生成退款记录"
            );

            Optional<Violation> result = safetyBoundary.checkCriteriaCoverage(
                testTaskId, successCriteria, verifiedCriteria);

            assertTrue(result.isEmpty(), "全部精确匹配应该返回 empty");
        }

        @Test
        @DisplayName("3条 criteria 包含匹配，全部通过")
        void threeSubstringMatches_allPass() {
            List<String> successCriteria = List.of(
                "数据完整性",
                "格式正确性",
                "业务合规性"
            );
            // verified 中包含 successCriteria 的子串
            List<String> verifiedCriteria = List.of(
                "已验证数据完整性检查通过",
                "已验证格式正确性校验完成",
                "已验证业务合规性审核通过"
            );

            Optional<Violation> result = safetyBoundary.checkCriteriaCoverage(
                testTaskId, successCriteria, verifiedCriteria);

            assertTrue(result.isEmpty(), "子串包含匹配应该返回 empty");
        }

        @Test
        @DisplayName("反向包含匹配：criterion 包含 verified")
        void reverseContainsMatch_allPass() {
            List<String> successCriteria = List.of(
                "已验证订单状态查询的完整性",
                "已计算退款金额并确认",
                "已生成退款记录并写入数据库"
            );
            List<String> verifiedCriteria = List.of(
                "订单状态",
                "退款金额",
                "退款记录"
            );

            Optional<Violation> result = safetyBoundary.checkCriteriaCoverage(
                testTaskId, successCriteria, verifiedCriteria);

            assertTrue(result.isEmpty(), "反向包含匹配也应该通过");
        }
    }

    // ==================== 场景B：1条未覆盖 ====================

    @Nested
    @DisplayName("场景B：1条 criterion 未覆盖")
    class OneCriterionUncovered {

        @Test
        @DisplayName("第3条 criterion 未覆盖，返回 Violation")
        void thirdCriterionUncovered_violation() {
            List<String> successCriteria = List.of(
                "查询订单状态",
                "计算退款金额",
                "生成退款记录"
            );
            List<String> verifiedCriteria = List.of(
                "查询订单状态",
                "计算退款金额"
                // "生成退款记录" 缺失
            );

            Optional<Violation> result = safetyBoundary.checkCriteriaCoverage(
                testTaskId, successCriteria, verifiedCriteria);

            assertTrue(result.isPresent(), "有未覆盖的标准应返回 Violation");
            assertInstanceOf(Violation.CriteriaNotCovered.class, result.get(),
                "Violation 类型应为 CriteriaNotCovered");

            Violation.CriteriaNotCovered violation = (Violation.CriteriaNotCovered) result.get();
            assertEquals("生成退款记录", violation.criterion());
            assertEquals("CRITERIA_NOT_COVERED", violation.code());
            assertEquals(Violation.Severity.HIGH, violation.severity());
            assertTrue(violation.message().contains("生成退款记录"));
        }

        @Test
        @DisplayName("checkCriteriaCoverageAll 返回恰好1条 Violation")
        void thirdCriterionUncovered_allReturnsOne() {
            List<String> successCriteria = List.of(
                "查询订单状态",
                "计算退款金额",
                "生成退款记录"
            );
            List<String> verifiedCriteria = List.of(
                "查询订单状态",
                "计算退款金额"
            );

            List<Violation> violations = safetyBoundary.checkCriteriaCoverageAll(
                testTaskId, successCriteria, verifiedCriteria);

            assertEquals(1, violations.size(), "应返回恰好1条 Violation");
            assertInstanceOf(Violation.CriteriaNotCovered.class, violations.getFirst());
            assertEquals("生成退款记录",
                ((Violation.CriteriaNotCovered) violations.getFirst()).criterion());
        }
    }

    // ==================== 场景C：全部未覆盖 ====================

    @Nested
    @DisplayName("场景C：所有 criteria 都未覆盖")
    class AllCriteriaUncovered {

        @Test
        @DisplayName("3条全部未覆盖，checkCriteriaCoverage 返回第一条 Violation")
        void allUncovered_returnsFirstViolation() {
            List<String> successCriteria = List.of(
                "查询订单状态",
                "计算退款金额",
                "生成退款记录"
            );
            List<String> verifiedCriteria = List.of(); // 空

            Optional<Violation> result = safetyBoundary.checkCriteriaCoverage(
                testTaskId, successCriteria, verifiedCriteria);

            assertTrue(result.isPresent());
            assertInstanceOf(Violation.CriteriaNotCovered.class, result.get());
            assertEquals("查询订单状态",
                ((Violation.CriteriaNotCovered) result.get()).criterion());
        }

        @Test
        @DisplayName("3条全部未覆盖，checkCriteriaCoverageAll 返回3条 Violation")
        void allUncovered_returnsAllThreeViolations() {
            List<String> successCriteria = List.of(
                "查询订单状态",
                "计算退款金额",
                "生成退款记录"
            );
            List<String> verifiedCriteria = List.of();

            List<Violation> violations = safetyBoundary.checkCriteriaCoverageAll(
                testTaskId, successCriteria, verifiedCriteria);

            assertEquals(3, violations.size(), "应返回3条 Violation");

            for (int i = 0; i < violations.size(); i++) {
                assertInstanceOf(Violation.CriteriaNotCovered.class, violations.get(i));
                assertEquals(Violation.Severity.HIGH, violations.get(i).severity());
                assertEquals("CRITERIA_NOT_COVERED", violations.get(i).code());
            }

            // 验证顺序保持一致
            assertEquals("查询订单状态",
                ((Violation.CriteriaNotCovered) violations.get(0)).criterion());
            assertEquals("计算退款金额",
                ((Violation.CriteriaNotCovered) violations.get(1)).criterion());
            assertEquals("生成退款记录",
                ((Violation.CriteriaNotCovered) violations.get(2)).criterion());
        }

        @Test
        @DisplayName("空 successCriteria 列表直接通过")
        void emptySuccessCriteria_passes() {
            List<String> successCriteria = List.of();
            List<String> verifiedCriteria = List.of();

            Optional<Violation> result = safetyBoundary.checkCriteriaCoverage(
                testTaskId, successCriteria, verifiedCriteria);

            assertTrue(result.isEmpty(), "空 successCriteria 应直接通过");
        }
    }

    // ==================== Violation 密封接口完整性 ====================

    @Nested
    @DisplayName("Violation sealed interface 新类型验证")
    class ViolationSealedInterfaceTest {

        @Test
        @DisplayName("CriteriaNotCovered 所有字段正确")
        void criteriaNotCovered_fieldsCorrect() {
            Violation.CriteriaNotCovered v = new Violation.CriteriaNotCovered(
                "数据完整性", "未覆盖");
            assertEquals("CRITERIA_NOT_COVERED", v.code());
            assertTrue(v.message().contains("数据完整性"));
            assertEquals(Violation.Severity.HIGH, v.severity());
            assertTrue(v.remediation().contains("数据完整性"));
        }

        @Test
        @DisplayName("McpSecurityViolation 所有字段正确")
        void mcpSecurityViolation_fieldsCorrect() {
            Violation.McpSecurityViolation v = new Violation.McpSecurityViolation(
                "http://sdk:8080/mcp", "证书无效");
            assertEquals("MCP_SECURITY_VIOLATION", v.code());
            assertTrue(v.message().contains("http://sdk:8080/mcp"));
            assertEquals(Violation.Severity.CRITICAL, v.severity());
            assertNotNull(v.remediation());
        }
    }
}
