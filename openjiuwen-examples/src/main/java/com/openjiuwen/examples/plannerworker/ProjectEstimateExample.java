package com.openjiuwen.examples.plannerworker;

import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例 2 启动类：Planner-Worker（带真实 @Tool）端到端运行。
 *
 * 首次端到端验证 Alpha PEV 的 TOOL_CALL 执行路径：分解 → estimateHours 工具并行 → 成本汇总。
 *
 * <p>task 正文只描述「做什么」（项目 + 可用工具 + 交付物），不描述「怎么做」（节点 type /
 * inputs 字面量 / 引用语法等技术规划语言），避免技术元语言被 LIGHT verify 误当成验收标准。
 *
 * 运行方式：
 * <pre>
 * mvn spring-boot:run -pl openjiuwen-examples \
 *   -Dspring-boot.run.main-class=com.openjiuwen.examples.plannerworker.ProjectEstimateExample \
 *   -Dspring-boot.run.arguments="--spring.ai.openai.api-key=YOUR_KEY"
 * </pre>
 */
@SpringBootApplication
public class ProjectEstimateExample {

    public static void main(String[] args) {
        SpringApplication.run(ProjectEstimateExample.class, args);
    }

    @Bean
    CommandLineRunner estimateDemo(AgentClient agentClient) {
        return args -> {
            System.out.println("=== Planner-Worker 示例（Alpha TOOL_CALL 路径）===");
            System.out.println("范式：分解子任务 → estimateHours 工具并行估工时 → 成本汇总");
            System.out.println();

            String task = """
                请估算下面软件项目的工时与成本。

                ## 项目
                开发一个博客系统。已知需要的功能模块：
                - 前端页面（首页、文章列表、文章详情、评论组件）
                - 后端 API（文章 CRUD、评论、用户认证鉴权）
                - 数据库设计（文章表、评论表、用户表及索引）
                - 部署运维（CI/CD 流水线、基础监控告警）

                ## 费率
                1500 元/人天。

                ## 可用工具
                你可使用工具 estimateHours(task, complexity) 估算单个子任务的工时（人天）：
                task 为子任务描述，complexity 为 1-5 的复杂度评分
                （1=简单 2=较易 3=中等 4=较难 5=极复杂）。

                ## 交付要求
                请先把项目分解为具体子任务并评定各自的复杂度（1-5），对每个子任务调用
                estimateHours 工具估算工时，最后汇总所有子任务工时并按 1500 元/人天 计算总成本。
                输出须包含：各子任务的工时与成本明细，以及项目总成本。
                """;

            // 关键：通过 availableTools 告知 planner 可用工具名（必须与 @Tool 方法名一致）
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("userInput", task);
            parameters.put("availableTools", List.of("estimateHours"));
            parameters.put("successCriteria", List.of(
                "输出包含各子任务的工时与成本明细，以及按 1500 元/人天 计算的项目总成本",
                "工时通过 estimateHours 工具估算"
            ));

            agentClient.invokeStream("project-estimate-agent", parameters)
                .doOnNext(event -> System.out.println("[Planner-Worker 事件] "
                    + event.getClass().getSimpleName() + " → " + event))
                .blockLast();

            System.out.println("=== 示例完成 ===");
        };
    }
}
