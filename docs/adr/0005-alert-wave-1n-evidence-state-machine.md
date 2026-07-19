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

> 路径风格（2026-07-19 修订）：与全仓 ADR-0002 软隔离风格一致，namespace 作必填 query 参数，而非路径段。

```
POST   /api/v1/alerts/{id}/ack?namespace={ns}          {note}  → ACKNOWLEDGED
POST   /api/v1/alerts/{id}/resolve?namespace={ns}      {note}  → RESOLVED（用户手动关）
POST   /api/v1/alerts/{id}/ignore?namespace={ns}       {note}  → IGNORED
POST   /api/v1/alert-silences?namespace={ns}           {match: {fingerprint|labels|ns+pipeline}, duration, note}
GET    /api/v1/alert-silences?namespace={ns}
DELETE /api/v1/alert-silences/{id}?namespace={ns}
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

---

## Addendum (2026-07-19): 状态机拆两维 + WavePolicy 纯函数

### 变更

§3 "告警状态机扩展为四态"被取代。原设计把**系统态**（`FIRING`/`RESOLVED`，时间驱动）与**用户处置**（`ACKNOWLEDGED`/`IGNORED`，人驱动）塞进同一个 `status` 枚举。业界对照（Alertmanager / Prometheus / Grafana）清楚显示这是两个正交维度，且无人把 ack 塞进告警状态机。混维导致：状态机守卫分裂（用户态有守卫、系统态裸 JPQL 无守卫）、`resolveJob` 只扫 `FIRING` 导致 ACK/IGNORE 告警永不到期、`RESOLVED` 词义误导（本项目是 TTL 到点、非业界"条件恢复"）。

**拆两维**：

| 维度 | 字段 | 值 | 驱动 |
|---|---|---|---|
| 系统态 | `status` | `ACTIVE` / `EXPIRED` | 时间（波次 TTL） |
| 用户处置 | `disposition` | `NONE` / `ACKNOWLEDGED` / `IGNORED` | 人（ack/ignore） |

- `ACTIVE` = 波次活跃；`EXPIRED` = 波次 TTL 到点（**非**业界条件恢复——本项目事件驱动 + TTL，问题未必解决，下次同 fingerprint emit 开新 ACTIVE 行 = 新波次）。不用 `RESOLVED`（会误导为"问题解决"）。
- disposition 与 status **完全正交**：ack/ignore 只改 `disposition` 列、不校验/不改 status。任意 status（ACTIVE 或 EXPIRED）都能被打 ack/ignore——EXPIRED 行也能 ack（本项目 EXPIRED 非条件恢复，事后标记"我看到了"有意义）。
- `resolveJob` 只看 `status=ACTIVE and ends_at<now → EXPIRED`，**不看 disposition**——ACK/IGNORE 的 ACTIVE 告警到期照常翻 EXPIRED（修掉"永不到期"）。

### 砍用户手动 close（R2）

§3 原 `POST /alerts/{id}/resolve`（用户手动关）删除。要"让告警消失"靠 **silence**（作用于未来同类）或等到期，与业界（Alertmanager/Grafana 都无手动 close）一致。处置维度只剩 ack/ignore 两个纯标记。

### WavePolicy 纯函数

波次动力学（dedup/续期/开新/到期判定）从 `DefaultAlertSink` + `AlertResolveJob` 收敛到 `domain/WavePolicy`（纯函数，零 JPA 依赖）：
- `decide(Optional<ActiveWave>, AlertSignal, now) → WaveDecision{Extend|Open}`——续期 vs 开新的决策。
- `isExpired(endsAt, now)`、`defaultTtl(Severity)`（C30/W10/I5）。
- 决策与落库分离：`WavePolicy` 只算决策，sink/resolveJob 拿结果去 JPA 落库。波次规则由此可纯单测（`WavePolicyTest`，无需 `@SpringBootTest` + H2 + `Thread.sleep`）。
- "新波次 = 新行"规则由 `decide` 显式表达（旧行 EXPIRED 后 findFirst...ACTIVE 返回空 → Open），取代过去散在 sink 的隐式 if/else。

### 不抽 AlertTransitions

原候选曾考虑抽一个合法转移表 `AlertTransitions`（系统态/用户态转移统一守卫）。拆两维后**不需要**——两个维度各自转移极简（系统态仅 ACTIVE→EXPIRED、处置态仅 NONE→ACK/IGN），维度正交后不存在"混合状态机"需要统一守卫。`AlertTransitions` 为旧模型准备，新模型下 YAGNI，不做。

### 由本 addendum 取代的旧语义

- §3 "状态：FIRING / RESOLVED / ACKNOWLEDGED / IGNORED" → 拆为 `status{ACTIVE,EXPIRED}` + `disposition{NONE,ACKNOWLEDGED,IGNORED}`。
- §3 "ACK/IGNORE 带备注 + 操作人 + 时间，记录到 alert 行（新增 ack_note/ack_by/ack_at 等列，或单列 disposition JSON）" → 落地为 ack_note/ack_by/ack_at 列 + 独立 disposition 列（status 与 disposition 两列）。
- §4 disposition API 的 `POST /alerts/{id}/resolve` → 删除（R2）。
- §"Why" "分离系统到期 RESOLVED 与用户介入处置，避免 resolved 歧义" → 拆两维彻底消解（系统态用 EXPIRED，不再与处置混）。
