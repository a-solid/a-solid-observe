# Subscription 多 Pipeline 扇出

**Status**: draft（待用户 review）
**Date**: 2026-07-19
**Author**: brainstorming session
**相关**: 延时动作模型重设计见 `2026-07-19-subscription-delayed-redesign.md`（draft，单独推进）

## 背景

当前 subscription 与 pipeline 是 **1:1 硬绑定**：

- `subscriptions` 表有 `pipeline_id BIGINT NOT NULL` + `pipeline_version INT NOT NULL`。
- `Subscription` / `SubscriptionDefinition` 持有单个 `pipelineId` + `pipelineVersion`。
- `DefaultSubscriptionMatcher.tryMatch` 末尾取单 pipeline + 版本校验，返回 `MatchedSubscription(sub, pipeline)`。
- 反向（一个 pipeline 被多个 subscription 触发）已支持（ADR-0007：一个 pipeline 多 cron 表达式靠多个订阅实现），但**正向（一个 subscription 触发多个 pipeline）不支持**。

本 spec 只解决正向扇出。延时动作模型（`actionType=SCHEDULE/CANCEL`、脚本 `delayed` binding）的重新设计**不在本 spec 范围**，见延时 spec。

## 目标

**同源事件扇出**：一个 CDC/Cron/Api 订阅进来，可同时触发多个 pipeline，避免为每个 pipeline 重复配置指向同一 source 的订阅。

## 非目标（YAGNI）

- 不同 pipeline 不同版本绑定（统一跟 currentVersion）。
- 不同 pipeline 不同 fieldFilter（fieldFilter 不同应拆成不同订阅）。
- 同 subscription 的多 pipeline 严格串行（一期顺序提交、并发执行即可）。
- 跨 namespace 绑定（软隔离铁律禁止）。
- **延时动作重设计**（`actionType`/`Action`/`DelayedActionHandler` 维持现状不动，由延时 spec 处理）。

## 关键决策

| # | 决策 | 备注 |
|---|---|---|
| D1 | 一个 subscription 可绑多个 pipeline（`pipelineIds` 列表） | 同源扇出 |
| D2 | subscription 不再绑版本，pipeline 各跑自己 currentVersion | 删 `pipeline_version` |
| D3 | `pipeline_ids` 用 JSON 字符串列存（无索引需求，读出即用） | vs 侧表，YAGNI |
| D4 | 扇出 = 分发线程内层 for 顺序提交到 runnerPool，N 个 pipeline 并发执行（受 runnerMax 限制） | "串行"指 for 循环提交，非严格顺序 |
| D5 | 每个 (sub, pipeline) 独立 executionId / trace / 失败隔离 | 一个 pipeline 挂不影响同 sub 的其他 pipeline |

## 设计

### 1. 数据模型（DB）

`observe-config/V1__init.sql` 的 `subscriptions` 表：

**删除列**：
- `pipeline_id BIGINT NOT NULL`
- `pipeline_version INT NOT NULL`

**新增列**：
- `pipeline_ids VARCHAR(4096) NOT NULL` —— JSON 数组字符串，snowflake id 列表（如 `[1001,1002]`）。

**保留不变**：`namespace`、`name`、`status`、source 相关列（`mq`/`topic`/`db`/`table_name`/`op_types`/`source_type`/`field_filter`）、cron 相关列（`cron_expression`/`cron_name`/`concurrent`）、以及延时相关列（`action_type`/`schedule_delay_ms`/`schedule_correlation_key_path` + 相关 CHECK）——这些归延时 spec，本 spec 不动。

> `pipeline_ids` 无索引需求：运行时由 `PipelineRegistryLoader` 读出反序列化为 `List<Long>`，不存在"按 pipeline_id 反查 subscription"的查询路径。

### 2. 配置态领域模型（observe-config）

**`SubscriptionDefinition`**（record）：

- 删除字段：`pipelineId`、`pipelineVersion`。
- 新增字段：`List<Long> pipelineIds`（非空，>=1）。
- **保留** `ActionType` / `actionType` / `scheduleDelay` / `scheduleCorrelationKeyPath`（延时 spec 处理，本 spec 不动）。

**校验规则**（`SubscriptionCrudService`）：
- `pipelineIds` 非空、每个 id 存在、且 `namespace` 与 subscription 一致（软隔离铁律）。

### 3. 运行态领域模型（observe-pipeline）

**`Subscription`**（record）：同步配置态，删 `pipelineId`/`pipelineVersion`，加 `pipelineIds`。**保留** `action` 字段（延时 spec 处理）。

**`MatchedSubscription`** 结构变化：
- 旧：`MatchedSubscription(Subscription sub, Pipeline pipeline)`
- 新：`MatchedSubscription(Subscription sub, List<Pipeline> pipelines)`（按 `pipelineIds` 解析 + currentVersion 校验后得到的可执行 pipeline 列表）

### 4. 匹配与扇出

**`DefaultSubscriptionMatcher.tryMatch`**：
- `matchesSource` / `passesFieldFilter` 逻辑**不变**（source/opTypes/fieldFilter 仍按 subscription 单份判定）。
- 末尾"取单 pipeline + 版本校验"改为：遍历 `sub.pipelineIds()`，对每个 id `snapshot.pipelineById(id)`，过滤掉 null（pipeline 不存在/未发布）的，得 `List<Pipeline>`。
- 返回 `new MatchedSubscription(sub, pipelines)`（空列表则 matcher 跳过该 sub，等价当前 `pipeline==null` 返回 null）。

**`SourceDispatcher.dispatch`** 扇出：
```
for (MatchedSubscription m : matcher.match(event)) {     // 外层：subscription 维度
    for (Pipeline p : m.pipelines()) {                   // 内层：pipeline 维度（顺序提交、并发执行）
        submitMatched(m.subscription(), p, event);       // 每个 (sub, pipeline) 独立提交
    }
}
```
- `submitMatched` 签名从 `(MatchedSubscription, event)` 改为 `(subscription, pipeline, event)`，内部逻辑（`delayedActionHandler.handle` / `inFlight` / `runnerPool`）**不变**——延时 handler 仍按现状在 per-(sub,pipeline) 上下文里被调用（其扇出后的语义问题留给延时 spec 解决）。
- 反压 / 在途信号量 `inFlight`：每个 (sub,pipeline) 各占一个 permit，语义不变。

### 5. 失败隔离与执行记录

- 每个 (subscription, pipeline) 调 `runner.run(pipeline, event, subscriptionId)` 一次，独立 executionId（snowflake）、独立 ExecutionMeta、独立 trace。
- 某 pipeline 抛异常 → `PipelineRunner` 现有 try/catch 写 `failed_executions`，**不影响同 sub 其他 pipeline**。
- `runAndRelease` 每任务独立 try/catch + finally release，天然隔离。
- 一条 CDC 事件扇出 3 个 pipeline → 3 行 `executions`（subscription_id 相同，pipeline_id 不同）。
- 跨表引用（`alerts.execution_id` / `failed_executions.execution_id`）保持 BIGINT 单值，无需改 ADR-0003/0005。

### 6. PipelineRegistryLoader

- `subPo.pipelineId` 单值检查 → `subPo.pipelineIds` 列表里**至少一个** id 在 `pipelines` map 里才纳入订阅；全部缺失则跳过该订阅。
- `toPipelineSubscription` 构造 `Subscription` 时填 `pipelineIds`（JSON 反序列化）。

### 7. 测试策略

**单元**：
- `DefaultSubscriptionMatcher`：多 pipeline 解析（部分 pipeline 不存在/未发布 → 只返回存在的；全不存在 → 跳过 sub）。
- `PipelineRegistryLoader`：`pipelineIds` 反序列化、至少一个 pipeline 存在才纳入订阅。
- `SubscriptionCrudService`：`pipelineIds` 非空校验、跨 namespace 绑定拒绝、id 存在性校验。

**集成**：
- `SourceDispatcher`：一条 CDC 事件扇出到 2 个 pipeline → 2 行 executions、独立 executionId、其中一个脚本抛异常不影响另一个（失败隔离）。
- 反压回归：`SourceDispatcherBackpressureTest` 保持绿。

**回归**：`CronSourceTest`、`DefaultAlertSinkIntegrationTest`、`DefaultSubscriptionMatcherTest`、`JpaExecutionRecorderTest` 保持绿。

## 改动清单（影响面）

**observe-config**：
- DDL `subscriptions` 表：删 `pipeline_id`/`pipeline_version`，加 `pipeline_ids`（§1）。
- `SubscriptionDefinition`（§2）、`SubscriptionPo`、`SubscriptionMapper`、`SubscriptionCrudService`、`SubscriptionController`、`SubscriptionDto`。
- `PipelineRegistryLoader`（§6）。

**observe-pipeline**：
- `Subscription` 运行态（§3）。
- `MatchedSubscription` → `(sub, List<Pipeline>)`（§3）。
- `DefaultSubscriptionMatcher.tryMatch`（§4）。
- `SourceDispatcher.dispatch` + `submitMatched` 签名（§4）。

**observe-bootstrap**：
- 无新增 bean；`SourceDispatcher` 构造签名若变则同步装配。

## 参考 ADR

- ADR-0001（配置态/运行态分层）：保持两层分离，配置态 `SubscriptionDefinition`/运行态 `Subscription` 同步演进。
- ADR-0002（namespace 软隔离）：`pipelineIds` 校验必带 namespace 一致性；matcher/dispatcher 不引入跨 namespace 路径。
- ADR-0003（snowflake id）：`pipeline_ids` 存 BIGINT id 列表；execution_id 等引用不变。
- ADR-0006（Event sealed interface）：扇出不改变 Event 模型，matcher 仍按子类型分发。
- ADR-0007（每订阅 cron 调度）：扇出与 cron 调度正交，一个 subscription 的 N 个 pipeline 共享同一 cron 触发。
