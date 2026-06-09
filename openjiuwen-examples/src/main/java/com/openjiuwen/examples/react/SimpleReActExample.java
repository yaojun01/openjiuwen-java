package com.openjiuwen.examples.react;

import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 示例 1 启动类：ReAct Agent 运行入口。
 *
 * 使用方式：
 * <pre>
 * mvn spring-boot:run -pl openjiuwen-examples \
 *   -Dspring-boot.run.arguments="--spring.ai.openai.api-key=YOUR_KEY"
 * </pre>
 *
 * 关键点：
 * - @SpringBootApplication 触发 starter 自动装配
 * - AgentClient 由 starter 自动注入
 * - SimpleReActAgent 被 @Agent 注解标记，自动注册
 */
@SpringBootApplication
public class SimpleReActExample {

    public static void main(String[] args) {
        SpringApplication.run(SimpleReActExample.class, args);
    }

    @Bean
    CommandLineRunner demo(AgentClient agentClient) {
        return args -> {
            System.out.println("=== ReAct Agent 示例 ===");

            // 同步调用（简化演示，实际返回 Mono）
            String result = agentClient.invoke("weather-agent", "北京明天天气怎么样？").block();
            System.out.println("结果: " + result);

            // 流式调用
            agentClient.invokeStream("weather-agent", "对比北京和上海的天气")
                .doOnNext(event -> System.out.println("事件: " + event))
                .blockLast();

            System.out.println("=== 示例完成 ===");
        };
    }
}
