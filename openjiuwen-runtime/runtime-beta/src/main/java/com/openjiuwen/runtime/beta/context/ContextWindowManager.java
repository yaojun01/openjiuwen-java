package com.openjiuwen.runtime.beta.context;
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

import java.util.*;
import java.util.stream.Collectors;

/**
 * 上下文窗口管理器 v2——Beta 策略的核心组件。
 *
 * 三层压缩策略：
 * - 层 1（近期完整）：最近 K 条消息完整保留，不做任何压缩
 * - 层 2（中期摘要）：K 之前的消息按"语义块"分组摘要，每块保留关键结论
 * - 层 3（溢出处理）：当压缩后仍超限时，丢弃最低优先级的信息
 *
 * 优先级（不压缩 → 先压缩 → 可丢弃）：
 * 1. 系统提示（永远不压缩）
 * 2. 目标描述 + 成功标准（永远不压缩）
 * 3. 工具调用结果中的关键中间结论（尽量保留）
 * 4. ContinueThinking / 反思结论（摘要保留）
 * 5. Replan 记录（只保留最新一次的完整描述，之前的只保留原因）
 * 6. 护栏拒绝反馈（最早的可丢弃）
 *
 * 溢出处理策略：
 * 当压缩后仍超过 maxTokens 的 90%：
 * - 触发 ContextOverflowEvent
 * - 丢弃最低优先级的消息
 * - 注入"[部分历史因上下文限制被丢弃]"标记
 */
public class ContextWindowManager {

    /** 最大 Token 数 */
    private final int maxTokens;
    /** 完整保留的最近消息数 */
    private final int recentWindowSize;
    /** 压缩阈值（百分比），超过时触发压缩 */
    private final double compactionThreshold;
    /** 溢出阈值（百分比），超过时触发丢弃 */
    private final double overflowThreshold;

    /** 所有消息（按时间顺序） */
    private final List<ContextMessage> messages;
    /** 已生成的摘要（替代被压缩的早期消息） */
    private final List<ContextMessage> summaries;
    /** 当前估算的 Token 数 */
    private int estimatedTokens;
    /** 压缩次数（审计用） */
    private int compactionCount;

    public ContextWindowManager(int maxTokens) {
        this(maxTokens, 6, 0.7, 0.9);
    }

    public ContextWindowManager(int maxTokens, int recentWindowSize,
                                double compactionThreshold, double overflowThreshold) {
        this.maxTokens = maxTokens;
        this.recentWindowSize = recentWindowSize;
        this.compactionThreshold = compactionThreshold;
        this.overflowThreshold = overflowThreshold;
        this.messages = new ArrayList<>();
        this.summaries = new ArrayList<>();
        this.estimatedTokens = 0;
        this.compactionCount = 0;
    }

    /**
     * 添加一条消息到上下文。
     * 如果超过压缩阈值，先压缩再添加。
     */
    public void addMessage(ContextMessage message) {
        estimatedTokens += message.estimatedTokens();
        messages.add(message);

        if (estimatedTokens > maxTokens * compactionThreshold) {
            compact();
        }

        // 溢出处理：压缩后仍超限
        if (estimatedTokens > maxTokens * overflowThreshold) {
            handleOverflow();
        }
    }

    /**
     * 获取当前所有有效的上下文消息（摘要 + 近期完整）。
     * 顺序：系统提示 → 摘要 → 近期消息
     */
    public List<ContextMessage> messages() {
        List<ContextMessage> result = new ArrayList<>();

        // 第一条永远是系统提示
        if (!messages.isEmpty() && "system".equals(messages.get(0).role())) {
            result.add(messages.get(0));
        }

        // 摘要
        result.addAll(summaries);

        // 近期完整消息（跳过已被摘要替代的早期消息）
        int fullStart = Math.max(1, messages.size() - recentWindowSize);
        for (int i = fullStart; i < messages.size(); i++) {
            result.add(messages.get(i));
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * 构建压缩后的决策历史文本——给 DecisionPromptBuilder 使用。
     *
     * 格式：
     * [早期决策摘要]
     * - 步骤 1-5: 摘要内容
     * - 步骤 6-10: 摘要内容
     *
     * [近期决策（完整）]
     * 步骤 11: CallTool(query_sales, {company: A}) → 结果: ...
     * 步骤 12: ContinueThinking("分析数据发现...")
     */
    public String buildCompressedHistory() {
        StringBuilder sb = new StringBuilder();

        // 早期摘要
        if (!summaries.isEmpty()) {
            sb.append("[早期决策摘要]\n");
            for (ContextMessage summary : summaries) {
                sb.append("- ").append(summary.content()).append("\n");
            }
            sb.append("\n");
        }

        // 近期完整消息
        int fullStart = Math.max(1, messages.size() - recentWindowSize);
        List<ContextMessage> recentFull = messages.subList(
            fullStart, messages.size());
        if (!recentFull.isEmpty()) {
            sb.append("[近期决策]\n");
            for (ContextMessage msg : recentFull) {
                sb.append(msg.role()).append(": ")
                  .append(truncate(msg.content(), 500)).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 三层压缩。
     *
     * 策略：
     * 1. 保留第一条（系统提示）和最近 recentWindowSize 条
     * 2. 中间的消息按语义分组生成摘要
     * 3. 每次压缩只压缩"未被压缩过"的消息
     */
    public void compact() {
        // 可压缩的范围：第 1 条到 (size - recentWindowSize) 条
        int compressEnd = messages.size() - recentWindowSize;
        if (compressEnd <= 1) return; // 太少不压缩

        // 找到尚未被压缩的消息范围
        // 已有摘要覆盖的消息数 = 摘要中的消息不重复计算
        int alreadySummarizedStart = 1; // 跳过系统提示
        for (int i = summaries.size() - 1; i >= 0; i--) {
            // 摘要按顺序排列，最后一个摘要覆盖到最后一条被压缩的消息
            alreadySummarizedStart++;
        }

        if (alreadySummarizedStart >= compressEnd) return;

        // 收集需要压缩的消息
        List<ContextMessage> toCompress = messages.subList(
            alreadySummarizedStart, compressEnd);

        if (toCompress.isEmpty()) return;

        // 按语义块分组（连续同类型消息为一组）
        List<List<ContextMessage>> groups = groupBySemantics(toCompress);

        // 为每个组生成摘要
        for (List<ContextMessage> group : groups) {
            String summary = summarizeGroup(group);
            int tokens = group.stream()
                .mapToInt(ContextMessage::estimatedTokens)
                .sum();
            // 摘要约为原文的 30%
            int summaryTokens = Math.max(50, tokens / 3);

            summaries.add(new ContextMessage("system", summary, summaryTokens));

            // 从估算中扣除原文，加上摘要
            estimatedTokens -= tokens;
            estimatedTokens += summaryTokens;
        }

        compactionCount++;
    }

    /**
     * 溢出处理：压缩后仍超过阈值。
     *
     * 策略：按优先级从低到高丢弃摘要。
     * 不丢弃近期完整消息（那些是 LLM 最近在用的）。
     */
    private void handleOverflow() {
        // 从最早的摘要开始丢弃
        while (estimatedTokens > maxTokens * 0.8 && !summaries.isEmpty()) {
            ContextMessage removed = summaries.remove(0);
            estimatedTokens -= removed.estimatedTokens();
        }

        // 如果还不够，截断近期消息中内容最长的
        if (estimatedTokens > maxTokens * 0.8 && messages.size() > recentWindowSize + 1) {
            // 找到内容最长的非系统消息
            for (int i = 1; i < messages.size() - recentWindowSize; i++) {
                ContextMessage msg = messages.get(i);
                if (msg.content().length() > 200) {
                    String truncated = msg.content().substring(0, 200) + "...[已截断]";
                    int originalTokens = msg.estimatedTokens();
                    messages.set(i, new ContextMessage(msg.role(), truncated, truncated.length() / 4));
                    estimatedTokens -= (originalTokens - truncated.length() / 4);
                }
                if (estimatedTokens <= maxTokens * 0.8) break;
            }
        }
    }

    /** 当前估算的 Token 数 */
    public int estimatedTokens() {
        return estimatedTokens;
    }

    /** 上下文是否接近上限 */
    public boolean isNearCapacity() {
        return estimatedTokens > maxTokens * compactionThreshold;
    }

    /** 是否触发过溢出处理 */
    public boolean isOverflow() {
        return estimatedTokens > maxTokens * overflowThreshold;
    }

    /** 压缩次数 */
    public int compactionCount() {
        return compactionCount;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 按语义分组：连续同 role 的消息分为一组。
     * 特殊规则：连续的 tool 消息合并为一组（一次工具调用链）。
     */
    private List<List<ContextMessage>> groupBySemantics(List<ContextMessage> msgs) {
        List<List<ContextMessage>> groups = new ArrayList<>();
        List<ContextMessage> currentGroup = new ArrayList<>();

        for (ContextMessage msg : msgs) {
            if (currentGroup.isEmpty()
                    || currentGroup.get(0).role().equals(msg.role())) {
                currentGroup.add(msg);
            } else {
                groups.add(new ArrayList<>(currentGroup));
                currentGroup.clear();
                currentGroup.add(msg);
            }
        }
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }

        return groups;
    }

    /**
     * 为一组消息生成摘要。
     *
     * 策略（不调用 LLM，用规则压缩）：
     * - tool 消息组：提取工具名和结果的简要描述
     * - assistant 消息组：提取每条的前 100 字符
     * - system 消息组（护栏反馈等）：完整保留（通常很短）
     */
    private String summarizeGroup(List<ContextMessage> group) {
        String role = group.get(0).role();

        return switch (role) {
            case "tool" -> {
                // 工具调用组：列出工具名和结果摘要
                String toolSummaries = group.stream()
                    .map(msg -> {
                        String content = msg.content();
                        int arrowIdx = content.indexOf(" -> ");
                        if (arrowIdx > 0) {
                            return content.substring(0, Math.min(arrowIdx + 50, content.length()));
                        }
                        return truncate(content, 80);
                    })
                    .collect(Collectors.joining("; "));
                yield "工具调用(" + group.size() + "次): " + toolSummaries;
            }
            case "assistant" -> {
                // 思考消息组：提取每条的核心观点
                String thoughts = group.stream()
                    .map(msg -> truncate(msg.content(), 100))
                    .collect(Collectors.joining("; "));
                yield "思考记录(" + group.size() + "条): " + thoughts;
            }
            case "system" -> {
                // 系统消息（护栏反馈等）
                yield group.stream()
                    .map(msg -> truncate(msg.content(), 100))
                    .collect(Collectors.joining("; "));
            }
            default -> {
                yield group.size() + "条" + role + "消息";
            }
        };
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * 上下文消息。
     *
     * @param role            角色：system / user / assistant / tool
     * @param content         消息内容
     * @param estimatedTokens 估算的 Token 数
     */
    public record ContextMessage(String role, String content, int estimatedTokens) {
        public ContextMessage(String role, String content) {
            this(role, content, content.length() / 4); // 粗估：4字符≈1token
        }
    }
}
