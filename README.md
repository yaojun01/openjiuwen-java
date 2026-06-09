# OpenJiuwen Java — Enterprise Deep Agent Framework

企业级 Deep Agent 框架，基于 Java 21 + Spring Boot 3.3 + Spring AI。

## 架构概览

```
                    openjiuwen-core (纯 Java 21，零依赖)
                           |
          +----------------+----------------+
          |                |                |
     runtime-core     (shared by all runtime modules)
          |
    +-----+------+------------+
    |            |            |
runtime-alpha  runtime-beta  openjiuwen-spring-boot-starter
  (PEV策略)    (LLM自治策略)   (自动装配)

    openjiuwen-sdk
    +----------+----------+
    |                     |
  sdk-api            sdk-remote
  (Java 8 纯接口)    (HTTP/MCP 远程客户端)
```

## 核心概念

### AgentKernel — 7 个系统调用

所有策略（Alpha/Beta）通过 AgentKernel 的 7 个系统调用与底层交互：

| 系统调用 | 用途 |
|---------|------|
| `think` | 调用 LLM 推理 |
| `invokeTool` | 执行工具（@Tool / MCP） |
| `observe` | 观察执行状态 |
| `saveCheckpoint` | 保存检查点 |
| `restoreCheckpoint` | 恢复检查点 |
| `yield` | 让出执行权（等待审批等） |
| `emit` | 发布事件 |

### 双策略模型

| 策略 | 模式 | 适用场景 |
|------|------|---------|
| **Alpha (PEV)** | Plan → Execute → Verify | 开发者可控的确定性流程 |
| **Beta (LLM-autonomous)** | LLM 自主决策循环 | 高自主度场景 |

### 四级自主度

`GUIDED` → `ASSISTED` → `META` → `AUTONOMOUS`

## 快速开始

### 1. 定义 Agent

```java
@Agent(
    name = "weather-agent",
    description = "天气查询 Agent",
    autonomyLevel = AutonomyLevel.GUIDED,
    systemPrompt = "你是天气查询助手。"
)
public class WeatherAgent {

    @Tool("查询当前天气")
    public String getWeather(@Param("城市") String city) {
        return "晴天，25°C";
    }
}
```

### 2. 调用 Agent

```java
@Autowired AgentClient agentClient;

// 一行调用
String result = agentClient.invoke("weather-agent", "北京天气怎么样？").block();
```

### 3. Deep Agent（多 Agent 编排）

```java
@Agent(name = "order-deep-agent", autonomyLevel = AutonomyLevel.META, ...)
public class OrderDeepAgent {
    @Tool("调用库存检查子 Agent")
    public String checkInventory(...) { ... }

    @Tool("调用风控评估子 Agent")
    public String assessRisk(...) { ... }
}
```

## 模块说明

| 模块 | 说明 |
|------|------|
| `openjiuwen-core` | 纯接口/模型层，零依赖（sealed interface + record） |
| `runtime-core` | AgentKernel 实现 + SafetyBoundary + 策略路由 |
| `runtime-alpha` | PEV 策略（Planner + PregelExecutor + Verifier） |
| `runtime-beta` | LLM 自治策略（Guardrail + Criteria + Reflection） |
| `openjiuwen-spring-boot-starter` | Spring Boot 自动装配（@Agent/@Tool 注解扫描） |
| `sdk-api` | Java 8 纯接口（AgentClient、ToolDefinition、SPI） |
| `sdk-remote` | HTTP/MCP 远程客户端（桥接 Java 8 企业系统） |
| `openjiuwen-examples` | 使用示例（ReAct / PEV / Deep Agent / 金融财富助手） |

## 构建

```bash
# Java 21 required
mvn clean compile

# 运行测试
mvn test -pl openjiuwen-examples

# 完整构建（含 Beta 策略）
mvn compile -P all
```

## 文档

- [架构设计文档](docs/architecture/) — 运行时架构、Deep Agent、SDK、安全层等
- [竞品分析](docs/research/) — AgentScope/Spring AI 对比分析

## License

Private — All rights reserved.
