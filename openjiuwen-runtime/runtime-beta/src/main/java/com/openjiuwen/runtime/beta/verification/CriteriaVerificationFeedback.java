package com.openjiuwen.runtime.beta.verification;

import com.openjiuwen.runtime.beta.context.ContextWindowManager;
import com.openjiuwen.core.kernel.model.Violation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 验证反馈构造器——将验证失败的信息格式化为 LLM 能理解的反馈。
 *
 * 职责：
 * 1. 将 Violation 列表转为可读的反馈文本
 * 2. 构造"请继续执行以覆盖以下标准"的指令
 * 3. 提供 replan 的建议（如果连续验证失败）
 */
public final class CriteriaVerificationFeedback {

    private CriteriaVerificationFeedback() {}

    /**
     * 构建验证失败后的反馈消息（注入到上下文中）。
     *
     * @param violations 未通过的标准列表
     * @param attemptCount 本次任务中第几次验证失败
     * @return 反馈文本
     */
    public static String buildFeedback(List<Violation> violations, int attemptCount) {
        StringBuilder sb = new StringBuilder();

        sb.append("你的 Complete 决策被阻止。原因：以下成功标准尚未满足\n\n");

        for (int i = 0; i < violations.size(); i++) {
            sb.append(i + 1).append(". ");
            if (violations.get(i) instanceof Violation.CriteriaNotCovered cnc) {
                sb.append(cnc.criterion()).append(" — ").append(cnc.reason());
            } else {
                sb.append(violations.get(i).message());
            }
            sb.append("\n");
        }

        sb.append("\n");

        // 根据失败次数给出不同建议
        if (attemptCount >= 3) {
            sb.append("你已经多次尝试 Complete 但未能满足所有标准。建议：\n");
            sb.append("- 考虑 replan 换一种执行策略\n");
            sb.append("- 或者使用 RequestHumanHelp 请求人类协助\n");
        } else {
            sb.append("请继续执行，确保覆盖以上标准后再给出 Complete。\n");
            sb.append("你可以继续调用工具或思考，也可以 replan。\n");
        }

        return sb.toString();
    }

    /**
     * 构建验证通过后的确认消息。
     */
    public static String buildSuccessMessage(List<String> criteria) {
        return "所有成功标准已验证通过：\n"
            + criteria.stream()
                .map(c -> "- [PASS] " + c)
                .collect(Collectors.joining("\n"))
            + "\n任务完成。";
    }
}
