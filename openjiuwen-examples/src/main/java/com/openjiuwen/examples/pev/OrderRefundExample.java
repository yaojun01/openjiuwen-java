package com.openjiuwen.examples.pev;

import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 示例 2 启动类：PEV 订单退款 Agent。
 *
 * 展示 Alpha 策略的多步骤编排：
 * - AgentClient.invoke → AdaptiveStrategy 路由到 AlphaStrategy
 * - AlphaStrategy 自动触发 Plan → Execute → Verify 循环
 * - 每步的 AgentEvent 通过 invokeStream 可观察
 */
@SpringBootApplication
public class OrderRefundExample {

    public static void main(String[] args) {
        SpringApplication.run(OrderRefundExample.class, args);
    }

    @Bean
    CommandLineRunner pevDemo(AgentClient agentClient) {
        return args -> {
            System.out.println("=== PEV 订单退款示例 ===");

            // 流式观察 PEV 过程
            agentClient.invokeStream("order-refund-agent",
                    "订单 ORD-20260601-0042 的无线耳机质量问题，申请全额退款")
                .doOnNext(event -> {
                    // Alpha PEV 会发射 PlanCreated / StepExecuting / StepCompleted / VerifyResult 等事件
                    System.out.println("[PEV 事件] " + event.getClass().getSimpleName()
                        + " → " + event);
                })
                .blockLast();

            System.out.println("=== 示例完成 ===");
        };
    }
}
