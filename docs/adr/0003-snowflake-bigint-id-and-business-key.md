# 资源标识：snowflake BIGINT 主键 + (namespace, name) 业务键

## Status

accepted

## Context

历史 id 全部是 `VARCHAR(64)`/`VARCHAR(36)` UUID，既当物理主键又当业务名。引入 namespace 名字作用域后，需要区分"物理主键（聚簇/跨表引用）"和"业务键（namespace 内人类可读名）"。同时多 worker / 跨环境数据流动场景下，DB 自增 id 会冲突。

## Decision

所有资源表的标识采用**双层结构**：

- **物理主键 `id`**：`BIGINT`，由 **snowflake** 算法在应用层分配（**非 DB 自增列**），趋势递增、跨实例唯一。用于聚簇索引、跨表引用（替代 FK 的应用层引用）、日志/trace 关联。
- **业务键 `(namespace, name)`**：唯一约束。`name` 是 namespace 内人类可读业务名。对外 API 用业务键寻址（`/api/v1/namespaces/{ns}/pipelines/{name}`）。

BIGINT `id` 不对外暴露；对外只认业务键。

## Why

- **snowflake 而非自增**：Sybase `IDENTITY` 与 H2 `AUTO_INCREMENT` 语义不一，snowflake 在应用层统一生成；趋势递增可作聚簇键保证插入性能；跨 worker/跨实例/跨环境不冲突，为二期分布式 worker 免除 id 迁移。
- **双层而非单一**：BIGINT 聚簇主键插入/查询性能优于 `(namespace_varchar, name_varchar)` 复合聚簇键；业务键满足名字作用域与人类可读。K8S 的 `metadata.uid` + `metadata.name` 同构。
- **id 不对外**：业务键稳定（可 rename），内部 id 重构无 API 破坏。

## Consequences

- 跨表引用（alerts.execution_id / alerts_evidence.alert_id / failed_executions.execution_id 等）全部 BIGINT，应用层在同事务内拿被引用方的 snowflake id。
- evidence 与 alert 的 1:1 / 1:N 关系（见改造3）建立在 BIGINT alert_id 上。
- snowflake 需要 worker id 分配（一期单 worker 可硬编码 workerId=1，多 worker 时引入协调）。
- trace 关联用 BIGINT id 的字符串形式，无障碍。

## Considered Options

- **纯 DB 自增 BIGINT**：rejected。Sybase/H2 语义不一 + 跨实例冲突。
- **保留 UUID**：rejected。UUID 作聚簇主键插入性能差（随机），且与"趋势递增 BIGINT 利于 B-tree"的存储偏好相悖。
- **BIGINT id 同时当业务名对外暴露**：rejected。失去 rename 能力，且与 namespace 名字作用域职责重叠。
