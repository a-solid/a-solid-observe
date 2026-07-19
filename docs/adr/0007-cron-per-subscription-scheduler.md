# CronSource 改为每订阅 cron 表达式调度

## Status

accepted

## Context

现状 `CronSource` 是全局单 bean，固定 `periodMillis`（默认 60s），`scheduleAtFixedRate`，所有 cron 订阅共享一个统一周期 tick，无法支持"每天 9 点""每小时第 5 分钟"等定点调度。要支持 cron 表达式必须把调度下沉到订阅级，推翻"全局单 CronSource"模型。

## Decision

### 1. cron 表达式配在 Subscription

`SubscriptionDefinition` 声明 `sourceType=CRON` + `cronExpression`（Spring `CronExpression` 6 字段：秒 分 时 日 月 周，支持 L/#/?）+ 可选 `cron name` + `concurrent`（默认 SKIP）。一个 pipeline 可被多个 cron 订阅以不同表达式触发。

### 2. 引入 CronScheduler（registry 观察者，每订阅一调度）

`CronScheduler` 是有状态组件，作为 `PipelineRegistry` 的观察者。热加载（`registry.replace`）时 diff 新旧快照的 Cron 订阅：新增/变更 → 起一个调度句柄（`ScheduledFuture`），删除 → `cancel`。每 Cron 订阅一个调度句柄（相同表达式也是独立调度）。到点产出 `TickEvent` 投给 `SourceDispatcher.onBatch`，由 matcher 按 cron name / subscriptionId 路由到订阅了该 cron 的 pipeline。

替换全局单 `CronSource` bean。

### 3. misfire 策略 = M1 忽略

worker 重启错过的 cron 不补跑，只看未来调度。与一期延时任务"重启丢失"风格一致；cron 无外部重发源，漏检靠业务容忍。

### 4. 并发 = 默认 SKIP

同一 cron 上次没跑完、下次触发到，默认跳过本次（串行不堆积）。订阅可配 `concurrent=ALLOW`。

## Why

- 配在 Subscription：与 CDC 订阅配 db/table 对称，订阅 = 触发条件 + pipeline 的抽象一致；一个 pipeline 多表达式触发灵活。
- Spring CronExpression：Spring Boot 自带无额外依赖，6 字段秒级精度，支持 L/#，能表达"每月最后一天"。
- registry 观察者：复用单一热加载链路，避免双轮询不一致；CronScheduler 只管"到点产出 TickEvent"，matcher 管"路由到哪些 pipeline"，职责清晰。
- 每订阅一调度：不引入 cron name 唯一性约束，订阅即调度单元，语义最简。
- M1 + SKIP：与项目一期"简单、容忍漏检"的整体风格一致（延时任务同策略）。

## Consequences

- 删除全局 `CronSource` bean + `WorkerProperties.cronPeriodMillis`/`cronPoolSize`；新增 `CronScheduler` 组件 + 调度线程池。
- `PipelineRegistry` 需暴露快照变化通知（观察者机制），或 `PipelineHotReloader` refresh 后显式调 `CronScheduler.sync(snapshot)`。
- `subscriptions` 表加 `cron_expression` / `cron_name` / `concurrent` 列（Cron 订阅专用，CDC/Api 订阅这些列为 null）。
- `SubscriptionDefinition.source` 运行时形态（`SourceRef`）需容纳 cron 字段；matcher 对 TickEvent 按 cron name / subscriptionId 精确匹配。
- misfire 漏检风险需在文档/CONTEXT 写明，运营知悉；二期若需补跑再上 M2。

## Considered Options

- **cron 配在 Pipeline**：rejected。违反 pipeline 只管执行逻辑的边界，且一 pipeline 只能一调度。
- **Unix cron 5 字段**：rejected。不支持秒级和 L/#，表达力不足。
- **Quartz 7 字段**：rejected。需引入 Quartz 依赖，Spring CronExpression 已够。
- **CronScheduler 独立轮询**：rejected。双轮询可能不一致，复用热加载链路更可靠。
- **按 cron name 去重调度**：rejected。引入 cron name 唯一性约束，订阅即调度单元更简。
- **misfire 补跑（M2）**：rejected for v1。需记录上次触发时间 + 重启补算，复杂度与一期风格不符。
- **默认并发 ALLOW**：rejected。cron 场景通常希望串行不堆积。

## Addendum (2026-07-19) — 路由键统一为 subscriptionId / (namespace, name)

### 旧路由模型（被取代）

§4 / Consequences 提到的 "matcher 对 TickEvent 按 cron name / subscriptionId 精确匹配" 与 `cron_name` / `mq` 列、"source name 索引" 已被废弃。

- `Snapshot` 旧有的 `subscriptionsBySource` 索引（key = `sub.source().mq()`）已删除。
- `SourceRef` 删 `mq` / `cronName` 字段；CDC 保留 db/table/opTypes。
- `TickMeta` 改为 `(subscriptionId, cronExpression, firedAt, attributes)`——删 `source` / `cronName`。
- `ApiMeta` 改为 `(subscriptionId, attributes)`——删 `source` / `apiName`。
- `SubscriptionDefinition` / `SubscriptionDto` / `SubscriptionFields` 均删 mq/cronName。
- `SubscriptionPo` 字段保留 mq/cronName（DDL 列残留策略：hibernate update 模式下空列无害）；Mapper 写入置 null。
- `validateCron` 中"mq/cronName 不变性"校验已删除（字段已删，校验无对象）。

### 新路由模型

| 事件类型 | 路由键 | 索引 |
|---|---|---|
| `CdcEvent` | (db, table) 内容路由 | `Snapshot.subscriptionsByDbTable` |
| `TickEvent` (Cron) | `meta.subscriptionId` | `Snapshot.subscriptionsById` |
| `ApiEvent` | `meta.subscriptionId` | `Snapshot.subscriptionsById` |
| `DelayedEvent` | `meta.subscriptionId` | `Snapshot.subscriptionsById` |

Tick/Api/Delayed 三种事件全对称——都用 subscriptionId 直查唯一订阅（matcher 不再校验 source/opTypes）。
ApiSource HTTP 入口需要按 (namespace, name) 反查订阅才能 wrap ApiEvent 填 subscriptionId——用第三个索引 `Snapshot.subscriptionsByNamespaceAndName`。

### HTTP 入口契约

```
POST /api/v1/events/api/{namespace}/{name}
Content-Type: application/json
Body: { ...任意业务载荷... }

→ 202 Accepted { "eventId": ... }  // 异步入队
→ 404 Not Found                     // (ns,name) 不存在或订阅 INACTIVE
```

### 删除的 mq / cronName 不变性校验

旧"mq 与 cronName 必须一致"的硬约束（B4-T3 Finding #2）已废弃——字段已删，校验无对象。

### 影响

- `DefaultSubscriptionMatcher`：删 DelayedEvent 短路、matchesNamed；加 Tick/Api/Delayed → return true 分支（snapshot 已路由到唯一订阅）。
- `CronSource.CronSub`：字段缩为 (cronExpression, concurrent)。
- `EventPaths`：TickMeta/ApiMeta 路径解析改为 `meta.subscriptionId` / `meta.cronExpression` / `meta.firedAt`。
- 测试 fixture / EndToEndFlowTest / CronSourceTest / DefaultSubscriptionMatcherTest 全部对齐新签名。

