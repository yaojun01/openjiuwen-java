# Runtime Beta — P2 DRAFT

**此模块默认不编译。** 它属于 P2 范围，仅在 `-P all` profile 下包含：

```bash
# P1 默认（不含此模块）
mvn compile

# 完整构建（含此模块）
mvn compile -P all
```

## 状态

此模块中的代码是 P2 设计的草稿（draft），源于 GEPA 50 轮推演中的 Beta 路径设计。
在 P1 阶段不会被运行或测试。

## 文件标记

所有 Java 文件开头均标记了 `P2 DRAFT` 注释头，以区别于 P1 的生产代码。

## 参考资料

- [Beta 变体架构设计文档](../../docs/architecture/05-beta-llm-autonomous-orchestration.md)
- [ADR-P1/P2 拆分](../../../memory/adr-p1-p2-feature-split.md) (via auto-memory)
