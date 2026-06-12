package com.openjiuwen.examples.debate;

import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例 3 启动类：多角色辩论（对抗轮次拓扑）端到端运行。
 *
 * 展示 Alpha PEV 的菱形 TaskGraph：正反立论 → 互相反驳 → 主持人仲裁。
 *
 * <p>task 正文只描述「做什么」（辩题 + 辩论结构 + 交付物），不描述「怎么做」（节点/引用语法等
 * 技术规划语言），避免技术元语言被 LIGHT verify 误当成验收标准而判 FAIL。
 *
 * 运行方式：
 * <pre>
 * mvn spring-boot:run -pl openjiuwen-examples \
 *   -Dspring-boot.run.main-class=com.openjiuwen.examples.debate.DebateExample \
 *   -Dspring-boot.run.arguments="--spring.ai.openai.api-key=YOUR_KEY"
 * </pre>
 */
@SpringBootApplication
public class DebateExample {

    public static void main(String[] args) {
        SpringApplication.run(DebateExample.class, args);
    }

    @Bean
    CommandLineRunner debateDemo(AgentClient agentClient) {
        return args -> {
            System.out.println("=== 多角色辩论示例（Alpha 对抗拓扑）===");
            System.out.println("范式：正反立论 → 互相反驳 → 主持人仲裁");
            System.out.println();

            String task = """
                请围绕以下辩题展开一场结构化辩论，并给出主持人裁决。

                ## 辩题
                AI 生成内容（如 AI 生成的文章、绘画、音乐）是否应当享有版权保护？

                ## 辩论背景（供各方参考，可补充常识性论据）
                <context>
                - 版权法的传统前提是「人类作者」的独创性表达；
                - AI 生成内容是否构成「作品」、其独创性归谁（提示词作者？模型开发者？无？）存在争议；
                - 支持保护的考量：激励投入与创作生态、明确权属便于交易和维权；
                - 反对保护的考量：缺乏人类独创性、可能导致内容泛滥与人类创作者权益被挤压、
                  训练数据本身已包含人类劳动；
                - 各国现行实践不一：部分国家明确不保护纯 AI 生成内容，部分正在立法探索。
                </context>

                ## 辩论结构与交付要求
                请按以下结构展开，各部分保持精炼：
                先由正方与反方分别立论（各方 2-3 个核心论点及论据，控制在 200 字以内），
                再由双方针对对方立论互相反驳（各方控制在 200 字以内），
                最后由主持人综合双方观点给出中立裁决与胜负倾向（控制在 400 字以内）。
                最终输出须包含五个部分：正方立论、反方立论、正方反驳、反方反驳、主持人仲裁。
                """;

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("userInput", task);
            parameters.put("successCriteria", List.of(
                "输出包含正方立论、反方立论、双方互相反驳、以及主持人仲裁共五个部分",
                "反驳针对对方的实际论点，主持人裁决给出明确的胜负倾向"
            ));

            agentClient.invokeStream("debate-agent", parameters)
                .doOnNext(event -> System.out.println("[辩论事件] "
                    + event.getClass().getSimpleName() + " → " + event))
                .blockLast();

            System.out.println("=== 示例完成 ===");
        };
    }
}
