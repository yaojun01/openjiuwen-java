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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 知识沉淀存储——记录任务成功/失败的执行路径特征。
 *
 * 用途：
 * 1. 相似任务推荐：新任务与历史成功任务匹配，推荐执行路径
 * 2. 失败模式学习：记录常见失败模式，提前预警
 * 3. 参数调优：统计不同目标类型的资源消耗基线
 *
 * 数据模型：
 * - goalPattern: 目标特征（提取自 GoalSpec）
 * - executionPath: 执行路径摘要
 * - outcome: SUCCESS / PARTIAL / FAILED
 * - criteria: 成功标准列表
 * - resourceProfile: 资源消耗统计
 *
 * 当前为接口定义，具体存储可对接 Redis / ES / 向量数据库。
 */
public interface KnowledgeStore {

    /**
     * 沉淀一条知识。
     *
     * @param goal            目标
     * @param decisionHistory 决策历史
     * @param outcome         执行结果
     * @param criteriaVerified 已验证通过的标准列表
     */
    void deposit(
        GoalSpec goal,
        List<LLMDecision> decisionHistory,
        ExecutionOutcome outcome,
        List<String> criteriaVerified
    );

    /**
     * 查找相似目标的历史执行记录。
     *
     * @param goalDescription 目标描述
     * @param maxResults      最多返回条数
     * @return 相似的历史执行记录
     */
    List<KnowledgeEntry> findSimilar(String goalDescription, int maxResults);

    /**
     * 获取目标类型的资源消耗基线。
     *
     * @param goalPattern 目标特征
     * @return 资源消耗统计
     */
    Optional<ResourceProfile> getResourceBaseline(String goalPattern);

    /**
     * 执行结果。
     */
    enum ExecutionOutcome {
        SUCCESS, PARTIAL, FAILED
    }

    /**
     * 知识条目。
     */
    record KnowledgeEntry(
        String goalPattern,
        String executionPathSummary,
        ExecutionOutcome outcome,
        List<String> criteria,
        ResourceProfile resourceProfile,
        Instant recordedAt
    ) {}

    /**
     * 资源消耗统计。
     */
    record ResourceProfile(
        int avgSteps,
        int avgToolCalls,
        int avgLLMCalls,
        long avgTokens,
        double successRate
    ) {}
}
