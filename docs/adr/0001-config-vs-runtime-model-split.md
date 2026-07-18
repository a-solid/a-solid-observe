# 配置态与运行态模型分层（不合并）

## Status

accepted

## Context

Pipeline 和 Subscription 都存在两套领域模型：配置态（`observe-config`，对应 DB 表，可版本化/热加载/含审计字段）和运行态（`observe-pipeline`，从配置态反序列化/聚合而来，执行引擎消费）。早期讨论过合并成单一模型以省掉一层映射。

## Decision

**保留两层**，不合并。配置态统一用 `*Definition` 命名（`PipelineDefinition`/`PipelineVersion`/`SubscriptionDefinition`），运行态裸名（`Pipeline`/`Subscription`）。`PipelineRegistryLoader` 做桥接。

## Why

- 配置态承载版本草稿/发布两态、`definition_hash` 编译缓存、审计字段、30s 热加载轮询；运行态热路径对象不该携带这些。
- `SourceRef`/`Action` sealed interface 这类执行语义聚合只对运行态有意义，配置态是扁平 DB 列。
- 两层让配置领域（CRUD/校验/发布）与执行领域（事务/脚本/告警）各自演进，符合 §6.2 依赖方向强约束。
- 代价（两套模型 + mapper 长期维护）可接受：mapper 是机械的字段搬运，热路径只碰运行态。

## Considered Options

- **合并为单一模型**：rejected。会让热路径对象臃肿，且版本/草稿/审计语义污染执行领域。
