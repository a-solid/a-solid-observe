# Event 模型改为 sealed interface（per-source 子类型）

## Status

accepted

## Context

现状单一 `Event{ meta, before, after, op, sourceTs }` 用可空字段表达所有 source 的事件：CDC 用 before/after/db/table/op=INSERT/UPDATE/DELETE；Cron/Api/Delayed 这些字段为 null。旧 `Op` 枚举把"数据变更语义"（INSERT/UPDATE/DELETE，CDC 专属）和"触发来源"（TICK/API/DELAYED）混在一起。问题：(1) 非 CDC source 用 null 字段表达"没有这个概念"，类型上无法区分；(2) 脚本作者面对 `event.after` 不知道当前 source 是否有 after；(3) 新 source 的数据只能塞 `attributes`，无正经载体。项目未上线、脚本仅一两个，破坏性可接受。

## Decision

### 1. Event 改 sealed interface，per-source 子类型

```java
// kernel.event.model
public sealed interface Event permits CdcEvent, TickEvent, ApiEvent, DelayedEvent {
    EventMeta meta();
    Instant sourceTs();
}
record CdcEvent(CdcMeta meta, Map before, Map after, CdcOp op, Instant sourceTs) implements Event {}
record TickEvent(TickMeta meta, Instant sourceTs) implements Event {}                       // 无 payload
record ApiEvent(ApiMeta meta, Map<String,Object> payload, Instant sourceTs) implements Event {}
record DelayedEvent(DelayedMeta meta, Event originalEvent, Instant sourceTs) implements Event {}
```

所有子类型统一放 `kernel.event.model`（Event 契约整体在 kernel，permits 简单）。

### 2. Op 拆分

`Op` 枚举拆为 `CdcOp{ INSERT, UPDATE, DELETE }`，仅挂 `CdcEvent`。TICK/API/DELAYED 不再是 op，而是 Event 子类型本身。`opTypes` 仅 CDC 订阅有意义；Cron/Api 订阅不配 opTypes，matcher 跳过校验。

### 3. 各子类型设计

- **CdcEvent**：含 before/after 快照 + CdcOp。`IbmMqCdcSource` 产出。
- **TickEvent**：无 payload，纯触发信号；`TickMeta` 带 cron name / cron 表达式 / 触发时刻。pipeline 脚本在节点里 `db.queryOne` 主动查 DB。`CronSource` 产出。
- **ApiEvent**：`payload`（HTTP POST body 反序列化 Map）+ `ApiMeta`（api name）。`ApiSource` 产出。
- **DelayedEvent**：嵌套 `originalEvent`（通常 CdcEvent）+ `DelayedMeta`（schedule_id / correlation_key / scheduled_at / fired_at）。语义 = 延迟重放原始事件。`InMemoryDelayedEventStore.fire` 产出，绕过 SourceDispatcher 直调 PipelineRunner。

### 4. SubscriptionMatcher pattern match 分发

`matcher.match(Event)` 内部按子类型分发：
- `CdcEvent` → 按 `meta.db()/table()` + `cdc.op()` 匹配 CDC 订阅（opTypes 生效）
- `TickEvent` → 按 `meta.source()`（cron name）匹配 Cron 订阅
- `ApiEvent` → 按 `meta.source()`（api name）匹配 Api 订阅
- `DelayedEvent` → 不走 matcher（§9.2 绕过 Source 通道）

PipelineRegistry 建多个索引：by `(db, table)` for CDC、by `source` for Cron/Api。

### 5. 脚本侧 S-implicit：订阅保证类型，零样板

订阅声明 source 类型，matcher 只把对应 Event 子类型分发给该订阅的 pipeline，引擎注入对应子类型为 `event` binding。脚本直接 `event.after.amt`（CDC 订阅下 event 即 CdcEvent），无需 `asCdc()` 转换、无需判空。类型安全由订阅层（按 SourceType 硬匹配）保证。

## Why

- sealed + per-source：类型安全，编译期区分各 source 事件，新 source 数据有正经载体（如 ApiEvent.payload）。
- Op 拆分：净化语义，数据变更（CdcOp）与触发来源（Event 子类型）分离。
- S-implicit：不引入类型转换样板，订阅层挡住不匹配 source，脚本零判空、零转换，书写最简。
- 全放 kernel：Event 契约（含子类型）整体收敛，避免跨 module sealed permits 复杂度。

## Consequences

- 现有一两个 Groovy 脚本需按新模型改写（`event.after.xxx` 在 CDC 订阅下仍成立，但 `event.meta.db()` → `event.meta().db()` 等 accessor 可能微调）。
- `SubscriptionMatcher` / `Condition.matches` / `EventPaths` / `ScriptNode` event 注入 / 各 Source 产出逻辑全要适配 sealed 类型。
- DelayedEvent 嵌套 originalEvent，序列化（execution.trigger_event / evidence）需处理嵌套结构。
- DelayedEvent 的 originalEvent 仍是内存引用（§9），序列化时需展开。
- 脚本 event 访问 DX 进一步简化（如缩短 `event.after.amt` 写法）为后续优化项，不在本轮范围。

## Considered Options

- **L2（单一 Event + 能力约束）**：rejected。不破坏脚本语法，但类型安全弱、不解决"新 source 数据载体"问题。
- **L3（保持现状仅文档化）**：rejected。不解决"抽象隔离"诉求。
- **Event 子类分散各 module**：rejected。跨 module sealed permits 复杂，收敛到 kernel 更简。
- **DelayedEvent 拷贝字段不嵌套（D2）**：rejected。丢失"延时重放"元信息层次。
- **TickEvent 带 payload**：rejected。偏离 cron"纯触发"语义，source 变重。
- **脚本侧 asCdc() 显式转换（S-bind）**：rejected。引入样板，违背"简化书写"诉求；订阅层已保证类型，无需脚本侧再转换。
