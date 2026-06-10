package com.openjiuwen.runtime.beta.reflection;

import com.openjiuwen.runtime.beta.model.LLMDecision;
import com.openjiuwen.core.kernel.model.ToolName;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认目标漂移检测实现。
 *
 * 检测策略（不依赖 LLM，纯规则判断）：
 *
 * 1. 每步检查（lightweight）——快速规则：
 *    a. 工具调用偏离度：最近 5 次工具调用中，有多少与目标关键词无关
 *    b. 思考内容偏离度：最近 3 次 ContinueThinking 的内容是否包含目标关键词
 *    c. Replan 频率：连续 replan 表明目标不可达或路径不稳定
 *
 * 2. FinalAnswer 前检查（deep）——需要 LLM 判断：
 *    由 CriteriaVerifier 负责，本类不处理
 *
 * 得分规则：
 * - 每个规则的偏离度 0.0-1.0
 * - 最终 score = 各规则得分的加权平均
 * - 权重：工具偏离(0.35) + 思考偏离(0.35) + replan频率(0.30)
 */
public class DefaultGoalAlignmentCheck implements GoalAlignmentCheck {

    /** 检查间隔：每 N 步检查一次 */
    private final int checkInterval;
    /** 目标关键词提取（简化：按空格/标点分词，去掉停用词） */
    private final Set<String> stopWords;

    public DefaultGoalAlignmentCheck() {
        this(5);
    }

    public DefaultGoalAlignmentCheck(int checkInterval) {
        this.checkInterval = checkInterval;
        this.stopWords = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "并", "及", "或", "与", "等", "为", "中", "对", "把", "被", "从", "让",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "shall", "can", "need", "dare",
            "and", "or", "but", "if", "then", "else", "when", "while", "for",
            "to", "of", "in", "on", "at", "by", "with", "from", "as", "into"
        );
    }

    @Override
    public AlignmentResult checkAlignment(
            String parentGoal,
            String currentGoal,
            List<LLMDecision> decisionHistory,
            int currentStep) {

        // 只在检查间隔时执行
        if (currentStep % checkInterval != 0 && currentStep > 1) {
            return new AlignmentResult(1.0, "非检查间隔，跳过", null, Instant.now());
        }

        // 如果是子 Agent（有 parentGoal），做完整检查
        // 如果是主 Agent，只在步数 > 15 时检查
        boolean isSubAgent = parentGoal != null && !parentGoal.isBlank();
        if (!isSubAgent && currentStep <= 15) {
            return new AlignmentResult(1.0, "主 Agent 步数未超阈值，跳过", null, Instant.now());
        }

        String targetGoal = isSubAgent ? parentGoal : currentGoal;
        Set<String> goalKeywords = extractKeywords(targetGoal);

        if (goalKeywords.isEmpty()) {
            return new AlignmentResult(1.0, "无法提取目标关键词，跳过", null, Instant.now());
        }

        // 各维度得分
        double toolScore = checkToolAlignment(decisionHistory, goalKeywords);
        double thoughtScore = checkThoughtAlignment(decisionHistory, goalKeywords);
        double replanScore = checkReplanStability(decisionHistory);

        // 加权平均
        double overallScore = toolScore * 0.35
                            + thoughtScore * 0.35
                            + replanScore * 0.30;

        // 生成注入提示
        String injection = null;
        if (overallScore < 0.3) {
            injection = "[严重偏离警告] 你的行为正在严重偏离原始任务目标。"
                + "原始目标: " + targetGoal + "。请立即回归正题，不要继续探索无关内容。";
        } else if (overallScore < 0.6) {
            injection = "[偏离警告] 你的行为可能偏离了原始任务目标。"
                + "原始目标: " + targetGoal + "。请确认你的下一步决策是否有助于达成目标。";
        }

        String reason = String.format(
            "工具对齐=%.2f, 思考对齐=%.2f, replan稳定性=%.2f → 综合=%.2f",
            toolScore, thoughtScore, replanScore, overallScore);

        return new AlignmentResult(overallScore, reason, injection, Instant.now());
    }

    // ==================== 各维度检查 ====================

    /**
     * 工具调用偏离度。
     * 检查最近 5 次工具调用的 reasoning 是否包含目标关键词。
     */
    private double checkToolAlignment(List<LLMDecision> history, Set<String> goalKeywords) {
        List<LLMDecision.CallTool> recentCalls = history.stream()
            .filter(d -> d instanceof LLMDecision.CallTool)
            .map(d -> (LLMDecision.CallTool) d)
            .skip(Math.max(0, history.stream()
                .filter(d -> d instanceof LLMDecision.CallTool).count() - 5))
            .toList();

        if (recentCalls.isEmpty()) return 1.0;

        long aligned = recentCalls.stream()
            .filter(ct -> {
                String text = (ct.reasoning() != null ? ct.reasoning() : "")
                    + " " + ct.toolName().value();
                return containsAnyKeyword(text.toLowerCase(), goalKeywords);
            })
            .count();

        return (double) aligned / recentCalls.size();
    }

    /**
     * 思考内容偏离度。
     * 检查最近 3 次 ContinueThinking 的内容是否包含目标关键词。
     */
    private double checkThoughtAlignment(List<LLMDecision> history, Set<String> goalKeywords) {
        List<LLMDecision.ContinueThinking> recentThoughts = history.stream()
            .filter(d -> d instanceof LLMDecision.ContinueThinking)
            .map(d -> (LLMDecision.ContinueThinking) d)
            .skip(Math.max(0, history.stream()
                .filter(d -> d instanceof LLMDecision.ContinueThinking).count() - 3))
            .toList();

        if (recentThoughts.isEmpty()) return 1.0; // 没有思考记录，不扣分

        long aligned = recentThoughts.stream()
            .filter(ct -> containsAnyKeyword(
                ct.thought().toLowerCase(), goalKeywords))
            .count();

        return (double) aligned / recentThoughts.size();
    }

    /**
     * Replan 稳定性。
     * 连续 replan 表明路径不稳定。
     * 0 次 replan → 1.0
     * 1-2 次 → 0.8
     * 3-4 次 → 0.5
     * 5+ 次 → 0.2
     */
    private double checkReplanStability(List<LLMDecision> history) {
        long replanCount = history.stream()
            .filter(d -> d instanceof LLMDecision.Replan)
            .count();

        if (replanCount == 0) return 1.0;
        if (replanCount <= 2) return 0.8;
        if (replanCount <= 4) return 0.5;
        return 0.2;
    }

    // ==================== 关键词工具 ====================

    private Set<String> extractKeywords(String goal) {
        if (goal == null || goal.isBlank()) return Set.of();
        return Arrays.stream(goal.toLowerCase()
                .replaceAll("[，。！？、；：“”‘’（）\\[\\]{},.!?;:\"'()\\[\\]]", " ")
                .split("\\s+"))
            .filter(w -> w.length() >= 2)
            .filter(w -> !stopWords.contains(w))
            .collect(Collectors.toSet());
    }

    private boolean containsAnyKeyword(String text, Set<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
