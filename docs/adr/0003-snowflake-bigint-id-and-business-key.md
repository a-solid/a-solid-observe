# 资源标识：snowflake BIGINT 主键 + (namespace, name) 业务键

## Status

accepted

## Context

历史 id 全部是 `VARCHAR(64)`/`VARCHAR(36)` UUID，既当物理主键又当业务名。引入 namespace 名字作用域后，需要区分"物理主键（聚簇/跨表引用）"和"业务键（namespace 内人类可读名）"。同时多 worker / 跨环境数据流动场景下，DB 自增 id 会冲突。

## Decision

所有资源表的标识采用**双层结构**：

- **物理主键 `id`**：`BIGINT`，由 **snowflake** 算法在应用层分配（**非 DB 自增列**），趋势递增、跨实例唯一。用于聚簇索引、跨表引用（替代 FK 的应用层引用）、日志/trace 关联。
- **业务键 `(namespace, name)`**：唯一约束。`name` 是 namespace 内人类可读业务名。

**对外暴露规则**（2026-07-19 修订）：`id` **可以对外暴露**——所有资源 DTO 都返回 `id`（作为前端持有/翻页/trace 关联的稳定句柄）。区分两类资源：

- **有业务名的资源**（pipeline / subscription / namespace）：对外 API **同时**用业务键寻址（`/api/v1/namespaces/{ns}/pipelines/{name}`），DTO 同时返回 `id` + `namespace` + `name`。这类资源仍保留 rename 能力（业务键稳定，id 内部不变）。
- **无业务名的派生资源**（alert / execution / failed_execution / alert_silence / evidence）：天生没有人类可读名，**直接用 `id` 寻址**（`/api/v1/alerts/{id}`），DTO 返回 `id`。namespace 作为必填 query 参数做软隔离（ADR-0002）。

## Why

- **snowflake 而非自增**：Sybase `IDENTITY` 与 H2 `AUTO_INCREMENT` 语义不一，snowflake 在应用层统一生成；趋势递增可作聚簇键保证插入性能；跨 worker/跨实例/跨环境不冲突，为二期分布式 worker 免除 id 迁移。
- **双层而非单一**：BIGINT 聚簇主键插入/查询性能优于 `(namespace_varchar, name_varchar)` 复合聚簇键；业务键满足名字作用域与人类可读。K8S 的 `metadata.uid` + `metadata.name` 同构。
- **id 对外（修订后）**：所有资源 DTO 返回 `id`；有业务名的资源额外返回业务键并支持业务键寻址，无业务名的派生资源用 id 寻址。内部 id 重构不影响业务键稳定性。

## Consequences

- 跨表引用（alerts.execution_id / alerts_evidence.alert_id / failed_executions.execution_id 等）全部 BIGINT，应用层在同事务内拿被引用方的 snowflake id。
- evidence 与 alert 的 1:1 / 1:N 关系（见改造3）建立在 BIGINT alert_id 上。
- snowflake 需要 worker id 分配（一期单 worker 可硬编码 workerId=1，多 worker 时引入协调）。
- trace 关联用 BIGINT id 的字符串形式，无障碍。

## Considered Options

- **纯 DB 自增 BIGINT**：rejected。Sybase/H2 语义不一 + 跨实例冲突。
- **保留 UUID**：rejected。UUID 作聚簇主键插入性能差（随机），且与"趋势递增 BIGINT 利于 B-tree"的存储偏好相悖。
- **BIGINT id 同时当业务名对外暴露**：部分 rejected。有业务名的资源仍用业务键寻址以保留 rename 能力；无业务名的派生资源（alert/execution/silence/evidence）接受用 id 寻址（2026-07-19 修订）。
