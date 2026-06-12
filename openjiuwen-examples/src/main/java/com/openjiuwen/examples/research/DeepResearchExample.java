package com.openjiuwen.examples.research;

import com.openjiuwen.runtime.spring.AgentClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例 1 启动类：Deep Research（扇出拓扑）端到端运行。
 *
 * 展示 Alpha PEV 的扇出 TaskGraph：1 个分解节点 → 多个维度并行调研 → 1 个综合报告节点。
 *
 * <p>task 正文只描述「做什么」（研究问题 + 背景 + 交付物），不描述「怎么做」（节点/引用语法等
 * 技术规划语言）——后者由 planner 内置规则 + Agent systemPrompt 负责。否则技术元语言会
 * 被 LIGHT verify 误当成验收标准（去输出里找 ${} 字样）而判 FAIL。
 *
 * 运行方式：
 * <pre>
 * mvn spring-boot:run -pl openjiuwen-examples \
 *   -Dspring-boot.run.main-class=com.openjiuwen.examples.research.DeepResearchExample \
 *   -Dspring-boot.run.arguments="--spring.ai.openai.api-key=YOUR_KEY"
 * </pre>
 */
@SpringBootApplication
public class DeepResearchExample {

    public static void main(String[] args) {
        SpringApplication.run(DeepResearchExample.class, args);
    }

    @Bean
    CommandLineRunner researchDemo(AgentClient agentClient) {
        return args -> {
            System.out.println("=== Deep Research 示例（Alpha 扇出拓扑）===");
            System.out.println("范式：分解问题 → 多维度并行调研 → 综合报告");
            System.out.println();

            String task = """
                请对以下研究问题做一次深度分析，并产出一份结构化研究报告。

                ## 研究问题
                远程办公（WFH）的普及，对二线城市经济的中长期影响是什么？

                ## 背景资料（自包含，请基于此分析，不要联网）
                <background>
                自 2020 年以来，远程办公在科技与白领行业大规模普及并部分常态化。
                一线城市的部分企业允许员工长期远程或采用混合办公。观察到的现象包括：
                - 人口流动：部分一线工作者迁居到生活成本更低的二线城市（如成都、武汉、长沙、合肥）；
                - 房地产：迁入带动当地住宅与长租公寓需求上升，核心城区与新城房价均出现上涨；
                - 消费结构：迁入人口更偏线上消费、品质餐饮、教育培训与亲子服务，本地线下零售结构调整；
                - 产业与人才：二线城市对数字技能人才可得性提升，部分企业设立研发中心或区域总部，
                  高新技术产业招聘活跃度上升；
                - 压力面：基础设施（交通、医疗、教育）承载负荷增加，房价上涨挤压本地低收入群体。
                </background>

                ## 分析方法与交付要求
                请从三个互不重叠的维度分别展开分析（建议覆盖：人口与劳动力市场、住房与消费市场、
                基础设施与公共服务承载力）。每个维度的分析请控制在 250 字以内，用要点聚焦关键发现，不要展开长段。
                再综合三维度发现写成一份精炼报告（控制在 600 字以内），结构化呈现：
                总体结论、三维度要点归纳、主要风险与政策建议。
                分析须基于背景资料，不得编造具体数字。
                """;

            // 通过 parameters 传 successCriteria，既引导 Verify 判定，也进 planner prompt 约束规划
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("userInput", task);
            parameters.put("successCriteria", List.of(
                "报告包含总体结论、3 个维度的关键发现、以及风险与政策建议",
                "各维度分析基于背景资料，无明显的虚构数据或编造数字"
            ));

            agentClient.invokeStream("deep-research-agent", parameters)
                .doOnNext(event -> System.out.println("[Deep Research 事件] "
                    + event.getClass().getSimpleName() + " → " + event))
                .blockLast();

            System.out.println("=== 示例完成 ===");
        };
    }
}
