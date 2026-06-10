package com.openjiuwen.runtime.beta.verification;
/**
 * ============================================================
 *  P2 DRAFT -- NOT part of P1 default compilation.
 *
 * This file belongs to the `runtime-beta` module, which is excluded from
 * P1's default Maven profile. It is only compiled with `-P all`.
 *
 * P2 will replace this draft with the final implementation.
 * See: docs/architecture/05-beta-llm-autonomous-orchestration.md
 * ============================================================
 */

import com.openjiuwen.runtime.beta.model.GoalSpec;
import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.runtime.core.engine.AgentKernel;
import com.openjiuwen.core.kernel.model.BudgetLimits;
import com.openjiuwen.core.kernel.model.Violation;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认成功标准验证器实现。
 *
 * 验证策略：
 * 1. 规则判断（优先）：
 *    a. 关键词覆盖：标准中的关键词是否出现在决策历史中
 *    b. 工具覆盖：标准隐含需要的操作是否被调用过
 *    c. 输出覆盖：Complete 的 output 是否包含标准要求的内容
 *
 * 2. LLM 判断（降级）：
 *    - 当规则判断无法确定时（关键词匹配不够），用 LLM 判断
 *    - LLM 判断的 prompt 包含：标准 + 决策历史摘要 + 最终输出
 *    - LLM 返回 PASS / FAIL + 原因
 *
 * 知识沉淀：
 * - 验证全部通过后，将"目标类型 + 成功的执行路径"记录为知识
 * - 知识用于后续类似任务的路径推荐
 */
public class DefaultCriteriaVerifier implements CriteriaVerifier {

    /** 关键词匹配的最小长度（避免单字匹配） */
    private static final int MIN_KEYWORD_LENGTH = 2;

    /** 规则判断无法确定时的降级策略 */
    private final FallbackStrategy fallbackStrategy;

    /** AgentKernel（可选，用于 LLM 判断降级） */
    private AgentKernel kernel;
    private BudgetLimits budgetLimits;

    public DefaultCriteriaVerifier() {
        this.fallbackStrategy = FallbackStrategy.ASSUME_FAIL;
    }

    public DefaultCriteriaVerifier(AgentKernel kernel) {
        this.fallbackStrategy = FallbackStrategy.LLM_JUDGE;
        this.kernel = kernel;
    }

    /**
     * 降级策略：规则判断无法确定时怎么办。
     */
    public enum FallbackStrategy {
        /** 保守：无法确定 = 未通过 */
        ASSUME_FAIL,
        /** 乐观：无法确定 = 通过 */
        ASSUME_PASS,
        /** 用 LLM 判断 */
        LLM_JUDGE
    }

    @Override
    public List<Violation> verify(
            GoalSpec goal,
            List<LLMDecision> decisionHistory,
            LLMDecision finalDecision) {

        List<String> criteria = goal.successCriteria();
        if (criteria == null || criteria.isEmpty()) {
            return List.of(); // 无标准，直接通过
        }

        // 构建验证素材
        String historyText = buildHistoryText(decisionHistory);
        String outputText = finalDecision instanceof LLMDecision.Complete c
            ? c.output() : "";

        List<Violation> violations = new ArrayList<>();

        for (String criterion : criteria) {
            CriterionVerification result = verifySingleCriterion(
                criterion, historyText, outputText, decisionHistory);

            if (!result.passed()) {
                violations.add(new Violation.CriteriaNotCovered(
                    criterion,
                    result.reason()
                ));
            }
        }

        // 如果全部通过，触发知识沉淀
        if (violations.isEmpty()) {
            triggerKnowledgeDeposit(goal, decisionHistory, finalDecision);
        }

        return violations;
    }

    // ==================== 单条标准验证 ====================

    /**
     * 验证单条成功标准。
     *
     * 三步验证：
     * 1. 输出覆盖：Complete 的 output 是否包含标准要求
     * 2. 历史覆盖：决策历史中是否有对应的操作记录
     * 3. 降级判断：前两步无法确定时的兜底
     */
    private CriterionVerification verifySingleCriterion(
            String criterion,
            String historyText,
            String outputText,
            List<LLMDecision> decisionHistory) {

        // 步骤 1: 输出覆盖检查
        OutputCheck outputCheck = checkOutputCoverage(criterion, outputText);
        if (outputCheck == OutputCheck.PASS) {
            return new CriterionVerification(
                criterion, true, VerificationMethod.RULE_BASED,
                "输出中包含标准相关内容", "输出覆盖检查通过");
        }

        // 步骤 2: 历史覆盖检查
        HistoryCheck historyCheck = checkHistoryCoverage(criterion, historyText, decisionHistory);
        if (historyCheck == HistoryCheck.PASS) {
            return new CriterionVerification(
                criterion, true, VerificationMethod.RULE_BASED,
                "决策历史中有对应的操作记录", "历史覆盖检查通过");
        }

        // 步骤 3: 降级判断
        if (outputCheck == OutputCheck.UNDETERMINED
                || historyCheck == HistoryCheck.UNDETERMINED) {
            return fallbackVerify(criterion, historyText, outputText);
        }

        // 两者都明确 FAIL
        return new CriterionVerification(
            criterion, false, VerificationMethod.RULE_BASED,
            "输出和历史均未覆盖该标准",
            "标准 '" + criterion + "' 未被任何执行过程验证覆盖");
    }

    // ==================== 输出覆盖检查 ====================

    private enum OutputCheck { PASS, FAIL, UNDETERMINED }

    /**
     * 检查最终输出是否覆盖了该标准。
     * 规则：标准中的核心词组是否出现在输出中。
     */
    private OutputCheck checkOutputCoverage(String criterion, String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return OutputCheck.FAIL;
        }

        // 提取标准中的核心词组
        List<String> keyPhrases = extractKeyPhrases(criterion);
        if (keyPhrases.isEmpty()) {
            return OutputCheck.UNDETERMINED; // 无法提取关键词，无法判断
        }

        String lowerOutput = outputText.toLowerCase();
        long matched = keyPhrases.stream()
            .filter(kp -> lowerOutput.contains(kp.toLowerCase()))
            .count();

        if (matched == keyPhrases.size()) {
            return OutputCheck.PASS; // 所有关键词都在输出中
        }
        if (matched > 0) {
            return OutputCheck.UNDETERMINED; // 部分匹配，不确定
        }
        return OutputCheck.FAIL; // 完全不匹配
    }

    // ==================== 历史覆盖检查 ====================

    private enum HistoryCheck { PASS, FAIL, UNDETERMINED }

    /**
     * 检查决策历史是否覆盖了该标准。
     * 规则：
     * - 有 CallTool 且 reasoning/工具名 包含标准关键词
     * - 有 ContinueThinking 且 thought 包含标准关键词
     * - 有 Replan 且 newApproach 涉及该标准
     */
    private HistoryCheck checkHistoryCoverage(
            String criterion, String historyText,
            List<LLMDecision> decisionHistory) {

        List<String> keyPhrases = extractKeyPhrases(criterion);
        if (keyPhrases.isEmpty()) {
            return HistoryCheck.UNDETERMINED;
        }

        // 检查工具调用的 reasoning 和工具名
        for (LLMDecision decision : decisionHistory) {
            String relevantText = extractRelevantText(decision);
            String lowerRelevant = relevantText.toLowerCase();

            long matched = keyPhrases.stream()
                .filter(kp -> lowerRelevant.contains(kp.toLowerCase()))
                .count();

            if (matched >= Math.ceil(keyPhrases.size() * 0.5)) {
                // 至少一半的关键词匹配，视为覆盖
                return HistoryCheck.PASS;
            }
        }

        return HistoryCheck.FAIL;
    }

    // ==================== 降级验证 ====================

    /**
     * 降级验证：规则判断无法确定时。
     */
    private CriterionVerification fallbackVerify(
            String criterion, String historyText, String outputText) {

        return switch (fallbackStrategy) {
            case ASSUME_FAIL -> new CriterionVerification(
                criterion, false, VerificationMethod.RULE_BASED,
                "规则无法确定，保守判定为未通过",
                "标准 '" + criterion + "' 无法通过规则验证确认");

            case ASSUME_PASS -> new CriterionVerification(
                criterion, true, VerificationMethod.RULE_BASED,
                "规则无法确定，乐观判定为通过",
                "标准 '" + criterion + "' 部分匹配，乐观通过");

            case LLM_JUDGE -> {
                // TODO: 调用 kernel.think() 用 LLM 判断
                // 当前先降级为 ASSUME_FAIL
                yield new CriterionVerification(
                    criterion, false, VerificationMethod.LLM_JUDGE,
                    "LLM 判断暂未实现，降级为未通过",
                    "标准 '" + criterion + "' 需要 LLM 验证（待实现）");
            }
        };
    }

    // ==================== 知识沉淀 ====================

    /**
     * 知识沉淀：将成功的执行路径记录下来。
     *
     * 记录内容：
     * - 目标类型（从 goal 提取）
     * - 成功标准列表
     * - 执行路径摘要（哪些工具、几步完成、是否 replan）
     * - 最终输出
     *
     * 用途：
     * - 后续类似任务可以推荐成功的执行路径
     * - 统计分析：哪些目标类型更容易成功
     */
    private void triggerKnowledgeDeposit(
            GoalSpec goal,
            List<LLMDecision> decisionHistory,
            LLMDecision finalDecision) {

        // 知识沉淀是副作用操作，不阻塞主流程
        // 实际实现中写入 KnowledgeStore

        // 统计执行路径特征
        long toolCallCount = decisionHistory.stream()
            .filter(d -> d instanceof LLMDecision.CallTool).count();
        long replanCount = decisionHistory.stream()
            .filter(d -> d instanceof LLMDecision.Replan).count();
        long thinkCount = decisionHistory.stream()
            .filter(d -> d instanceof LLMDecision.ContinueThinking).count();

        // 构建知识条目（简化版，实际写入 KnowledgeStore）
        String knowledge = String.format(
            "[知识沉淀] 目标: %s | 标准数: %d | 总步数: %d | 工具调用: %d | "
                + "思考: %d | Replan: %d | 完成",
            goal.goal(),
            goal.successCriteria().size(),
            decisionHistory.size(),
            toolCallCount,
            thinkCount,
            replanCount
        );

        // 生产环境中应调用:
        // knowledgeStore.deposit(goal.goal(), knowledge, decisionHistory);
    }

    // ==================== 辅助方法 ====================

    /**
     * 从标准文本中提取关键短语。
     * 策略：去掉标点和停用词，保留 2 字以上的词组。
     */
    private List<String> extractKeyPhrases(String criterion) {
        if (criterion == null || criterion.isBlank()) return List.of();

        // 按标点和空格分词
        String[] tokens = criterion
            .replaceAll("[，。！？、；：“”‘’（）\\[\\]{},.!?;:\"'()\\[\\]]", " ")
            .split("\\s+");

        Set<String> stopWords = Set.of(
            "的", "了", "在", "是", "有", "和", "就", "不", "都", "要",
            "或", "与", "及", "为", "中", "对", "等", "能", "被", "把",
            "the", "a", "an", "is", "are", "was", "be", "been",
            "have", "has", "do", "does", "will", "would", "should",
            "can", "may", "must", "and", "or", "but", "if", "to",
            "of", "in", "on", "at", "by", "with", "from"
        );

        return Arrays.stream(tokens)
            .filter(t -> t.length() >= MIN_KEYWORD_LENGTH)
            .filter(t -> !stopWords.contains(t.toLowerCase()))
            .distinct()
            .toList();
    }

    private String extractRelevantText(LLMDecision decision) {
        return switch (decision) {
            case LLMDecision.CallTool ct ->
                ct.reasoning() + " " + ct.toolName().value();
            case LLMDecision.ContinueThinking ct ->
                ct.thought();
            case LLMDecision.Replan r ->
                r.newApproach() + " " + r.replanReason();
            case LLMDecision.Complete c ->
                c.output() + " " + c.summary();
            default -> "";
        };
    }

    private String buildHistoryText(List<LLMDecision> history) {
        return history.stream()
            .map(this::extractRelevantText)
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining(" "));
    }
}
