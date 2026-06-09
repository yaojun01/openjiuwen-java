# Openjiuwen-Java P1 开发者端到端验证文档

> 2026-06-07 | P1 Developer Guide | 端到端验证
> 目标读者：行业 Java 开发者（熟悉 Spring Boot，不熟悉 AI Agent）
> P1 范围：AlphaStrategy（PEV）+ AgentKernel + SafetyBoundary + MCP 安全 + SDK
> 验收标准：一个 Spring Boot 开发者在 10 分钟内跑通第一个 Agent

---

## 目录

- [一、快速入门：10 分钟跑通你的第一个 Agent](#一快速入门10-分钟跑通你的第一个-agent)
- [二、完整配置参考](#二完整配置参考)
- [三、示例 1：简单问答 Agent（ReAct 叶子执行）](#三示例-1简单问答-agentreact-叶子执行)
- [四、示例 2：多步骤任务 Agent（PEV，含子 Agent）](#四示例-2多步骤任务-agentpev含子-agent)
- [五、示例 3：Java 8 老系统接入（SDK Remote）](#五示例-3java-8-老系统接入sdk-remote)
- [六、与 Spring AI 原生写法对比](#六与-spring-ai-原生写法对比)
- [七、常见问题（FAQ）](#七常见问题faq)
- [八、P1 API 参考](#八p1-api-参考)

---

## 一、快速入门：10 分钟跑通你的第一个 Agent

### 前置条件

| 项目 | 要求 |
|------|------|
| Java | 21+（Runtime 模式）/ 8+（SDK Remote 模式） |
| Spring Boot | 3.3+（Runtime）/ 1.x-4.x（SDK） |
| Maven / Gradle | 任意 |
| LLM API Key | DeepSeek / OpenAI / Anthropic / 阿里云 DashScope，至少一个 |

### Step 1：添加依赖

**Maven（pom.xml）**

```xml
<!-- Openjiuwen Spring Boot Starter — 一个依赖搞定 -->
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- 选择一个 LLM Provider（以 DeepSeek 为例） -->
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-provider-deepseek</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**Gradle（build.gradle）**

```groovy
implementation 'com.openjiuwen:openjiuwen-spring-boot-starter:0.1.0-SNAPSHOT'
implementation 'com.openjiuwen:openjiuwen-provider-deepseek:0.1.0-SNAPSHOT'
```

> **说明**：`openjiuwen-spring-boot-starter` 会自动引入 `openjiuwen-java-core` 和 `openjiuwen-java-runtime`，并完成自动配置。不需要手动声明 Spring AI 依赖。

### Step 2：定义 Agent

```java
package com.example.agent;

import com.openjiuwen.java.core.agent.Agent;
import com.openjiuwen.java.core.agent.AutonomyLevel;
import com.openjiuwen.java.core.tool.Tool;
import com.openjiuwen.java.core.tool.Param;

/**
 * 我的第一个 Agent。
 *
 * @Agent 注解声明这是一个 Agent，就像 @Service 声明一个服务。
 * - name: Agent 的唯一标识，调用时用这个名字
 * - autonomy: 自治级别，GUIDED 表示每步需要 LLM 推理但受开发者约束
 *
 * Runtime 启动时会自动扫描并注册这个 Agent。
 */
@Agent(name = "my-first-agent", autonomy = AutonomyLevel.GUIDED)
public class MyFirstAgent {

    /**
     * 工具方法。Agent 可以调用它来搜索信息。
     *
     * @Tool 注解声明这是一个工具方法，LLM 会根据描述决定何时调用。
     * @Param 注解描述参数含义，帮助 LLM 正确传参。
     */
    @Tool(description = "搜索信息")
    public String search(
        @Param(description = "搜索关键词") String query
    ) {
        // 这里写你的业务逻辑。
        // POC 阶段可以返回硬编码结果，后续接入真实搜索引擎。
        return "搜索结果：" + query + " → 这是模拟结果";
    }

    /**
     * 你可以定义多个工具。Agent 会根据用户的问题自动选择调用哪个。
     */
    @Tool(description = "获取当前时间")
    public String getCurrentTime() {
        return java.time.LocalDateTime.now().toString();
    }
}
```

### Step 3：调用 Agent

```java
package com.example.controller;

import com.openjiuwen.java.runtime.client.AgentClient;
import com.openjiuwen.java.runtime.client.AgentEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST 控制器。注入 AgentClient，像调用一个 Service 一样调用 Agent。
 *
 * AgentClient 由 openjiuwen-spring-boot-starter 自动注入，
 * 不需要手动配置。
 */
@RestController
@RequiredArgsConstructor
public class MyController {

    private final AgentClient agentClient;

    /**
     * 同步调用。最简单的用法——发一个问题，拿一个答案。
     */
    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return agentClient.invoke("my-first-agent", q);
    }

    /**
     * 流式调用。适合前端对话场景——实时显示 Agent 的思考过程。
     */
    @GetMapping("/chat")
    public String chat(@RequestParam String q) {
        StringBuilder sb = new StringBuilder();

        agentClient.stream("my-first-agent", q, new AgentEventHandler() {
            @Override
            public void onThinking(String thought) {
                sb.append("[思考] ").append(thought).append("\n");
            }

            @Override
            public void onToolCall(String tool, java.util.Map<String, Object> args) {
                sb.append("[调工具] ").append(tool).append("(").append(args).append(")\n");
            }

            @Override
            public void onComplete(String output) {
                sb.append("[回答] ").append(output);
            }
        });

        return sb.toString();
    }
}
```

### Step 3.5：最小配置（application.yml）

```yaml
# 最小配置：只需要指定 LLM Provider 的 API Key
openjiuwen:
  llm:
    provider: deepseek          # 使用哪个 LLM
    api-key: ${DEEPSEEK_API_KEY}  # 从环境变量读取
    model: deepseek-chat          # 模型名
```

### 启动 & 验证

```bash
# 1. 设置 API Key 环境变量
export DEEPSEEK_API_KEY=sk-your-key-here

# 2. 启动 Spring Boot 应用
mvn spring-boot:run

# 3. 测试同步调用
curl "http://localhost:8080/ask?q=今天几号"
# 预期输出：Agent 调用 getCurrentTime() 工具，返回当前时间

# 4. 测试流式调用
curl "http://localhost:8080/chat?q=帮我搜索Openjiuwen"
# 预期输出：
# [思考] 用户想要搜索 Openjiuwen 的信息，我需要调用搜索工具...
# [调工具] search({query=Openjiuwen})
# [回答] 根据搜索结果...
```

> **10 分钟到了吗？** 如果上面的步骤你跑通了——恭喜，你已经写出了一个 AI Agent。接下来的文档告诉你怎么做得更好。

---

## 二、完整配置参考

以下是 P1 阶段的完整 `application.yml`，每个配置项都有注释说明。开发时只需要改 LLM 部分即可。

```yaml
# ============================================================
# Openjiuwen-Java P1 完整配置参考
# ============================================================

spring:
  application:
    name: my-agent-app
  main:
    banner-mode: off              # 可选：关掉启动 Banner

# ============================================================
# LLM 配置（必填）
# ============================================================
openjiuwen:
  llm:
    # --- Provider 选择 ---
    # 支持：deepseek / openai / anthropic / dashscope / gemini / ollama
    provider: deepseek

    # --- API 密钥（从环境变量读取，不要明文写在配置里）---
    api-key: ${LLM_API_KEY}

    # --- 模型选择 ---
    # 不同 Provider 的模型名不同：
    #   deepseek:  deepseek-chat / deepseek-reasoner
    #   openai:    gpt-4o / gpt-4o-mini
    #   anthropic: claude-sonnet-4-20250514
    #   dashscope: qwen-plus / qwen-max
    model: deepseek-chat

    # --- 连接配置 ---
    base-url: https://api.deepseek.com    # 可选：自定义 API 地址（兼容 OpenAI 接口的第三方服务）
    connect-timeout: 5000                  # 连接超时（毫秒）
    read-timeout: 60000                    # 读取超时（毫秒），LLM 推理可能较慢

    # --- Token 预算 ---
    max-input-tokens: 32000                # 单次请求最大输入 Token
    max-output-tokens: 4096                # 单次请求最大输出 Token

  # ============================================================
  # Agent 全局默认配置（可选，@Agent 注解可覆盖）
  # ============================================================
  agent:
    default-max-iterations: 10            # ReAct 最大迭代次数（防止死循环）
    default-strategy: react               # 默认执行策略：react / pev
    system-prompt-template: |             # 全局系统提示模板（可选）
      你是一个有帮助的 AI 助手。
      回答时请简洁明了。
      如果不确定，请说明。

  # ============================================================
  # 记忆系统（可选）
  # ============================================================
  memory:
    type: in-memory                       # in-memory / redis / jdbc
    # redis 配置（type=redis 时生效）
    # redis:
    #   host: localhost
    #   port: 6379
    max-messages: 50                      # 对话历史保留条数
    auto-compress: true                   # 自动压缩过长上下文

  # ============================================================
  # 检查点（可选，长流程恢复用）
  # ============================================================
  checkpoint:
    enabled: true                         # 是否启用检查点
    store: in-memory                      # in-memory / redis / jdbc
    interval: 5                           # 检查点间隔（秒）

  # ============================================================
  # 安全层（可选，P1 基础安全）
  # ============================================================
  security:
    enabled: false                        # 开发环境关闭，生产环境开启

    # --- L1: 传输安全 ---
    tls:
      enabled: false                      # 开发环境不启用
      # mtls-required: true              # 生产环境强制 mTLS
      # key-store-path: /certs/runtime.p12
      # key-store-password: ${TLS_KS_PASSWORD}
      # trust-store-path: /certs/truststore.p12
      # trust-store-password: ${TLS_TS_PASSWORD}

    # --- L4: 参数校验（推荐开启）---
    validation:
      enabled: true                       # 参数类型和必填校验
      strict-mode: false                  # 严格模式：未知参数报错

    # --- L5: 审计日志（推荐开启）---
    audit:
      enabled: true
      store: in-memory                    # in-memory / jdbc
      sanitize-before-log: false          # 开发环境不脱敏

  # ============================================================
  # 可观测性（可选）
  # ============================================================
  observability:
    enabled: true
    log-level: info                       # debug 可查看完整的推理过程
    trace-llm-calls: true                 # 记录每次 LLM 调用的 Token 消耗

# ============================================================
# Server 配置（Spring Boot 标准）
# ============================================================
server:
  port: 8080

logging:
  level:
    com.openjiuwen: info                  # 改为 debug 可查看详细推理过程
    # com.openjiuwen.java.runtime.strategy: debug  # 只调试策略层
```

### 不同环境的推荐配置

| 环境 | security | checkpoint.store | memory.type | log-level |
|------|----------|-------------------|-------------|-----------|
| **本地开发** | `false` | `in-memory` | `in-memory` | `debug` |
| **测试** | `true`（API Key 模式） | `in-memory` | `in-memory` | `info` |
| **生产** | `true`（mTLS 模式） | `redis` | `redis` | `warn` |

---

## 三、示例 1：简单问答 Agent（ReAct 叶子执行）

**场景**：客服 FAQ Bot，用户问问题，Agent 搜索知识库回答。

**执行策略**：ReAct（Reasoning + Acting 循环），适合简单问答场景。

### 完整 Java 代码

```java
package com.example.faq;

import com.openjiuwen.java.core.agent.Agent;
import com.openjiuwen.java.core.agent.AutonomyLevel;
import com.openjiuwen.java.core.agent.SystemPrompt;
import com.openjiuwen.java.core.tool.Tool;
import com.openjiuwen.java.core.tool.Param;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * FAQ 客服 Agent。
 *
 * 使用 ReAct 策略（默认）：
 * 1. 用户提问 → LLM 推理
 * 2. LLM 决定调用哪个工具 → 执行工具
 * 3. 工具结果返回 LLM → LLM 继续推理或输出答案
 *
 * autonomy = GUIDED 表示 Agent 受约束运行：
 * - 最多迭代 10 次（防死循环）
 * - 只能调用已声明的 @Tool 方法
 * - Token 超预算自动停止
 */
@Agent(
    name = "faq-bot",
    description = "客服 FAQ 机器人，回答产品相关问题",
    autonomy = AutonomyLevel.GUIDED,
    maxIterations = 10
)
@Component
public class FaqBotAgent {

    /**
     * 系统提示。定义 Agent 的角色和行为规则。
     * 这是 ReAct 策略的核心——好的系统提示 = 好的 Agent 行为。
     */
    @SystemPrompt
    public String systemPrompt() {
        return """
            你是产品客服助手。
            回答规则：
            1. 先搜索知识库，基于搜索结果回答
            2. 如果搜索结果不相关，明确告诉用户"我没有找到相关信息"
            3. 不要编造信息
            4. 回答简洁，不超过 200 字
            """;
    }

    /**
     * 搜索知识库。
     * 实际项目中，这里会接入 Elasticsearch / 向量数据库等。
     */
    @Tool(description = "搜索产品知识库")
    public String searchKnowledgeBase(
        @Param(description = "搜索关键词") String query
    ) {
        // 模拟知识库搜索
        Map<String, String> knowledgeBase = Map.of(
            "退货", "退货政策：收到商品 7 天内可以申请退货，商品需保持原包装。",
            "配送", "配送时效：一线城市次日达，其他城市 2-3 天。",
            "价格", "当前活动：新用户首单 8 折，老用户满 200 减 30。",
            "售后", "售后服务：提供 1 年质保，质量问题免费换新。"
        );

        StringBuilder results = new StringBuilder();
        for (var entry : knowledgeBase.entrySet()) {
            if (entry.getKey().contains(query) || query.contains(entry.getKey())) {
                results.append(entry.getValue()).append("\n");
            }
        }

        return results.isEmpty()
            ? "未找到与\"" + query + "\"相关的信息"
            : results.toString();
    }

    /**
     * 查询订单状态。
     * 一个 Agent 可以有多个工具，LLM 会根据用户问题自动选择。
     */
    @Tool(description = "查询订单状态")
    public String queryOrder(
        @Param(description = "订单号") String orderId
    ) {
        // 实际项目中接入订单系统
        if (orderId.startsWith("ORD")) {
            return "订单 " + orderId + "：已发货，预计明天到达。物流单号：SF1234567890";
        }
        return "未找到订单 " + orderId;
    }
}
```

### 调用端

```java
package com.example.faq;

import com.openjiuwen.java.runtime.client.AgentClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/faq")
public class FaqController {

    private final AgentClient agentClient;

    /**
     * 同步问答。
     */
    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        // 调用 faq-bot Agent，传入用户问题
        // invoke() 会阻塞直到 Agent 返回结果
        return agentClient.invoke("faq-bot", q);
    }

    /**
     * 带上下文的对话。
     * sessionId 用于关联对话历史（记忆）。
     */
    @GetMapping("/chat")
    public String chat(
        @RequestParam String sessionId,
        @RequestParam String q
    ) {
        return agentClient.invoke("faq-bot", q, sessionId);
    }
}
```

### application.yml

```yaml
openjiuwen:
  llm:
    provider: deepseek
    api-key: ${DEEPSEEK_API_KEY}
    model: deepseek-chat
  agent:
    default-max-iterations: 5       # FAQ 场景不需要太多迭代
  memory:
    type: in-memory
    max-messages: 20
  observability:
    log-level: info
```

### 预期输入输出

**输入 1：直接 FAQ**

```
GET /api/faq/ask?q=你们的退货政策是什么
```

**预期输出**：

```json
{
  "answer": "我们的退货政策是：收到商品 7 天内可以申请退货，商品需保持原包装。如需退货，请在订单详情页点击"申请退货"按钮。"
}
```

**Agent 执行过程**（debug 日志可见）：

```
[ReAct Iteration 1]
  Thinking: 用户询问退货政策，我需要搜索知识库。
  ToolCall: searchKnowledgeBase({query="退货"})
  ToolResult: 退货政策：收到商品 7 天内可以申请退货，商品需保持原包装。
  Thinking: 找到了退货政策，我可以直接回答。
  Output: 我们的退货政策是...
```

**输入 2：查询订单**

```
GET /api/faq/ask?q=我的订单ORD20240101到哪了
```

**预期输出**：

```json
{
  "answer": "您的订单 ORD20240101 已发货，预计明天到达。物流单号：SF1234567890。"
}
```

**Agent 执行过程**：

```
[ReAct Iteration 1]
  Thinking: 用户问订单状态，提到了订单号 ORD20240101。
  ToolCall: queryOrder({orderId="ORD20240101"})
  ToolResult: 订单 ORD20240101：已发货，预计明天到达。
  Output: 您的订单 ORD20240101 已发货...
```

### 关键代码说明

| 代码 | 说明 |
|------|------|
| `@Agent(name = "faq-bot")` | 声明 Agent。name 是唯一标识，调用时用这个名字。 |
| `autonomy = GUIDED` | 受控模式：工具白名单 + Token 预算 + 迭代上限。推荐默认值。 |
| `@SystemPrompt` | 定义 Agent 的行为规则。ReAct 策略下，系统提示质量直接决定 Agent 质量。 |
| `@Tool(description = "...")` | 声明工具方法。description 是给 LLM 看的，帮助它决定何时调用。 |
| `@Param(description = "...")` | 描述参数含义。LLM 根据描述正确传参。 |
| `agentClient.invoke()` | 同步调用。阻塞直到返回。最简单的用法。 |
| `agentClient.invoke(name, input, sessionId)` | 带上下文调用。sessionId 关联对话历史。 |

---

## 四、示例 2：多步骤任务 Agent（PEV，含子 Agent）

**场景**：订单处理 Agent，需要规划多个步骤（查询订单 → 检查退款规则 → 执行退款 → 发通知），支持子 Agent 委派。

**执行策略**：PEV（Plan-Execute-Verify），AlphaStrategy 变体。

### 完整 Java 代码

```java
package com.example.order;

import com.openjiuwen.java.core.agent.Agent;
import com.openjiuwen.java.core.agent.AutonomyLevel;
import com.openjiuwen.java.core.agent.SystemPrompt;
import com.openjiuwen.java.core.tool.Tool;
import com.openjiuwen.java.core.tool.Param;
import com.openjiuwen.java.core.policy.*;
import com.openjiuwen.java.core.constraint.*;
import com.openjiuwen.java.core.approval.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 订单处理 Agent。
 *
 * 使用 PEV（Plan-Execute-Verify）策略：
 * 1. Plan:  LLM 规划执行步骤（生成 TaskGraph）
 * 2. Execute: 按拓扑排序执行步骤（可并行）
 * 3. Verify:  LLM 验证执行结果
 *
 * 如果验证失败 → 自动 Replan → 重新执行失败的步骤
 *
 * 关键特性：
 * - PlanningMode.SEMI_AUTO: Plan 可以经过开发者审批
 * - 审批门: 退款操作需要人工确认
 * - 约束: 工具白名单 + Token 预算
 * - 错误策略: 重试后降级
 */
@Agent(
    name = "order-processor",
    description = "订单处理 Agent：查询、退款、改地址、发通知",
    autonomy = AutonomyLevel.GUIDED,
    strategy = "pev"                     // 使用 PEV 策略
)
@Component
public class OrderProcessorAgent {

    @SystemPrompt
    public String systemPrompt() {
        return """
            你是订单处理助手。
            能力：查询订单、检查退款规则、执行退款、修改地址、发送通知。
            规则：
            1. 涉及金额操作时，先确认再执行
            2. 退款前必须检查退款规则
            3. 超过 5000 元的退款自动升级审批
            4. 每步操作后发通知给用户
            """;
    }

    /**
     * 查询订单。天然幂等的查询操作。
     */
    @Tool(description = "查询订单详情")
    public String queryOrder(
        @Param(description = "订单号") String orderId
    ) {
        // 接入真实订单系统
        return """
            {
              "orderId": "%s",
              "status": "DELIVERED",
              "amount": 2999.00,
              "customer": "张三",
              "orderDate": "2026-06-01"
            }
            """.formatted(orderId);
    }

    /**
     * 检查退款规则。退款前的预检查。
     */
    @Tool(description = "检查退款规则，返回是否可以退款及退款金额")
    public String checkRefundRule(
        @Param(description = "订单号") String orderId,
        @Param(description = "退款原因") String reason
    ) {
        // 业务规则：7 天内可退，超过 7 天需特殊审批
        return """
            {
              "refundable": true,
              "maxAmount": 2999.00,
              "deduction": 0,
              "reason": "订单在 7 天退货期内"
            }
            """;
    }

    /**
     * 执行退款。非幂等操作，需要审批门 + 幂等保障。
     */
    @Tool(description = "执行退款操作")
    public String processRefund(
        @Param(description = "订单号") String orderId,
        @Param(description = "退款金额") BigDecimal amount,
        @Param(description = "退款原因") String reason
    ) {
        // 接入支付系统
        return """
            {
              "refundId": "REF-20260607-001",
              "status": "SUCCESS",
              "amount": %s,
              "message": "退款成功"
            }
            """.formatted(amount);
    }

    /**
     * 发送通知。
     */
    @Tool(description = "发送通知给用户")
    public String sendNotification(
        @Param(description = "用户标识") String userId,
        @Param(description = "通知内容") String message
    ) {
        // 接入消息系统
        return "通知已发送给 " + userId + "：" + message;
    }
}
```

### Agent 策略配置（Java Config）

```java
package com.example.order;

import com.openjiuwen.java.core.*;
import com.openjiuwen.java.core.policy.*;
import com.openjiuwen.java.core.constraint.*;
import com.openjiuwen.java.core.approval.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 订单 Agent 的策略配置。
 *
 * 在 @Agent 注解基础上，通过 AgentDefinition 精确控制：
 * - 执行策略参数（并行度、超时、重试）
 * - 约束（工具白名单、Token 预算）
 * - 审批门（哪些操作需要人工确认）
 * - 错误策略（失败怎么办）
 */
@Configuration
public class OrderAgentConfig {

    @Bean
    public AgentDefinition orderProcessorDefinition() {
        return new AgentDefinition(
            // 身份
            "order-processor",
            "订单处理 Agent", "1.0.0",

            // 系统提示
            new StaticPrompt("你是订单处理助手..."),

            // 可用工具
            List.of(
                new ToolReference("queryOrder", "查询订单", null, Map.of(
                    "orderId", new ParameterSchema("orderId", "string", "订单号", true, "")
                )),
                new ToolReference("checkRefundRule", "检查退款规则", null, Map.of(
                    "orderId", new ParameterSchema("orderId", "string", "订单号", true, ""),
                    "reason", new ParameterSchema("reason", "string", "退款原因", true, "")
                )),
                new ToolReference("processRefund", "执行退款", null, Map.of(
                    "orderId", new ParameterSchema("orderId", "string", "订单号", true, ""),
                    "amount", new ParameterSchema("amount", "number", "退款金额", true, ""),
                    "reason", new ParameterSchema("reason", "string", "退款原因", true, "")
                )),
                new ToolReference("sendNotification", "发送通知", null, Map.of(
                    "userId", new ParameterSchema("userId", "string", "用户标识", true, ""),
                    "message", new ParameterSchema("message", "string", "通知内容", true, "")
                ))
            ),

            // 执行策略
            new ExecutionPolicy(
                10,                              // maxIterations: 最多 10 轮
                4,                               // maxParallelism: 最多 4 个并行步骤
                2,                               // maxRetries: 验证失败最多重试 2 次
                Duration.ofSeconds(30),          // stepTimeout: 单步 30 秒超时
                Duration.ofMinutes(5),           // totalTimeout: 总共 5 分钟
                PlanningMode.SEMI_AUTO,          // LLM 规划，开发者可审批
                VerifyMode.LLM_AUTO,             // LLM 自动验证
                true,                            // 启用检查点
                Duration.ofSeconds(5)            // 每 5 秒保存检查点
            ),

            // 约束：只允许这 4 个工具
            List.of(
                new ToolWhitelist(Set.of(
                    "queryOrder", "checkRefundRule", "processRefund", "sendNotification"
                )),
                new CostConstraint(50000, 10000) // Token 预算
            ),

            // 审批门：退款操作需要人工确认
            List.of(
                new ApprovalGate(
                    "refund-approval",                        // gateId
                    ApprovalTrigger.ON_TOOL_CALL,             // 触发条件：调用退款工具时
                    "processRefund",                          // 工具名
                    "退款操作需要人工确认",                     // 给审批人的说明
                    Duration.ofMinutes(10),                   // 等待超时
                    ApprovalAction.ESCALATE                   // 超时行为：升级
                )
            ),

            // 错误策略：重试 2 次后降级
            new DegradePolicy("queryOrder", 2)
        );
    }
}
```

### 调用端

```java
package com.example.order;

import com.openjiuwen.java.runtime.client.AgentClient;
import com.openjiuwen.java.runtime.client.AgentEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/order")
public class OrderController {

    private final AgentClient agentClient;

    /**
     * 同步调用。PEV 策略下会执行完整的 Plan → Execute → Verify 流程。
     * 如果有审批门（如退款审批），会阻塞等待人工确认。
     */
    @PostMapping("/process")
    public String process(@RequestBody OrderRequest request) {
        return agentClient.invoke("order-processor", request.getInstruction());
    }

    /**
     * 异步调用。适合耗时任务，不阻塞调用方。
     */
    @PostMapping("/submit")
    public String submit(@RequestBody OrderRequest request) {
        String taskId = agentClient.submit("order-processor", request.getInstruction());
        return "任务已提交，taskId=" + taskId;
    }

    /**
     * 查询任务状态。异步任务的轮询接口。
     */
    @GetMapping("/status/{taskId}")
    public String status(@PathVariable String taskId) {
        return agentClient.getStatus(taskId).toString();
    }
}

record OrderRequest(String instruction) {}
```

### application.yml

```yaml
openjiuwen:
  llm:
    provider: deepseek
    api-key: ${DEEPSEEK_API_KEY}
    model: deepseek-chat
  agent:
    default-strategy: pev           # 使用 PEV 策略
  checkpoint:
    enabled: true
    store: in-memory
  security:
    validation:
      enabled: true                 # 开启参数校验（退款金额检查等）
    audit:
      enabled: true                 # 开启审计日志
```

### 预期输入输出

**输入**：

```
POST /api/order/process
Body: { "instruction": "订单 ORD20240601 需要退款，客户说商品有瑕疵" }
```

**Agent 执行过程（PEV 三阶段）**：

```
=== Phase 1: Plan ===
[PlanGenerated] TaskGraph:
  Node-A: queryOrder(orderId="ORD20240601")          → TOOL_CALL
  Node-B: checkRefundRule(orderId, reason="商品瑕疵")  → TOOL_CALL
  Node-C: processRefund(orderId, amount, reason)      → TOOL_CALL（需要审批）
  Node-D: sendNotification(userId, message)           → TOOL_CALL
  Edges: A→B→C→D

=== Phase 2: Execute ===
[Step-A] queryOrder("ORD20240601")
  → 结果：订单金额 2999.00，状态 DELIVERED
[Step-B] checkRefundRule("ORD20240601", "商品瑕疵")
  → 结果：可退款，全额 2999.00
[Step-C] processRefund("ORD20240601", 2999.00, "商品瑕疵")
  ⚠ 审批门触发：退款操作需要人工确认
  → 等待审批...
  → 审批通过
  → 结果：退款成功 REF-20260607-001
[Step-D] sendNotification("张三", "您的订单 ORD20240601 退款 2999.00 已处理")
  → 结果：通知已发送

=== Phase 3: Verify ===
[Verify] LLM 检查执行结果
  → 通过：订单已退款，用户已通知，流程完整

=== Output ===
"订单 ORD20240601 退款已处理完成。退款金额 2999.00 元，退款单号 REF-20260607-001。已通知客户张三。"
```

**关键说明**：

- PEV 策略下，Agent 先规划再执行，不会"想到哪做到哪"
- 审批门拦截了退款操作，等待人工确认后才继续
- 检查点在每层执行后保存，如果中途崩溃可以从断点恢复
- Verify 阶段由 LLM 检查结果完整性，自动发现遗漏

---

## 五、示例 3：Java 8 老系统接入（SDK Remote）

**场景**：企业有一个 Java 8 + Spring Boot 2.x 的老系统，想要调用 Openjiuwen Runtime 上的 Agent，并且把老系统的业务方法暴露为 Agent 的工具。

**架构**：

```
老系统（Java 8）                   Runtime（Java 21）
┌────────────────┐               ┌────────────────┐
│ Spring Boot 2.x │   HTTP+MCP   │ Spring Boot 3.x │
│                 │ ←───────────→ │                  │
│ SDK             │               │ Runtime          │
│  ├ AgentClient  │  REST(调用)   │  ├ AgentRunner   │
│  └ MCP Server   │  MCP(工具)   │  └ MCP Client    │
│                 │               │                  │
│ @Tool 方法       │               │ LLM             │
│ (业务代码)       │               │                  │
└────────────────┘               └────────────────┘
```

### SDK 依赖（老系统 pom.xml）

```xml
<!-- Java 8 系统：只需要这两个依赖 -->
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-sdk-api</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>openjiuwen-sdk-remote</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

> **说明**：SDK 依赖只有 Jackson（JSON 序列化），兼容 Java 8+。不依赖 Spring Boot 版本。

### 业务工具定义（在老系统里）

```java
package com.legacy.system.agent;

import com.openjiuwen.java.sdk.tool.Tool;
import com.openjiuwen.java.sdk.tool.Param;
import org.springframework.stereotype.Component;

/**
 * 老系统的业务工具。
 *
 * @Tool 和 @Param 是 SDK 提供的注解（不是 Runtime 的）。
 * SDK 会扫描这些注解，通过 MCP 协议暴露给 Runtime。
 *
 * 工具方法可以直接调用老系统的 Spring Bean，
 * 因为它在老系统的 JVM 里运行。
 */
@Component
public class LegacyOrderTools {

    private final OrderService orderService;     // 老系统的 Service
    private final PaymentService paymentService;  // 老系统的 Service

    public LegacyOrderTools(OrderService orderService, PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    @Tool(description = "查询老系统订单状态")
    public String queryLegacyOrder(
        @Param(description = "订单号") String orderId
    ) {
        // 直接调用老系统的业务代码
        Order order = orderService.findOrder(orderId);
        if (order == null) {
            return "订单不存在：" + orderId;
        }
        return String.format("订单 %s，状态 %s，金额 %.2f",
            order.getId(), order.getStatus(), order.getAmount());
    }

    @Tool(description = "查询客户信息")
    public String queryCustomer(
        @Param(description = "客户ID") String customerId
    ) {
        Customer customer = orderService.findCustomer(customerId);
        return String.format("客户 %s，等级 %s，注册时间 %s",
            customer.getName(), customer.getLevel(), customer.getRegisterDate());
    }
}
```

### SDK 初始化（Spring Bean 配置）

```java
package com.legacy.system.agent;

import com.openjiuwen.java.sdk.AgentClient;
import com.openjiuwen.java.sdk.AgentEventHandler;
import com.openjiuwen.java.sdk.remote.RemoteAgentClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SDK 配置。
 * 在老系统的 Spring 配置中创建 AgentClient。
 */
@Configuration
public class OpenjiuwenSdkConfig {

    /**
     * 创建 AgentClient。
     * 指向远程的 Openjiuwen Runtime 地址。
     */
    @Bean
    public AgentClient agentClient(LegacyOrderTools legacyTools) {
        return AgentClient.builder()
            .runtimeUrl("http://openjiuwen-runtime:8080")  // Runtime 的地址
            .apiKey("legacy-system-key")                    // API Key 认证
            .connectTimeout(5000)                           // 连接超时 5 秒
            .readTimeout(60000)                             // 读取超时 60 秒
            .tool(legacyTools)                              // 注册业务工具
            .build();
    }
}
```

### 调用 Agent

```java
package com.legacy.system.controller;

import com.openjiuwen.java.sdk.AgentClient;
import com.openjiuwen.java.sdk.AgentResult;
import com.openjiuwen.java.sdk.TaskStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
public class LegacyAgentController {

    private final AgentClient agentClient;

    public LegacyAgentController(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    /**
     * 方式 1：同步调用（最简单）。
     */
    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        AgentResult result = agentClient.invoke("faq-bot", q);
        return result.getOutput();
    }

    /**
     * 方式 2：异步调用。
     */
    @GetMapping("/ask-async")
    public String askAsync(@RequestParam String q) {
        // 提交异步任务
        String taskId = agentClient.submit("faq-bot", q);

        // 轮询等待结果（实际项目中可以用 WebSocket 或回调）
        int maxWait = 60; // 最多等 60 秒
        for (int i = 0; i < maxWait; i++) {
            TaskStatus status = agentClient.getStatus(taskId);
            if (status == TaskStatus.COMPLETED) {
                AgentResult result = agentClient.await(taskId, java.time.Duration.ofSeconds(1));
                return result.getOutput();
            }
            if (status == TaskStatus.FAILED) {
                return "Agent 执行失败";
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        return "超时，taskId=" + taskId;
    }

    /**
     * 方式 3：流式调用。实时接收 Agent 的推理过程。
     */
    @GetMapping("/chat")
    public String chat(@RequestParam String q) {
        StringBuilder sb = new StringBuilder();

        agentClient.stream("faq-bot", q, new AgentEventHandler() {
            @Override
            public void onThinking(String thought) {
                sb.append("[思考] ").append(thought).append("\n");
            }

            @Override
            public void onToolCall(String tool, java.util.Map<String, Object> args) {
                sb.append("[调工具] ").append(tool).append("\n");
            }

            @Override
            public void onToolResult(String tool, Object result) {
                sb.append("[结果] ").append(tool).append(" → ").append(result).append("\n");
            }

            @Override
            public void onComplete(String output) {
                sb.append("[回答] ").append(output);
            }

            @Override
            public void onError(Exception e) {
                sb.append("[错误] ").append(e.getMessage());
            }
        });

        return sb.toString();
    }
}
```

### application.yml（老系统）

```yaml
# 老系统的 application.yml，只需要加一段
openjiuwen:
  sdk:
    runtime-url: http://openjiuwen-runtime:8080   # Runtime 地址
    api-key: ${OPENJIUWEN_API_KEY}                 # API Key
    connect-timeout: 5000
    read-timeout: 60000
    mcp:
      server-port: 9090                            # MCP Server 端口（供 Runtime 回调工具）
      thread-pool:
        core-size: 4                               # 并发工具调用的核心线程数
        max-size: 16                               # 最大线程数
        queue-capacity: 1000                       # 等待队列容量
```

### 预期输入输出

**输入**：

```
GET /api/agent/chat?q=查一下订单ORD20240601和客户CUST001的信息
```

**预期输出**：

```
[思考] 用户想查两个信息：订单和客户。我需要分别调用工具查询。
[调工具] queryLegacyOrder
[结果] queryLegacyOrder → 订单 ORD20240601，状态 DELIVERED，金额 2999.00
[调工具] queryCustomer
[结果] queryCustomer → 客户张三，等级VIP，注册时间2024-01-15
[回答] 查询结果：
- 订单 ORD20240601：已送达，金额 2999.00 元
- 客户 CUST001：张三，VIP 等级，2024 年 1 月注册
```

**关键说明**：

- `queryLegacyOrder` 和 `queryCustomer` 是老系统里的方法，通过 MCP 暴露给 Runtime
- Runtime 上的 LLM 决定调用哪些工具、传什么参数
- 工具在老系统的 JVM 里执行，可以直接访问老系统的数据库和 Service
- SDK 只依赖 Jackson（~200KB），不会和老系统的 Spring Boot 2.x 冲突

---

## 六、与 Spring AI 原生写法对比

### 场景：写一个带工具调用的 Agent

**需求**：定义一个 Agent，有一个搜索工具，用户可以对话。

#### Spring AI 原生写法

```java
// === 1. 手动配置 ChatClient ===
@Configuration
class AiConfig {
    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder
            .defaultSystem("你是客服助手")
            .defaultFunctions("searchFunction")
            .build();
    }

    // === 2. 手动注册函数 ===
    @Bean
    @Description("搜索信息")
    public Function<SearchRequest, SearchResponse> searchFunction() {
        return request -> {
            // 搜索逻辑
            return new SearchResponse("搜索结果：" + request.query());
        };
    }
}

// === 3. 定义请求/响应 record ===
record SearchRequest(String query) {}
record SearchResponse(String result) {}

// === 4. 控制器里手动调用 ===
@RestController
@RequiredArgsConstructor
class ChatController {
    private final ChatClient chatClient;

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return chatClient.prompt()
            .user(q)
            .call()
            .content();
    }
}

// === 5. application.yml ===
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
          temperature: 0.7
```

**代码量**：~45 行（不含 import），4 个文件。

**缺失能力**（需要自己实现）：
- 多轮对话记忆管理
- 工具调用审计日志
- Token 预算控制
- 检查点 / 断点恢复
- 错误重试策略
- 安全约束（工具白名单）
- 审批门（人工介入）

#### Openjiuwen 写法

```java
// === 1. 定义 Agent（一个文件）===
@Agent(name = "faq-bot")
public class FaqBot {
    @SystemPrompt
    public String prompt() { return "你是客服助手"; }

    @Tool(description = "搜索信息")
    public String search(@Param(description = "关键词") String query) {
        return "搜索结果：" + query;
    }
}

// === 2. 调用 ===
@RestController
@RequiredArgsConstructor
class Controller {
    private final AgentClient client;

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return client.invoke("faq-bot", q);
    }
}

// === 3. application.yml ===
openjiuwen:
  llm:
    provider: deepseek
    api-key: ${DEEPSEEK_API_KEY}
```

**代码量**：~20 行（不含 import），2 个文件。

**内置能力**（不需要自己实现）：
- 对话记忆（自动管理上下文窗口）
- 审计日志（每次工具调用记录）
- Token 预算（防超支）
- 检查点（断点恢复）
- 工具白名单（安全约束）
- 错误重试（可配置策略）

### 代码量对比

| 维度 | Spring AI 原生 | Openjiuwen | 减少 |
|------|---------------|------------|------|
| 文件数 | 4+ | 2 | 50% |
| 代码行数 | ~45 | ~20 | 56% |
| 手动配置 | ChatClient + Function Bean | 无 | 100% |
| 记忆管理 | 手动实现 | 内置 | - |
| 安全约束 | 手动实现 | 内置 | - |
| 检查点 | 手动实现 | 内置 | - |
| 审计日志 | 手动实现 | 内置 | - |

### 核心差异

```
Spring AI 的抽象层级：
  ChatModel → ChatClient → Function Callback
  开发者需要理解：Prompt Engineering + Function Calling + 记忆管理 + 错误处理

Openjiuwen 的抽象层级：
  @Agent + @Tool → AgentClient
  开发者需要理解：Agent 是谁 + 能用什么工具
```

**一句话总结**：Spring AI 是 LLM 调用框架，Openjiuwen 是 Agent 开发框架。Openjiuwen 基于 Spring AI 的 ChatModel，在上面加了 Agent 生命周期管理。

---

## 七、常见问题（FAQ）

### Q1: Openjiuwen 和 Spring AI / Spring AI Alibaba 是什么关系？

**关系定位**：

```
Spring AI           → LLM 调用层（ChatModel / Embedding / VectorStore）
Spring AI Alibaba   → 阿里云生态的 Spring AI 扩展（DashScope / Nacos）
Openjiuwen-Java     → Agent 开发框架（基于 Spring AI 的 ChatModel）
```

- Openjiuwen **依赖** Spring AI 作为 LLM 调用层。它不替代 Spring AI，而是在 Spring AI 之上提供 Agent 抽象。
- Openjiuwen **提供商中立**。不绑定 DashScope，所有 Spring AI 支持的 Provider 都能用。
- Openjiuwen **关注 Agent 生命周期**：规划、执行、验证、检查点、安全约束——这些是 Spring AI 不提供的。

> 可信度：[解读] [当前有效]

### Q2: 支持哪些 LLM Provider？

P1 通过 Spring AI 的 ChatModel 抽象支持以下 Provider：

| Provider | 模型示例 | 配置 provider 值 | 备注 |
|----------|---------|-------------------|------|
| DeepSeek | deepseek-chat | `deepseek` | 推荐开发用，性价比高 |
| OpenAI | gpt-4o | `openai` | 需要海外 API 访问 |
| Anthropic | claude-sonnet-4-20250514 | `anthropic` | 需要海外 API 访问 |
| 阿里云 DashScope | qwen-plus | `dashscope` | 国内推荐 |
| Google Gemini | gemini-2.0-flash | `gemini` | - |
| Ollama 本地 | llama3 | `ollama` | 离线开发用 |

所有 Provider 共享同一套 Agent 代码。切换 Provider 只需要改 `application.yml` 中的 `provider` 和 `api-key`。

> 可信度：[官方设计] [当前有效]

### Q3: Java 8 系统怎么接入？

通过 SDK Remote 模式。只需要两个 Maven 依赖（`openjiuwen-sdk-api` + `openjiuwen-sdk-remote`），总共约 200KB。

**接入步骤**：

1. 部署一个 Openjiuwen Runtime（Java 21 + Spring Boot 3.3+），可以和 Java 8 系统在不同服务器上
2. 在 Java 8 系统里引入 SDK 依赖
3. 用 `@Tool` 注解标注要暴露给 Agent 的业务方法
4. 通过 `AgentClient.invoke()` 调用 Runtime 上的 Agent

**SDK 和 Runtime 之间通过 HTTP 通信**：
- SDK → Runtime：REST 调用（Agent 执行请求）
- Runtime → SDK：MCP 协议（工具回调）

SDK 不依赖 Spring Boot 版本，不依赖任何 Java 17+ 特性。

> 可信度：[官方设计] [当前有效]

### Q4: 怎么调试 Agent？

三级调试策略：

**Level 1：日志调试**

```yaml
# application.yml
logging:
  level:
    com.openjiuwen: debug
```

开启 debug 日志后，可以看到：
- 每次 LLM 调用的完整 prompt 和 response
- 每次工具调用的参数和返回值
- Agent 的推理过程（Thinking）
- TaskGraph 的生成和执行过程

**Level 2：事件流调试**

```java
agentClient.stream("faq-bot", "test", new AgentEventHandler() {
    @Override public void onThinking(String thought) {
        System.out.println("THINKING: " + thought);   // LLM 推理过程
    }
    @Override public void onToolCall(String tool, Map<String, Object> args) {
        System.out.println("TOOL_CALL: " + tool + " " + args);  // 工具调用
    }
    @Override public void onToolResult(String tool, Object result) {
        System.out.println("TOOL_RESULT: " + tool + " → " + result);  // 工具结果
    }
    @Override public void onComplete(String output) {
        System.out.println("OUTPUT: " + output);  // 最终输出
    }
});
```

**Level 3：审批门调试**

把 `PlanningMode` 设为 `SEMI_AUTO`，在 Plan 阶段暂停 Agent，查看 TaskGraph 再决定是否继续执行。

```java
// 审批门配置
new ApprovalGate(
    "debug-gate",
    ApprovalTrigger.ON_PLAN_COMPLETE,   // Plan 完成后暂停
    null,
    "调试：查看 LLM 生成的执行计划",
    Duration.ofMinutes(30),
    ApprovalAction.APPROVE              // 超时自动通过
)
```

> 可信度：[官方设计] [当前有效]

### Q5: 怎么控制 Token 成本？

四层成本控制：

**1. 模型选择**（最大影响）

```yaml
openjiuwen:
  llm:
    model: deepseek-chat    # 比 GPT-4o 便宜 10-20 倍
    # model: gpt-4o-mini    # OpenAI 便宜版
    # model: qwen-plus      # 阿里云，国内网络快
```

**2. Token 预算**（CostConstraint）

```java
// 在 AgentDefinition 中设置
new CostConstraint(
    50000,   // maxInputTokens：单次执行最多 5 万输入 Token
    10000    // maxOutputTokens：单次执行最多 1 万输出 Token
)
```

**3. 迭代次数限制**（防死循环）

```java
@Agent(name = "faq-bot", maxIterations = 5)  // 最多 5 轮 ReAct
```

或：

```java
new ExecutionPolicy(10, ...)  // PEV 策略最多 10 轮迭代
```

**4. 系统提示精简**

系统提示越短，每轮推理的 Token 消耗越少。避免在系统提示中放大量 few-shot 示例——只在必要时使用。

**成本估算参考**：

| 场景 | 模型 | 平均 Token/请求 | 1000 次成本 |
|------|------|----------------|------------|
| 简单 FAQ | deepseek-chat | ~500 | ~0.1 元 |
| 多步骤 PEV | deepseek-chat | ~3000 | ~0.6 元 |
| 复杂 Deep Agent | gpt-4o | ~10000 | ~50 元 |

> 可信度：[解读] [当前有效] — 价格随市场变化，仅供参考

---

## 八、P1 API 参考

### 注解（Annotations）

| 注解 | 作用目标 | 说明 |
|------|---------|------|
| `@Agent` | 类 | 声明一个 Agent。必需属性：`name`。可选：`description`, `strategy`, `autonomy`, `maxIterations`, `model` |
| `@SystemPrompt` | 方法 | 定义 Agent 的系统提示。返回 String。每个 Agent 类只能有一个 |
| `@Tool` | 方法 | 声明一个工具方法。必需属性：`description`。方法签名即工具参数 |
| `@Param` | 方法参数 | 描述工具参数。属性：`description`, `required`（默认 true） |
| `@Idempotent` | 方法 | 标记工具的幂等特性。属性：`key`, `ttlSeconds`, `compensable`, `compensateTool` |

### Agent 注解属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `name` | String | （必填） | Agent 唯一标识，用于 `AgentClient.invoke(name, ...)` |
| `description` | String | `""` | Agent 功能描述（给 LLM 看） |
| `strategy` | String | `"react"` | 执行策略：`react` / `pev` |
| `autonomy` | AutonomyLevel | `GUIDED` | 自治级别：`GUIDED`（受控）/ `AUTONOMOUS`（自主） |
| `maxIterations` | int | `10` | ReAct 最大迭代次数 |
| `model` | String | `""` | 指定模型。空则用全局默认 |

### AutonomyLevel 枚举

| 值 | 含义 |
|----|------|
| `GUIDED` | 受控模式。工具白名单 + Token 预算 + 迭代上限 + 安全约束。推荐默认值 |
| `AUTONOMOUS` | 自主模式。LLM 完全自主决策。仅用于可信环境 |

### AgentClient 接口

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `invoke(name, input)` | `String` | 同步调用 Agent，返回结果文本。最简单的用法 |
| `invoke(name, input, sessionId)` | `String` | 带对话上下文的同步调用 |
| `invokeAsync(name, input)` | `CompletableFuture<String>` | 异步调用 |
| `stream(name, input, handler)` | `void` | 流式调用，通过 AgentEventHandler 实时接收事件 |
| `submit(name, input)` | `String` | 提交异步任务，返回 taskId |
| `getStatus(taskId)` | `TaskStatus` | 查询异步任务状态 |
| `await(taskId, timeout)` | `String` | 等待异步任务完成（带超时） |
| `resume(taskId, input)` | `void` | 恢复暂停的任务（如审批后） |
| `cancel(taskId)` | `void` | 取消正在执行的任务 |
| `builder()` | `AgentClientBuilder` | 创建 Builder（SDK Remote 模式） |

### AgentClientBuilder（SDK Remote 模式）

| 方法 | 说明 |
|------|------|
| `runtimeUrl(url)` | Runtime 服务地址 |
| `apiKey(key)` | API Key 认证 |
| `connectTimeout(ms)` | 连接超时（毫秒） |
| `readTimeout(ms)` | 读取超时（毫秒） |
| `tool(Object)` | 注册工具实例（包含 @Tool 方法的对象） |
| `build()` | 构建 AgentClient |

### AgentEventHandler 接口

| 方法 | 说明 |
|------|------|
| `onThinking(thought)` | Agent 推理过程 |
| `onToolCall(tool, args)` | Agent 调用工具 |
| `onToolResult(tool, result)` | 工具返回结果 |
| `onComplete(output)` | Agent 执行完成 |
| `onError(exception)` | Agent 执行出错 |

> 所有方法都有默认空实现，只需覆写关心的。

### TaskStatus 枚举

| 值 | 含义 |
|----|------|
| `CREATED` | 任务已创建 |
| `RUNNING` | 执行中 |
| `PAUSED` | 暂停（等待审批或外部输入） |
| `COMPLETED` | 成功完成 |
| `FAILED` | 执行失败 |
| `CANCELLED` | 被取消 |

### ExecutionPolicy（PEV 策略配置）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `maxIterations` | int | `10` | PEV 循环最大迭代次数 |
| `maxParallelism` | int | `4` | 同层最大并行节点数 |
| `maxRetries` | int | `2` | 验证失败最大重试次数 |
| `stepTimeout` | Duration | `30s` | 单步超时 |
| `totalTimeout` | Duration | `10m` | 总超时 |
| `planningMode` | PlanningMode | `SEMI_AUTO` | 规划模式 |
| `verifyMode` | VerifyMode | `LLM_AUTO` | 验证模式 |
| `enableCheckpoints` | boolean | `true` | 是否启用检查点 |
| `checkpointInterval` | Duration | `5s` | 检查点间隔 |

### PlanningMode 枚举

| 值 | 含义 |
|----|------|
| `AUTO` | LLM 规划，直接执行（简单任务） |
| `SEMI_AUTO` | LLM 规划，开发者可审批后执行（推荐默认） |
| `MANUAL` | 开发者预定义 TaskGraph，LLM 只填充参数（确定性流程） |

### VerifyMode 枚举

| 值 | 含义 |
|----|------|
| `LLM_AUTO` | LLM 自动验证执行结果 |
| `HUMAN_REVIEW` | 验证结果提交人工审核 |
| `SKIP` | 跳过验证（确定性流程用） |

### Constraint 体系

| 类 | 说明 |
|----|------|
| `ToolWhitelist(allowedTools)` | 工具白名单：只有列表中的工具可调用 |
| `CostConstraint(maxInput, maxOutput)` | Token 预算约束 |
| `DataScopeConstraint(allowedDomains)` | 数据范围约束（P1 预留，P2 实现） |
| `CustomConstraint(name, checker)` | 自定义约束：开发者提供检查逻辑 |

### ApprovalGate（审批门）

| 字段 | 说明 |
|------|------|
| `gateId` | 审批门唯一 ID |
| `trigger` | 触发条件：`ON_TOOL_CALL` / `ON_PLAN_COMPLETE` / `ON_VERIFY_FAIL` / `ON_SUB_AGENT` / `ALWAYS` |
| `toolName` | 触发工具名（trigger=ON_TOOL_CALL 时有效） |
| `description` | 给审批人的说明 |
| `timeout` | 等待审批超时 |
| `onTimeout` | 超时行为：`REJECT` / `APPROVE` / `ESCALATE` |

### ErrorPolicy 体系

| 类 | 说明 |
|----|------|
| `FailFastPolicy()` | 任何错误立即终止 |
| `RetryPolicy(maxRetries, backoff, multiplier)` | 重试策略：指数退避 |
| `DegradePolicy(fallbackTool, retriesBeforeDegrade)` | 降级策略：重试失败后切换备用工具 |

### 配置属性（application.yml）

| 属性路径 | 类型 | 默认值 | 说明 |
|---------|------|--------|------|
| `openjiuwen.llm.provider` | String | （必填） | LLM 提供商 |
| `openjiuwen.llm.api-key` | String | （必填） | API 密钥 |
| `openjiuwen.llm.model` | String | （必填） | 模型名称 |
| `openjiuwen.llm.base-url` | String | Provider 默认 | 自定义 API 地址 |
| `openjiuwen.llm.connect-timeout` | int | `5000` | 连接超时（ms） |
| `openjiuwen.llm.read-timeout` | int | `60000` | 读取超时（ms） |
| `openjiuwen.llm.max-input-tokens` | int | `32000` | 最大输入 Token |
| `openjiuwen.llm.max-output-tokens` | int | `4096` | 最大输出 Token |
| `openjiuwen.agent.default-max-iterations` | int | `10` | 默认最大迭代 |
| `openjiuwen.agent.default-strategy` | String | `react` | 默认策略 |
| `openjiuwen.memory.type` | String | `in-memory` | 记忆存储类型 |
| `openjiuwen.memory.max-messages` | int | `50` | 最大对话条数 |
| `openjiuwen.checkpoint.enabled` | boolean | `true` | 启用检查点 |
| `openjiuwen.checkpoint.store` | String | `in-memory` | 检查点存储 |
| `openjiuwen.security.enabled` | boolean | `false` | 安全层开关 |
| `openjiuwen.security.validation.enabled` | boolean | `true` | 参数校验开关 |
| `openjiuwen.security.audit.enabled` | boolean | `true` | 审计日志开关 |
| `openjiuwen.observability.enabled` | boolean | `true` | 可观测性开关 |
| `openjiuwen.observability.log-level` | String | `info` | 日志级别 |

### Maven 坐标

| 模块 | Java 版本 | 说明 |
|------|----------|------|
| `openjiuwen-spring-boot-starter` | 21+ | Runtime 模式，一个依赖搞定 |
| `openjiuwen-provider-deepseek` | 21+ | DeepSeek Provider |
| `openjiuwen-provider-openai` | 21+ | OpenAI Provider |
| `openjiuwen-provider-dashscope` | 21+ | 阿里云 DashScope Provider |
| `openjiuwen-sdk-api` | 8+ | SDK 纯接口，零依赖 |
| `openjiuwen-sdk-remote` | 8+ | SDK Remote 模式实现 |

---

_最后更新：2026-06-07_
_P1 范围：AlphaStrategy（PEV）+ AgentKernel + SafetyBoundary + MCP 安全 + SDK_
_设计文档：[Deep Agent 架构 v2](./2026-06-06-openjiuwen-java-architecture-v2.md) | [Alpha 变体](./2026-06-07-openjiuwen-pareto-variant-alpha-developer-control.md) | [MCP 安全层](./2026-06-07-openjiuwen-mcp-security-layer-design.md)_
