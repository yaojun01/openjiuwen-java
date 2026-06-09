package com.openjiuwen.examples.deepagent;

import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 示例 3 启动类：Deep Agent 端到端运行。
 *
 * 展示 Deep Agent 的多 Agent 协作：
 * - OrderDeepAgent（主 Agent）编排 2 个子 Agent
 * - 子 Agent 并行执行（库存检查 + 风控评估）
 * - 主 Agent 汇总结果做最终决策
 *
 * Deep Agent = Agent of Agents
 * - 主 Agent 使用 META 自主度，可以调度子 Agent
 * - 子 Agent 各自独立，有专属的 systemPrompt 和工具集
 * - 子 Agent 之间并行执行，主 Agent 聚合决策
 */
@SpringBootApplication
public class OrderDeepAgentExample {

    public static void main(String[] args) {
        SpringApplication.run(OrderDeepAgentExample.class, args);
    }

    @Bean
    CommandLineRunner deepAgentDemo(AgentClient agentClient) {
        return args -> {
            System.out.println("=== Deep Agent 端到端示例 ===");
            System.out.println();
            System.out.println("场景：用户下单 → 主 Agent 编排子 Agent 并行处理");
            System.out.println("  主 Agent: order-deep-agent (META 自主度)");
            System.out.println("  子 Agent 1: inventory-check-agent (库存检查)");
            System.out.println("  子 Agent 2: risk-assess-agent (风控评估)");
            System.out.println();

            String request = """
                处理新订单：
                - 订单号：ORD-20260608-1234
                - 用户ID：USR-8899
                - 商品：SKU-WH-001 无线耳机 x2
                - 金额：598.00 元
                """;

            // 流式观察 Deep Agent 的多 Agent 编排过程
            agentClient.invokeStream("order-deep-agent", request)
                .doOnNext(event -> {
                    String type = event.getClass().getSimpleName();
                    System.out.println("[Deep Agent 事件] " + type + " → " + event);
                })
                .blockLast();

            System.out.println();
            System.out.println("=== 示例完成 ===");
        };
    }
}
