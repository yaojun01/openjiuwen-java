package com.openjiuwen.examples.wealth;

import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 示例 4：金融财富助手 Deep Agent 端到端运行。
 *
 * 展示金融场景下的多 Agent 协作：
 * - WealthDeepAgent（主 Agent）编排 3 个子 Agent
 * - 子 Agent 并行执行（持仓分析 + 市场研究 + 风险评估）
 * - 主 Agent 汇总结果生成财富建议报告
 *
 * 运行方式：
 * <pre>
 * mvn spring-boot:run -pl openjiuwen-examples \
 *   -Dspring-boot.run.main-class=com.openjiuwen.examples.wealth.WealthDeepAgentExample \
 *   -Dspring-boot.run.arguments="--spring.ai.openai.api-key=YOUR_KEY"
 * </pre>
 */
@SpringBootApplication
public class WealthDeepAgentExample {

    public static void main(String[] args) {
        SpringApplication.run(WealthDeepAgentExample.class, args);
    }

    @Bean
    CommandLineRunner wealthDemo(AgentClient agentClient) {
        return args -> {
            System.out.println("=== 金融财富助手 Deep Agent 示例 ===");
            System.out.println();
            System.out.println("场景：高净值客户咨询 → 主 Agent 编排 3 个子 Agent 并行分析");
            System.out.println("  主 Agent:   wealth-deep-agent (META 自主度)");
            System.out.println("  子 Agent 1: portfolio-analysis-agent (持仓分析)");
            System.out.println("  子 Agent 2: market-research-agent    (市场研究)");
            System.out.println("  子 Agent 3: risk-assessment-agent    (风险评估)");
            System.out.println();

            String request = """
                客户 CLT-20240001 希望进行季度财富体检：
                - 客户等级：金卡
                - 风险偏好：稳健型
                - 投资期限：3-5年
                - 关注板块：白酒、新能源
                - 咨询内容：请分析当前持仓状况，结合市场环境给出下季度投资建议
                """;

            // 流式观察 Deep Agent 的多 Agent 编排过程
            agentClient.invokeStream("wealth-deep-agent", request)
                .doOnNext(event -> {
                    String type = event.getClass().getSimpleName();
                    System.out.println("[财富助手事件] " + type + " → " + event);
                })
                .blockLast();

            System.out.println();
            System.out.println("=== 示例完成 ===");
        };
    }
}
