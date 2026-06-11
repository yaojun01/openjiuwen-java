package com.openjiuwen.examples.analysis;

import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 示例：纯推理 Alpha（PEV）成功路径。
 *
 * <p>与 {@code OrderRefundExample} 的区别：
 * <ul>
 *   <li>Agent 无 {@code @Tool}，任务自包含（文章原文内联在请求里）</li>
 *   <li>不依赖任何 mock 或真实业务系统</li>
 *   <li>期望结果：Plan（纯 {@code LLM_CALL} 节点）→ Execute（逐节点真实推理）→ Verify（LIGHT）→ PASS</li>
 * </ul>
 *
 * <p>运行：{@code ./test-alpha-glm.sh com.openjiuwen.examples.analysis.ArticleAnalysisExample}
 */
@SpringBootApplication
public class ArticleAnalysisExample {

    public static void main(String[] args) {
        SpringApplication.run(ArticleAnalysisExample.class, args);
    }

    @Bean
    CommandLineRunner articleDemo(AgentClient agentClient) {
        return args -> {
            System.out.println("=== 纯推理 Alpha 示例：技术文章分析 ===");

            // 文章原文内联，自包含——LLM 无需任何外部数据即可完成分析。
            // 三项明确交付物，方便 LIGHT verify 判断"是否完整回答了目标"。
            String task = """
                请分析下面这段技术文章，依次完成三项任务：
                1. 提取文章的 3 个核心观点；
                2. 针对每个观点，用一句话点评其价值或局限；
                3. 综合以上分析，写一段不超过 80 字的总结。

                <article>
                Java 21 引入虚拟线程后，高并发服务的写法正在改变。传统上我们用线程池配合回调或异步框架来承载大量并发请求，代码复杂且调试困难。虚拟线程让每个请求运行在独立的轻量级线程上，可以用同步阻塞的写法写出高吞吐代码。但这并不意味着线程池已经无用——CPU 密集型任务仍适合用平台线程承载。关键在于区分 IO 密集与 CPU 密集两种负载，分别选择合适的并发模型。
                </article>
                """;

            agentClient.invokeStream("article-analysis-agent", task)
                .doOnNext(event -> System.out.println("[Alpha 事件] "
                    + event.getClass().getSimpleName() + " → " + event))
                .blockLast();

            System.out.println("=== 示例完成 ===");
        };
    }
}
