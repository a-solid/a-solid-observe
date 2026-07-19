# Subscription 多 Pipeline 扇出 + 延时动作模型重设计

**Status**: draft（待用户 review；延时章节为开放点，见 §9）
**Date**: 2026-07-19
**Author**: brainstorming session

## 背景

当前 subscription 与 pipeline 是 **1:1 硬绑定**：

- `subscriptions` 表有 `pipeline_id BIGINT NOT NULL` + `pipeline_version INT NOT NULL`。
- `Subscription` / `SubscriptionDefinition` 持有单个 `pipelineId` + `pipelineVersion`。
- `DefaultSubscriptionMatcher.tryMatch` 末尾取单 pipeline + 版本校验，返回 `MatchedSubscription(sub, pipeline)`。
- 反向（一个 pipeline 被多个 subscription 触发）已支持（ADR-0007：一个 pipeline 多 cron 表达式靠多个订阅实现），但**正向（一个 subscription 触发多个 pipeline）不支持**。

同时，延时动作当前存在**两个入口**，且都不可用/不可维护：

- **入口 A（脚本运行时 `delayed.schedule(...)`）**：`delayed` binding **从未注入脚本上下文**，是死代码/预留端口，脚本根本调不到。
- **入口 B（subscription 配置 `actionType=SCHEDULE/CANCEL`）**：配置 + handler 接线完整，但**当前没有任何 subscription 实际配置过 SCHEDULE/CANCEL**（项目处于开发设计阶段）。

项目处于开发设计阶段、无线上数据、无向后兼容包袱，适合一次性把 subscription↔pipeline 关系和延时模型设计干净。

## 目标

1. **同源事件扇出**：一个 CDC/Cron/Api 订阅进来，可同时触发多个 pipeline，避免为每个 pipeline 重复配置指向同一 source 的订阅。
2. **延时动作模型重设计**：废弃两个旧入口，统一为一种可维护、schedule/cancel 配对、声明式的延时模型。

## 非目标（YAGNI）

- 不同 pipeline 不同版本绑定（统一跟 currentVersion）。
- 不同 pipeline 不同 fieldFilter（fieldFilter 不同应拆成不同订阅）。
- 同 subscription 的多 pipeline 严格串行（一期顺序提交、并发执行即可）。
- 延时任务持久化（沿用 `InMemoryDelayedEventStore`，重启丢失，与一期风格一致）。
- 跨 namespace 绑定（软隔离铁律禁止）。

## 关键决策

| # | 决策 | 备注 |
|---|---|---|
| D1 | 一个 subscription 可绑多个 pipeline（`pipelineIds` 列表） | 同源扇出 |
| D2 | subscription 不再绑版本，pipeline 各跑自己 currentVersion | 删 `pipeline_version` |
| D3 | `pipeline_ids` 用 JSON 字符串列存（无索引需求，读出即用） | vs 侧表，YAGNI |
| D4 | 扇出 = 分发线程内层 for 顺序提交到 runnerPool，N 个 pipeline 并发执行（受 runnerMax 限制） | "串行"指 for 循环提交，非严格顺序 |
| D5 | 每个 (sub, pipeline) 独立 executionId / trace / 失败隔离 | 一个 pipeline 挂不影响同 sub 的其他 pipeline |
| D6 | 延时统一走 **DELAYED 订阅类型**；废弃入口 A（脚本 delayed binding）和入口 B（actionType=SCHEDULE/CANCEL） | schedule/cancel 在一个订阅内配对 |
| D7 | DELAYED 订阅：`schedule` 必填、`cancel` 可选 | 允许只挂起到期复查 |
| D8 | DELAYED 订阅的 schedule/cancel 各自声明触发条件（when），可指向不同事件（如 INSERT vs UPDATE） | 解决触发源不同 |
| D9 | correlationKeyPath 在 schedule 和 cancel 各自声明；若都配，引擎校验一致（不一致 warn） | schedule/cancel 配对纽带 |

## 设计

### 1. 数据模型（DB）

`observe-config/V1__init.sql` 的 `subscriptions` 表：

**删除列**：
- `pipeline_id BIGINT NOT NULL`
- `pipeline_version INT NOT NULL`
- `action_type VARCHAR NOT NULL DEFAULT 'RUN'`
- `schedule_delay_ms BIGINT`
- `schedule_correlation_key_path VARCHAR`

**删除约束**：
- `ck_sub_action_type`（action_type CHECK）
- `ck_sub_schedule`（action_type 与 schedule 字段联动的 CHECK）

**新增列**：
- `type VARCHAR NOT NULL DEFAULT 'FANOUT'` —— 订阅类型，`FANOUT` / `DELAYED`
- `pipeline_ids VARCHAR(4096) NOT NULL` —— JSON 数组字符串，snowflake id 列表（如 `[1001,1002]`）。FANOUT 和 DELAYED 都用此列声明到点/命中时跑哪些 pipeline。

**新增约束**：
- `ck_sub_type CHECK (type IN ('FANOUT','DELAYED'))`

**DELAYED 订阅专用列**（FANOUT 订阅这些列为 null）：
- `schedule_when LONG VARCHAR` —— schedule 面触发条件 JSON（sourceType/db/table/opTypes/fieldFilter 或 cron/api）
- `schedule_correlation_key_path VARCHAR`
- `schedule_delay_ms BIGINT`
- `cancel_when LONG VARCHAR` —— cancel 面触发条件 JSON（可选）
- `cancel_correlation_key_path VARCHAR` —— 可选

**保留不变**：`namespace`、`name`、`status`、source 相关列（`mq`/`topic`/`db`/`table_name`/`op_types`/`source_type`/`field_filter`）、cron 相关列（`cron_expression`/`cron_name`/`concurrent`）。

> **注**：现有 source 相关列（db/table/op_types/source_type/field_filter/cron_*）原本描述的是 FANOUT 订阅的 `when`。引入 `type` 后，FANOUT 订阅继续用这些列作为 `when`；DELAYED 订阅则改用 `schedule_when`/`cancel_when` 两个独立的 JSON 条件块（因为这些列是单组，无法表达 schedule/cancel 两组条件）。这一点在 §9 标注为待 review 的 schema 复杂度问题。

### 2. 配置态领域模型（observe-config）

**`SubscriptionDefinition`**（record）：

删除字段：`pipelineId`、`pipelineVersion`、`actionType`、`scheduleDelay`、`scheduleCorrelationKeyPath`、`ActionType` 内嵌枚举。

新增字段：
```java
SubscriptionType type;            // FANOUT | DELAYED
List<Long> pipelineIds;           // 非空，>=1
// DELAYED 专用（type==DELAYED 时必填）：
TriggerWhen scheduleWhen;         // 触发条件
String scheduleCorrelationKeyPath;
Duration scheduleDelay;
TriggerWhen cancelWhen;           // 可选
String cancelCorrelationKeyPath;  // 可选，若 cancelWhen 非空则建议与 schedule 对齐
```

`TriggerWhen` 是一个值对象，封装 sourceType + (db/table/opTypes) 或 (cronExpression/cronName) 或 (apiName) + 可选 fieldFilter。复用现有 SourceRef 的字段语义。

**校验规则**（`SubscriptionCrudService`）：
- `pipelineIds` 非空、每个 id 存在、且 `namespace` 与 subscription 一致（软隔离铁律）。
- `type==FANOUT`：`scheduleWhen` 等 DELAYED 字段必须为 null。
- `type==DELAYED`：`scheduleWhen`/`scheduleCorrelationKeyPath`/`scheduleDelay` 必填；`cancelWhen` 可选。
- `cancelWhen` 非空时，若 `cancelCorrelationKeyPath` 与 `scheduleCorrelationKeyPath` 不一致 → warn 日志（不拒绝，留 review 空间）。

### 3. 运行态领域模型（observe-pipeline）

**`Subscription`**（record）：同步配置态，删 `pipelineId`/`pipelineVersion`/`action`，加 `type` + `pipelineIds` + DELAYED 字段。

**`Action` sealed interface：整体删除。** 只剩 `Run` 无存在意义（FANOUT 默认就是 run）。

**`MatchedSubscription` → `Matched` sealed interface**：matcher 的返回类型需同时表达"立即扇出"和"延时动作"两种结果，故从单一 record 改为 sealed interface：
- 旧：`MatchedSubscription(Subscription sub, Pipeline pipeline)`
- 新：
  ```java
  sealed interface Matched permits MatchedFanout, MatchedDelay {}
  record MatchedFanout(Subscription sub, List<Pipeline> pipelines) implements Matched {}
  record MatchedDelay(Subscription sub, DelayOp op, List<Pipeline> pipelines, String correlationKey) implements Matched {}
  enum DelayOp { SCHEDULE, CANCEL }
  ```
  `MatchedFanout.pipelines` 为按 `pipelineIds` 解析 + currentVersion 校验后的可执行 pipeline 列表；`MatchedDelay.pipelines` 对 SCHEDULE 为到点重放目标，对 CANCEL 为 null。具体形态见 §4 与开放点 3。

### 4. 匹配与扇出

**`DefaultSubscriptionMatcher`**：

`match(Event)` 内部按 subscription `type` 分流：

- **FANOUT 订阅**：`matchesSource` + `passesFieldFilter`（逻辑不变）→ 若通过，解析 `sub.pipelineIds()` 得 `List<Pipeline>`（过滤掉 null/未发布的）→ 返回 `MatchedSubscription(sub, pipelines)`。
- **DELAYED 订阅**：
  - 若 `scheduleWhen` 命中 → 产出 `MatchedDelay(sub, DelayOp.SCHEDULE, pipelines, keyFromSchedulePath)`
  - 若 `cancelWhen` 命中 → 产出 `MatchedDelay(sub, DelayOp.CANCEL, null, keyFromCancelPath)`
  - schedule/cancel 互斥（一个事件只可能命中其中一个 when）。

> matcher 返回类型需扩展为能同时表达"立即扇出"和"延时动作"两种结果。具体：返回 `List<Matched>`，`Matched` 是一个 sealed interface（`MatchedFanout` / `MatchedDelay`），dispatcher 按 subtype 分发。细节在实施计划里定。

**`SourceDispatcher.dispatch`**：
```
for (Matched m : matcher.match(event)) {
    switch (m) {
        case MatchedFanout f -> {
            for (Pipeline p : f.pipelines()) {       // 内层 for：顺序提交、并发执行
                submitFanout(f.subscription(), p, event);
            }
        }
        case MatchedDelay d -> {
            if (d.op() == SCHEDULE) {
                store.schedule(d.correlationKey(),
                    () -> fireDelay(d.subscription(), event, d.pipelines(), d.correlationKey()),
                    d.subscription().scheduleDelay());
            } else { // CANCEL
                store.cancel(d.correlationKey());
            }
        }
    }
}
```
`fireDelay` 即 §6 的 fire 闭包（构造 DelayedEvent + 扇出 pipelines）。

`submitFanout`：与现有 `submitMatched` 等价（`inFlight.acquire` → `runnerPool.execute(runner.run)` → `finally release`），反压/在途信号量语义不变。

### 5. 失败隔离与执行记录

- 每个 (subscription, pipeline) 调 `runner.run(pipeline, event, subscriptionId)` 一次，独立 executionId（snowflake）、独立 ExecutionMeta、独立 trace。
- 某 pipeline 抛异常 → `PipelineRunner` 现有 try/catch 写 `failed_executions`，不影响同 sub 其他 pipeline。
- `runAndRelease` 每任务独立 try/catch + finally release，天然隔离。
- 一条 CDC 事件扇出 3 个 pipeline → 3 行 `executions`（subscription_id 相同，pipeline_id 不同）。
- 跨表引用（`alerts.execution_id` / `failed_executions.execution_id`）保持 BIGINT 单值，无需改 ADR-0003/0005。

### 6. 延时动作（DELAYED 订阅）

**SCHEDULE 流程**（在 dispatcher 的 DELAYED 分支内，替代被删的 `DelayedActionHandler`）：
1. matcher 命中 DELAYED 订阅的 `scheduleWhen` → 产出 `MatchedDelay(sub, SCHEDULE, pipelines, key)`，key 由 `EventPaths.get(event, scheduleCorrelationKeyPath)` 提取。
2. dispatcher 调 `DelayedEventStore.schedule(key, fireTask, scheduleDelay)`，`fireTask` 是一个闭包，捕获 `(sub, originalEvent, pipelines, key)`。
3. 到点 fireTask 执行（在 DelayedEventStore 的调度线程里）：构造 `DelayedEvent(originalEvent, ...)` → 对 `pipelines` 内层 for 扇出 `runner.run(pipeline, delayedEvent, subId)`（与 FANOUT 同款扇出，每个 pipeline 独立隔离）。注意：DelayedEvent 绕过 matcher 直调 runner（ADR-0006 §9.2 语义保留）。

**CANCEL 流程**（dispatcher 的 DELAYED 分支内）：
1. matcher 命中 DELAYED 订阅的 `cancelWhen` → 产出 `MatchedDelay(sub, CANCEL, null, key)`，key 由 `EventPaths.get(event, cancelCorrelationKeyPath)` 提取。
2. dispatcher 调 `DelayedEventStore.cancel(key)` —— 按 key 取消挂起的复查任务（幂等，无则 no-op + warn）。

> fire 闭包的逻辑原本在 `DelayedActionHandler.fire/wrapAsDelayed`，handler 删除后这部分代码迁移到 dispatcher（或一个新的轻量 `DelayFireAdapter`，实施时定）。`wrapAsDelayed` 的 DelayedMeta 构造逻辑保留。

**保留**：`DelayedEventStore` 端口、`InMemoryDelayedEventStore` 实现、`DelayedEvent` 事件模型、`EventPaths` key 提取。这些原本为入口 B 设计，DELAYED 订阅复用，语义一致（到点重放原事件给绑定的 pipeline）。

**废弃**：
- `DelayedActionHandler` 类（整个删除）—— 其职责（按 subscription action 调度/取消）由 matcher + dispatcher 的 DELAYED 分支取代。
- `Action` sealed interface（含 `Action.Schedule`/`Cancel`/`Run`）—— 全删。
- `SubscriptionDefinition.ActionType` 枚举 —— 全删。
- 入口 A 的脚本 `delayed` binding —— 不接线（本就没接），彻底放弃。

### 7. PipelineRegistryLoader

- 读 `SubscriptionPo` 时，按 `type` 构造对应运行态 `Subscription`。
- `pipelineIds` 反序列化（JSON → `List<Long>`）。
- 至少一个 pipeline id 在 `pipelines` map 中存在才纳入订阅；全部缺失则跳过该订阅。
- DELAYED 订阅：解析 `scheduleWhen`/`cancelWhen` JSON 为 `TriggerWhen`。

### 8. 测试策略

**单元**：
- `DefaultSubscriptionMatcher`：FANOUT 多 pipeline 解析（部分缺失 → 只返回存在的；全缺失 → 跳过 sub）；DELAYED schedule/cancel 分别命中；schedule/cancel 互斥。
- `PipelineRegistryLoader`：`pipelineIds` 反序列化、type 分流、至少一个存在才纳入。
- `SubscriptionCrudService`：`pipelineIds` 非空/跨 namespace 拒绝/id 存在性；FANOUT 不允许带 DELAYED 字段；DELAYED schedule 必填。
- `InMemoryDelayedEventStore`：schedule/cancel（已有测试，DELAYED 订阅接线后补 fire 闭包测试）。

**集成**：
- `SourceDispatcher`：一条 CDC 事件扇出到 2 pipeline → 2 行 executions、独立 executionId、一个脚本抛异常不影响另一个（失败隔离）。
- DELAYED 订阅：schedule 命中 → 挂起；cancel 命中（同 key）→ 取消；到期未取消 → 重放给 pipelineIds。
- 反压回归：`SourceDispatcherBackpressureTest` 保持绿。

**回归**：`CronSourceTest`、`DefaultAlertSinkIntegrationTest`、`DefaultSubscriptionMatcherTest` 保持绿。

### 9. 开放点 / 待 review

本 spec 经多轮迭代，以下点用户明确要求**写完 spec 后继续研究是否 OK**：

1. **DELAYED 作为独立 subscription 类型是否最佳**：当前把延时抽象成 `type=DELAYED` 的订阅，schedule/cancel 作为订阅内的两个 when 块。替代方案：在 pipeline 上声明延时配置、或独立 delayedRule 实体。需要 review 这个抽象层次是否正确。
2. **schema 复杂度**：引入 `type` 后，FANOUT 用原有 source 列当 when，DELAYED 用新增的 `schedule_when`/`cancel_when` JSON 列。两组条件表达方式不对称（一组是扁平列，一组是 JSON），可能需要统一为"when 全部 JSON 化"或重新设计列结构。
3. **`Matched` sealed interface 的具体形态**：matcher 返回 `MatchedFanout`/`MatchedDelay` 的 sealed 类型是初步设计，实施时可能调整。
4. **cancelCorrelationKeyPath 与 schedule 不一致**：当前定为 warn，可能需要改为拒绝（配置期强校验）。
5. **现有 source 相关列（db/table/op_types/source_type/field_filter/cron_*）的归属**：引入 DELAYED 后这些列名义上只服务 FANOUT 的 when，但 schema 上仍对所有 type 开放，需要明确是否加约束。

## 改动清单（影响面）

**observe-config**：
- DDL `subscriptions` 表（§1）
- `SubscriptionDefinition`（§2）、`SubscriptionPo`、`SubscriptionMapper`、`SubscriptionCrudService`、`SubscriptionController`、`SubscriptionDto`
- `PipelineRegistryLoader`（§7）

**observe-pipeline**：
- `Subscription` 运行态（§3）
- 删 `Action` sealed interface、`DelayedActionHandler`
- `MatchedSubscription` → `Matched` sealed（§3/§4）
- `DefaultSubscriptionMatcher`（§4）
- `SourceDispatcher`（§4）
- 保留 `DelayedEventStore`/`InMemoryDelayedEventStore`/`DelayedEvent`

**observe-bootstrap**：
- 装配：移除 `DelayedActionHandler` bean；matcher/dispatcher 新签名接线。

## 参考 ADR

- ADR-0001（配置态/运行态分层）：本改造保持两层分离，配置态 `SubscriptionDefinition`/运行态 `Subscription` 同步演进。
- ADR-0002（namespace 软隔离）：`pipelineIds` 校验必带 namespace 一致性；matcher/dispatcher 不引入跨 namespace 路径。
- ADR-0003（snowflake id）：`pipeline_ids` 存 BIGINT id 列表；execution_id 等引用不变。
- ADR-0006（Event sealed interface）：DELAYED 订阅到点重放复用 `DelayedEvent`，绕过 matcher 直调 runner 的语义保留。
- ADR-0007（每订阅 cron 调度）：DELAYED 订阅若 schedule.when 是 cron 类型，复用 CronSource 调度（待 review 是否支持）。
