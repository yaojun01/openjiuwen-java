# ADR-0001: OpenJiuwen 采用独立竞争策略（选项 α）进入 Java Agent L3 市场

## Status

Accepted

## Date

2026-05-16

## Deciders

@突突突（AI 系统架构与行业解决方案设计者）

## Context

OpenJiuwen（华为开源 Agent 框架，agent-core-java）需要确定在 Java Agent 生态中的战略定位。当前 L3 层（Agent 编排层）竞争格局：

- **Spring AI Alibaba**：已有完整 L3 能力（Graph 引擎 + 6 种 Agent + 可视化平台），60+ 贡献者，阿里云分发渠道
- **AgentScope-Java**：阿里通义实验室的 Java Agent 框架，与 SAA 融合中
- **LangChain4j Agentic**：社区驱动，11,500 Stars，Google ADK 背书
- **Koog**：JetBrains 出品，Kotlin 优先，Beta 阶段
- **Embabel**：Spring 创始人 Rod Johnson 出品，GOAP 算法

OpenJiuwen 自身状态：
- 3 GitHub Stars，60+ 贡献者（Python 版），Java 版 0.1.7
- Framework-agnostic（零 Spring 依赖）
- 已有 Pregel 图引擎、ReActAgent、WorkflowAgent、MCP 客户端（5 种传输）、6 家模型适配
- 独有：`agent_evolving/` Agent 演进系统（LLM-as-Judge + 指令/记忆/工具优化器）
- 独有：华为 InferenceAffinity 适配

企业约束：对 ~10,000 Java 开发者负责，旧有生态兼容是第一优先级。

## Decision Drivers

- **必须有可持续差异化**——3 Stars 无法靠"也做一个 Agent 框架"胜出
- **必须匹配企业开发者需求**——框架疲劳是真实痛点（微软调查 647 人）
- **必须在 SAA 补齐能力前建立心智**——SAA 补 Agent 演进预计 6-12 个月
- **必须利用华为生态作为分发渠道**——这是 SAA 不可能复制的壁垒
- **必须保持 Framework-agnostic**——SAA 架构上做不到这一点

## Considered Options

### Option α：独立竞争

- **Pros**：保持独立性，构建自有品牌，华为生态入口是独占优势
- **Cons**：资源劣势（3 Stars vs 60+ 贡献者），生态建设周期长

### Option β：差异化生态位

- **Pros**：聚焦非 Spring / 华为生态，避免正面竞争
- **Cons**：市场碎片化，长期增长天花板低

### Option γ：融入 SAA 生态

- **Pros**：借力 SAA 生态飞轮，开发成本低
- **Cons**：丧失独立性，成为阿里生态附属，华为生态冲突

## Decision

选择 **Option α（独立竞争）**。

基于三个核心支柱的判断：
1. 华为生态是同量级的分发渠道，SAA 不可能做华为适配
2. Agent 演进是 SAA 当前没有的杀手级能力，6-12 个月时间窗口
3. Framework-agnostic 有真实市场（非 Spring 用户 + 不想被阿里云绑定的企业）

**补充支柱（私有化维度）**：
4. 金融/电力行业 **80%+ 要求私有化部署**（监管硬约束），而 SAA 有能力做私有化但有商业冲突——SAA 的收入来自 DashScope/百炼 API，私有化部署切断了这个收入链。OpenJiuwen 无此冲突，可以把"私有云优先"作为核心定位
5. 华为 Cloud Stack + ModelArts + 昇腾 NPU 是中国政企私有化 AI 最强商业方案，OpenJiuwen 作为 Agent 层形成全栈私有化 AI 方案

## Rationale

**Porter 五力分析支持 α**：
- 竞争对抗强度高，但在"Framework-agnostic + Agent 演进"的白空间无人占据
- 买方（企业）议价能力强，需要企业级运维能力——这是 OpenJiuwen 可以做好的
- 替代品（Python/低代码）威胁高，但 Java 在企业后端的统治地位提供了天然护城河

**蓝海策略四步框架验证**：
- 消除：Spring 深度绑定、通用可视化、提供商列表竞赛
- 减少：Agent 模式种类、学习曲线、Stars 运营
- 提升：Agent 自动调优、Token 预算控制、多模型中立
- 创造：Agent 演进闭环、Framework-agnostic Starter、华为云原生入口

**可持续优势测试**：
- Framework-agnostic：SAA 架构上做不到 → 可持续
- 华为生态入口：商业竞争壁垒 → 可持续
- Agent 演进系统：6-12 月窗口期 → 窗口期优势，需快速建立心智

**对 10,000 开发者的叙事**：不是"替代 SAA"，是"让 SAA 编排出来的 Agent 更好"。互补定位不触发竞争防御。

## Consequences

### Positive

- 保持 OpenJiuwen 作为独立品牌的战略自主权
- 华为云成为自然分发渠道，不依赖 GitHub Stars
- Agent 演进系统如果成功建立心智，可以定义"Agent 质量"的行业认知
- Framework-agnostic 定位覆盖 SAA 触达不到的市场（Quarkus/Micronaut/华为云）
- 对 10,000 开发者可以通过"互补"叙事渐进式推广
- **私有云优先定位**利用了 SAA 的商业冲突（百炼 API 收入 vs 私有化），形成 SAA 无法消除的结构性壁垒
- 金融/电力行业的合规需求是**监管硬约束**，付费意愿强，客户生命周期长

### Negative

- 需要持续投入资源与 SAA 保持差异化领先
- 3 Stars 在公开市场上无法发出有效信号
- 可视化平台缺失，Phase 1-2 需要替代方案
- 如果 SAA 在 6-12 个月内补齐 Agent 演进，最大的差异化消失

### Risks

| 风险 | 缓解 |
|------|------|
| SAA 补齐 Agent 演进（6-12 月） | 先发优势 + 把 Agent 演进做成行业标准 + 持续迭代速度 |
| 开发者选 SAA 而不是 OpenJiuwen | 互补叙事——用 SAA 编排，用 OpenJiuwen 调优 |
| 可视化缺失影响采用 | Agent 演进评估报告作为天然可视化 + Phase 4 补齐 |
| 华为-阿里生态竞争波及 | OpenJiuwen 保持开源中立（Apache 2.0），华为适配只是其中一个 Starter |

## Implementation Notes

### Phase 1（2-3 人月）：独立框架 + 杀手锏
- openjiuwen-core 稳定版（0.1.x → 1.0）
- Agent 演进系统产品化（LLM-as-Judge + 指令/记忆/工具优化器）
- openjiuwen-spring-boot-starter（轻量模式 ~5MB）
- 3 个企业场景示例 + 开发者上手指南

### Phase 2（2-3 人月）：企业级就绪
- Token 预算控制（四级体系）
- OpenTelemetry 集成
- 华为昇腾深度适配

### Phase 3（2-3 人月）：生态建设
- Quarkus / Micronaut 适配
- A2A 协议
- Agent Store v1

### 滩头市场（修正版）
金融/电力行业中，受监管约束必须私有化部署、使用华为 Cloud Stack、且有大规模 Java 开发团队的企业。

### 私有化专项
- 默认示例配置面向内网（Ollama + 内网向量库，非 DashScope/OpenAI）
- 气隙环境工具包（不依赖外网的 Agent 工具集）
- 华为 Cloud Stack 全栈方案（昇腾 NPU + ModelArts + OpenJiuwen）
- 合规审计内置（Agent 调用日志、Token 消耗、决策链追溯）

## Related Decisions

- （待写）ADR-0002: OpenJiuwen Spring Boot Starter 技术选型
- （待写）ADR-0003: Agent 演进系统架构设计
- （待写）ADR-0004: 华为昇腾适配策略

## References

- [Java Agent 生态深度调研报告](2026-05-16-java-agent-ecosystem-deep-research.md)
- [Brainstorming 方法论拓展文档](2026-05-16-java-agent-brainstorm.md)
- [竞争格局分析（Porter 五力 + 蓝海策略）](2026-05-16-java-agent-competitive-landscape.md)
- [Spring AI Alibaba 官方博客](https://java2ai.com/en/blog/saa-agentscope-announcement) [最高/官方]
- [AgentScope-Java GitHub](https://github.com/agentscope-ai/agentscope-java) [最高/官方]
- [微软 Java AI 开发者调查](https://devblogs.microsoft.com/java/the-state-of-coding-the-future-with-java-and-ai/) [最高/官方]
