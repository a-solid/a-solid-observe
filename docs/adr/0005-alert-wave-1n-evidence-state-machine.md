# Alert 波次收敛、1:N Evidence 与告警状态机

## Status

accepted

## Context

现状告警收敛 = fingerprint 永久去重 + dedup_count 计数；evidence 与 alert 1:1（仅首次 emit 落库，dedup 不留痕）；状态机仅 FIRING/RESOLVED 两态，翻转 job 还未接线（§4.5）。问题：(1) 无法表达"告警反复发生的波次"；(2) dedup 期间证据丢失，排查看不到演变；(3) 用户无法介入处置（ack/ignore），无法压制反复告警。

## Decision

### 1. 波次（Wave）= 收敛窗口 = ttl

引入"波次"作为告警收敛的时间窗口，**与 ttl 合并为同一概念**（不再有独立 ttl）。
- 波次内同 fingerprint → dedup（dedup_count +1）+ **每次 emit 写一条新 evidence**（1:N）。
- 波次边界（ends_at 到期）→ 系统自动将告警翻为 `RESOLVED`（需把 §4.5 的 AlertResolveJob 接上 `@Scheduled`）。
- 下次同 fingerprint emit → 开一条新 FIRING alert 行 = 新波次（历史波次的 RESOLVED 行保留）。
- 默认波次按 severity 分级：CRITICAL 30min / WARNING 10min / INFO 5min。脚本 emit 时传 ttl 可覆盖。

### 2. Evidence 改 1:N

`alerts_evidence` PK 改为 snowflake BIGINT 自增，新增 `alert_id` 引用列 + 索引 `(alert_id, captured_at)`。每次 emit（含波次内 dedup 命中）INSERT 一条 evidence：本次 execution_id、nodeOutputs 快照、capture keys、emit 时间、emit 序号。evidence 与 execution 仍各自独立（execution 采样写、evidence 每次 emit 写），职责不混。

evidence 量增长由归档治理（§7.1 已预留 created_at + 索引）。

### 3. 告警状态机扩展为四态 + silence

状态：`FIRING` / `RESOLVED`（系统自动，波次到期）/ `ACKNOWLEDGED`（用户确认）/ `IGNORED`（用户忽略单条）。
- ACK/IGNORE 带备注 + 操作人 + 时间，记录到 alert 行（新增 `ack_note`/`ack_by`/`ack_at` 等列，或单列 `disposition` JSON）。
- 另设 **SILENCE 规则表 `alert_silences`**：按 fingerprint 精确 / label 维度（app/team/line）/ namespace+pipeline 维度 + 有效期（如 10 天）+ 备注 + 操作人。emit 阶段（AlertSink 落库前）查询命中 → 不建 alert（静默拦截）。作用于未来同类，区别于单条 IGNORED。

### 4. 处置 API（用户友好）

```
POST   /api/v1/namespaces/{ns}/alerts/{id}/ack          {note}  → ACKNOWLEDGED
POST   /api/v1/namespaces/{ns}/alerts/{id}/resolve      {note}  → RESOLVED（用户手动关）
POST   /api/v1/namespaces/{ns}/alerts/{id}/ignore       {note}  → IGNORED
POST   /api/v1/namespaces/{ns}/alert-silences           {match: {fingerprint|labels|ns+pipeline}, duration, note}
GET    /api/v1/namespaces/{ns}/alert-silences
DELETE /api/v1/namespaces/{ns}/alert-silences/{id}
```

## Why

- 波次=ttl：复用现有 ends_at 机制，一个概念管"收敛窗口"和"生命周期"，简化用户认知；分级默认值让用户免配。
- 1:N evidence：回应"每次告警 evidence 保留关联"，dedup 期间演变可追溯；evidence 独立于 execution 避免与采样策略耦合。
- 四态 + silence：分离"系统到期 RESOLVED"与"用户介入处置"，避免 resolved 歧义；silence 规则解决"10 天内忽略这类告警"的批量压制需求，比单条 IGNORED 更贴近运营场景。

## Consequences

- 波次内高频 emit 会让 evidence 快速增长——一期接受，靠归档（§7.1）兜底；若爆炸再加限频（如每分钟最多 1 条 evidence）。
- AlertResolveJob 必须接线（§4.5 待办项转为一期必做），否则波次边界不生效、旧行不翻 RESOLVED。
- AlertSink 落库前需查 silence 规则——silence 查询要高效（按 namespace + 匹配维度索引，建议内存缓存 + 短 TTL 刷新）。
- AlertEntity/AlertPo 增加处置字段；新增 AlertSilenceEntity/PO/Mapper + CRUD service + controller。
- Grafana 拉取的告警会出现"周期性 FIRING/RESOLVED"——这是波次设计的预期表现，代表告警反复发生，非缺陷。

## Considered Options

- **W1（ttl 即窗口，波次内不翻）**：rejected。无法表达"告警波次"语义，反复告警在 Grafana 上是一条永久 FIRING，看不出反复。
- **波次内 dedup 不写 evidence（限频）**：rejected for now。丢失演变快照，与"保留每次 evidence 关联"诉求冲突；保留为未来限频优化项。
- **evidence 合并进 execution（S2）**：rejected。execution 采样写、evidence 每次写，两者策略不同；合并会让 execution 落库策略被 evidence 需求绑架。
- **silence 用单条 IGNORED 替代**：rejected。单条 IGNORED 只作用于已存在的告警，无法压制"未来同类"；批量静默需要独立的规则实体。
