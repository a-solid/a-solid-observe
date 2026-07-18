# a-solid-observe 改造分批执行设计

**日期**：2026-07-18
**状态**：Proposed
**关联**：`CONTEXT.md`、`docs/adr/0001`–`0007`、`docs/改造.md`、`docs/observe-platform-design.md`

## 1. 背景与目标

上一轮 grilling 已就 `docs/改造.md` 的 7 个改造点逐一确认设计，沉淀为 `CONTEXT.md`（领域术语表）与 7 条 ADR（`docs/adr/0001`–`0007`）。本文档不重新讨论设计本身，而是把这些改造点**按代码耦合关系合并成分批执行方案**，作为后续实施计划（writing-plans）的输入。

**目标**：把 7 个改造点组织成 5 个批次，每批内部高内聚、批间单向依赖，每个批次结束时仓库可编译、测试绿、可启动。

## 2. 约束（已与用户确认）

1. **每批结束（批次切换点）必须可编译 + 测试绿 + 可启动**。批次内部不强求每次提交都编译通过，只要批末正确即可。
2. **id 改 snowflake BIGINT 与 namespace 合并为首批**（α 决策），作为横切地基一次改透。
3. **每批只补/改该批直接动到的组件的测试**，不批量补现状测试空洞。
4. **namespace 需要 CRUD API**（不止初始化数据）；RBAC 仍为远期不做。
5. **CronSource 在 B3 用占位固定周期产 TickEvent**，B4 才换 CronScheduler + 表达式（避免 B3 反向依赖 B4）。
6. **CronScheduler 用显式调用**（`PipelineHotReloader.refresh()` 末尾调 `cronScheduler.sync(snapshot)`），不用事件订阅模式。
7. **silence `match` 用统一 JSON 列**（silence 量小、AlertSink 内存遍历匹配，不需要按匹配维度建索引）。

## 3. 改造点与代码耦合分析

依赖关系（决定批次边界）：

```
改造0(命名重构 + 延时端口) ──┐
改造1(namespace + id) ───────┼─→ 都动 Subscription/PO/Mapper/Loader，合并
                              │
改造4(Event sealed) ─────────→ 必须带动 matcher/Condition/Snapshot/各Source，整批
                              │   且 4 必须在 5 之前（TickEvent 先于 CronScheduler）
改造5(Cron) ─────────────────→ 依赖改造4 的 TickEvent
                              │
改造2(labels 投影列) ─┐
改造3(Alert 重构)    ├→ 都动 alerts 表/AlertPo/AlertSink，合并
                     │
改造6(demo 清理) ───── 独立，零依赖
```

## 4. 批次总览与依赖顺序

```
B1 (暖身清理)           独立，零依赖
  │
  ▼
B2 (标识层地基)         namespace + id + 业务键 + 命名重构 + 延时端口
  │                    ← 所有后续批次的横切地基
  ├──────────────┐
  ▼              ▼
B3 (Event)     B5 (Alert 重构)    B3 与 B5 互相独立
  │
  ▼
B4 (Cron)                ← 依赖 B3 的 TickEvent
```

**建议执行顺序**：B1 → B2 → B3 → B4 → B5。

- B2 是地基，必须最先（B1 之后）。
- B3 与 B5 互相独立（触不同 module），建议 B3 先于 B5（先稳定底层 kernel 再改上层 alerting）。
- B4 必须在 B3 之后（TickEvent 先于 CronScheduler）。

## 5. 批次详情

### 5.1 B1 — 暖身清理

**范围**：删 `observe-bootstrap/.../demo/` 整个包（`DemoMain` + `DemoPipelineFactory` + 内部 Noop 类）。

**动机**：demo 自包含、无外部引用、Noop 都是 DemoMain 私有类。零功能风险，作暖身批验证构建+测试流程。

**动到的文件**：
- 删 `DemoMain.java`、`DemoPipelineFactory.java`
- 检查 `EndToEndFlowTest` 是否覆盖"无 Spring 手动装配跑 pipeline"冒烟；未覆盖则补轻量测试（符合 ADR-0001 Conventions）

**验收**：
- `mvn compile` 通过
- `mvn test` 全绿
- `grep -r bootstrap.demo` 无残留

**风险**：极低。

---

### 5.2 B2 — 标识层地基（namespace + id + 命名 + 延时端口）

最大、最关键的一批。把"标识与隔离"地基一次改透。

#### B2.1 namespace 引入（ADR-0002）
- 新增表 `namespaces`（显式资源）：`id BIGINT PK, name, display_name, created_at, updated_at`，唯一约束 `(name)`。
- **namespace CRUD API**：`POST/GET/PUT/DELETE /api/v1/namespaces`（约束 4）。RBAC 不做（远期）。
- 所有资源表加 `namespace` 列（NOT NULL）：`pipelines` / `pipeline_versions` / `subscriptions` / `alerts` / `alerts_evidence` / `executions` / `failed_executions`。
- alerts/executions/failed_executions 的 namespace denormalize（从触发 pipeline 继承）。
- 应用层铁律：所有 Repository 查询方法签名加 `namespace` 参数；漏带即串数据（review checklist 固化）。

#### B2.2 id 改 snowflake BIGINT（ADR-0003）
- 新增 `SnowflakeIdGenerator`（kernel 或 bootstrap util）：workerId 一期硬编码（单 worker），多 worker 时再协调。
- 所有表 PK VARCHAR → BIGINT（snowflake 生成，非自增）：`pipelines.id` / `subscriptions.id` / `alerts.id` / `executions.id` / `failed_executions.id`。
- `pipeline_versions` 复合键 `(pipeline_id, version)` → pipeline_id 改 BIGINT。
- `alerts_evidence.alert_id`（现 1:1 PK）→ BIGINT（B5 改 1:N 时再调 PK 结构，B2 先改类型）。
- 业务键唯一约束 `(namespace, name)` 加到 `pipelines` / `subscriptions`。
- 对外 API 用 `(namespace, name)`，BIGINT id 不暴露：controlplane 路径改 `/api/v1/namespaces/{ns}/pipelines/{name}`。

#### B2.3 命名重构 + 延时端口（改造0 / ADR-0001）
- `config.domain.Subscription` → **`SubscriptionDefinition`**（对齐 PipelineDefinition），全链路重命名（PO/Mapper/Repository/Controller/DTO/Loader）。
- 抽 `DelayedEventStore` 端口（observe-pipeline.application 或 application.delayed），纯调度原语：`schedule(correlationKey, task)` / `cancel(correlationKey)` / `pendingCount()` / `shutdown()`。`InMemoryDelayedEventStore implements DelayedEventStore`，`DelayedActionHandler` 只依赖端口（修层间耦合）。

#### B2.4 Flyway 迁移
- 建 namespaces 表、所有资源表加 namespace + 改 id BIGINT + 加业务键唯一约束。
- H2 开发库（`ddl-auto: update`）+ Flyway 双轨；注意 `pipeline_versions` 复合键类型变更。

**动到的文件（主要）**：
- kernel：新增 `SnowflakeIdGenerator`、可选 `NamespaceId`/`BusinessKey` 值对象
- config：`Subscription`→`SubscriptionDefinition` 重命名；所有 PO 加 namespace、id 改 BIGINT；Mapper + Repository（加 namespace 参数）；`PipelineRegistryLoader` namespace 透传
- pipeline：`Subscription` 运行态加 namespace；抽 `DelayedEventStore` 端口；`PipelineRegistry.Snapshot` 索引加 namespace 维度
- alerting：`AlertPo`/`EvidencePo` 加 namespace、id BIGINT；Mapper
- controlplane：所有 controller 路径改 `/namespaces/{ns}/.../{name}`；DTO 加/去字段；namespace CRUD controller
- bootstrap：装配 `SnowflakeIdGenerator`、`DelayedEventStore` 接线；namespace CRUD service
- db/migration：新 Flyway 脚本

**验收**：
- `mvn compile` + `mvn test` 绿
- EndToEndFlow：建 namespace → 建 pipeline（业务键寻址）→ 触发 → 告警落库带 namespace
- 两个 namespace 同名 pipeline 不冲突（名字作用域验证）
- 延时事件经端口工作（DelayedActionHandler 不再直 import InMemoryDelayedEventStore）
- 所有 Repository 查询带 namespace（grep 校验无裸查）

**风险**：
- 最大横切批：所有表 + 所有 PO/Mapper/Repository + controlplane 全部路由。批内可分若干提交（namespace / id / 命名 / 端口），不必每提交绿，批末整体绿。
- id 类型变更连带：跨表引用（alert.execution_id 等）全部 BIGINT，应用层同事务内拿 snowflake id 串链，逐一核对。
- H2 vs Sybase DDL 差异：BIGINT 两库都有，唯一约束/索引语法需测。

---

### 5.3 B3 — Event 模型（sealed interface）

把 Event 从单一 record 改成 sealed interface per-source。**必须整批消化 matcher/Condition/Snapshot/各 Source 连带改造**（否则编译不过）。

#### B3.1 kernel：Event sealed + 子类型 + CdcOp
```
kernel.event.model:
  sealed interface Event permits CdcEvent, TickEvent, ApiEvent, DelayedEvent {
      EventMeta meta(); Instant sourceTs();
  }
  record CdcEvent(CdcMeta meta, Map before, Map after, CdcOp op, Instant sourceTs)
  record TickEvent(TickMeta meta, Instant sourceTs)              // 无 payload
  record ApiEvent(ApiMeta meta, Map<String,Object> payload, Instant sourceTs)
  record DelayedEvent(DelayedMeta meta, Event originalEvent, Instant sourceTs)
  enum CdcOp { INSERT, UPDATE, DELETE }                          // 取代旧 Op 的 CDC 部分
```
- 删旧 `Op` 枚举（INSERT/UPDATE/DELETE/TICK/API/DELAYED）——TICK/API/DELAYED 不再是 op，由 Event 子类型表达。
- 各 Meta（CdcMeta/TickMeta/ApiMeta/DelayedMeta）替代旧 EventMeta；CdcMeta 保留 db/table/source/sourceType/attributes。

#### B3.2 matcher pattern-match 分发
`DefaultSubscriptionMatcher.match(Event)` 按子类型分发：
- `case CdcEvent cdc` → 按 `cdc.meta().db()/table()` + `cdc.op()` 匹配 CDC 订阅（opTypes 生效）
- `case TickEvent tick` → 按 `tick.meta().source()` 匹配 Cron 订阅
- `case ApiEvent api` → 按 `api.meta().source()` 匹配 Api 订阅
- `case DelayedEvent` → 不走 matcher（§9.2 绕过）

#### B3.3 PipelineRegistry.Snapshot 多索引
现只有 `subscriptionsByDbTable`。改为：
- `subscriptionsByCdcDbTable`（CDC 订阅，按 db/table）
- `subscriptionsBySource`（Cron/Api 订阅，按 source name）
- `Snapshot.subscriptionsFor(Event)` 按子类型查对应索引

#### B3.4 Condition.matches 适配 sealed
`Condition.Fields.resolve(Event, path)` 改为 pattern-match：CDC 路径（`before.`/`after.`/`op`/`meta.db`）只在 CdcEvent 上解析；其他子类型相关路径各自解析。fieldFilter 仅 CDC 订阅有意义（与 opTypes 一致）。

#### B3.5 各 Source 产出对应子类型
- `IbmMqCdcSource` + `IbmMqXmlParser` → 产 `CdcEvent`（op 用 CdcOp）
- `CronSource` → 产 `TickEvent`（**B3 阶段先用占位固定周期，B4 再换 CronScheduler + 表达式**，约束 5）
- `ApiSource` → 产 `ApiEvent`（payload 从 HTTP body）
- `InMemoryDelayedEventStore.fire` → 产 `DelayedEvent`（嵌套 originalEvent）

#### B3.6 脚本侧 S-implicit
`ScriptNode` 注入 `event` binding——订阅保证类型，脚本直接 `event.after.amt`。ScriptNode 不改类型转换逻辑（订阅层已挡）。DryRunService / ExecutionRecorder 的 trigger_event 序列化处理 sealed + DelayedEvent 嵌套。

#### B3.7 现有脚本 + 测试改写
- 测试里的 Groovy 脚本按新模型改写（CDC 订阅下 `event.after.xxx` 仍成立）。
- `DefaultSubscriptionMatcherTest` / `ConditionTest` / `SourceDispatcherTest` / `InMemoryDelayedEventStoreTest` 全部适配 sealed。

**验收**：
- `mvn compile` + `mvn test` 绿
- CDC 触发：CdcEvent 经 matcher 匹配 CDC 订阅 → pipeline 跑通
- Api 触发：ApiEvent 经 matcher 匹配 Api 订阅（payload 可读）
- 延时：DelayedEvent 嵌套 originalEvent，fire 后 pipeline 能读 original
- Cron 占位 tick 仍工作（TickEvent 产出）

**风险**：
- sealed 序列化：Jackson 对 sealed interface + record 需配 `@JsonTypeInfo(use=NAME, property="@type")` + `@JsonSubTypes`（trigger_event、evidence）。DelayedEvent 嵌套 Event 是递归 sealed，序列化要测。
- B3 不能依赖 B4：CronSource 用占位固定周期产 TickEvent（约束 5）。
- Op 枚举删除的连带：`executions.trigger_type`/`failed_executions` 存 op name，DB 数据语义需对齐；一期数据可清空。

---

### 5.4 B4 — Cron 调度（每订阅 cron 表达式）

依赖 B3 的 TickEvent。把"全局单 CronSource 固定周期"换成"每订阅 cron 表达式 + CronScheduler 观察者"。

#### B4.1 配置层：Cron 订阅字段
- `subscriptions` 表加列（Cron 订阅专用，CDC/Api 这些列为 null）：`cron_expression`（Spring `CronExpression` 6 字段）/ `cron_name` / `concurrent`（SKIP 默认 / ALLOW）。
- `SubscriptionDefinition` + `SourceRef` 运行态容纳 cron 字段。
- `SubscriptionCrudService` / Controller / DTO 处理 cron 字段；保存时校验 `cronExpression` 可被 `CronExpression.parse()` 解析。

#### B4.2 CronScheduler 组件（registry 观察者，每订阅一调度）
- 新增 `observe-pipeline.application.CronScheduler`（或 infrastructure）：内部 `ConcurrentMap<subscriptionId, ScheduledFuture>` + 调度线程池。
- **观察者触发 = 显式调用**（约束 6）：`PipelineHotReloader.refresh()` 调 `registry.replace(snapshot)` 后，显式调 `cronScheduler.sync(snapshot)`：
  - 新增/变更 → `CronExpression.parse(expr)` + `scheduler.schedule(tickTask)`（按表达式下一次触发时刻）
  - 删除 → `future.cancel(false)`
- 到点产 TickEvent 投 SourceDispatcher：`dispatcher.onBatch(List.of(tickEvent))`，matcher 按 cron name/subscriptionId 路由。
- concurrent=SKIP：per-subscription `AtomicBoolean running`，上次没跑完则跳过本次。
- misfire M1：重启后只看未来调度，不补跑（CronScheduler 启动重建调度，不计算错过的）。

#### B4.3 替换全局 CronSource
- 删 `WorkerConfig.cronSource` bean + `WorkerProperties.cronPeriodMillis`/`cronPoolSize`。
- 新增 `CronScheduler` bean + 专用调度线程池（`observe.worker.cron-scheduler-pool-size`）。
- `WorkerShutdown` 加 `cronScheduler.shutdown()`。

#### B4.4 显式调用触发点
`PipelineHotReloader.refresh()` 末尾插 `cronScheduler.sync(registry.snapshot())`，必须在 `registry.replace` 之后同一 refresh 调用内 sync。

**验收**：
- `mvn compile` + `mvn test` 绿
- 配置 cron 订阅（表达式如 `*/30 * * * * ?`）→ 到点触发对应 pipeline
- 热加载：新增/改表达式/删 cron 订阅 → 调度句柄随之起停（日志可见）
- concurrent=SKIP：构造慢 pipeline + 高频 cron → 不堆积并发
- worker 重启 → cron 调度重建（错过的忽略）

**风险**：
- CronScheduler 与热加载的竞态：`sync` 在 hot reloader 线程、fire 在调度线程池——`ConcurrentMap` + `cancel` 需线程安全；`AtomicBoolean` per-sub 防 SKIP 竞态。
- Spring CronExpression 时区：默认 JVM 时区，一期用 worker 时钟，不引入时区配置。
- 观察者触发点：必须在 `registry.replace` 之后同一 refresh 内 sync。

---

### 5.5 B5 — Alert 重构（波次 + 1:N evidence + 状态机 + labels 投影列 + silence）

B3/B4 之后做（与 B3 互相独立，放最后让底层先稳）。一次性改透 alerts 表簇。

#### B5.1 alerts 表结构变更（合并改造2 + 改造3）
- **去 `pipeline_labels` 列**（ADR-0004）：emit 时把路由/分类 label 固化进 `alerts.labels`。
- **加 label 投影列**（ADR-0004）：`label_app` / `label_team` / `label_line`，普通 B-tree 索引，应用层 emit 时从 labels JSON 投影填充。
- **波次 = ttl**（ADR-0005）：默认 C30/W10/I5 min，脚本传 ttl 可覆盖。`ends_at = last_seen_at + 波次`。
- **状态机扩展**（ADR-0005）：`status` 加 `ACKNOWLEDGED` / `IGNORED`。加处置列 `disposition_note` / `disposition_by` / `disposition_at`（ack/ignore 共用）。
- **AlertResolveJob 接线**（ADR-0005 + §4.5 待办转必做）：`@Scheduled` 每分钟扫 `status='FIRING' AND ends_at < now()` → 翻 `RESOLVED`（波次边界）。波次生效的硬前提。

#### B5.2 alerts_evidence 改 1:N（ADR-0005）
- PK 从 `alert_id`（1:1）改为独立 `id BIGINT` snowflake；加 `alert_id BIGINT` 引用列 + 索引 `(alert_id, captured_at)`。
- **每次 emit（含波次内 dedup）INSERT 一条 evidence**：本次 execution_id、nodeOutputs 快照、capture keys、emit 时间、emit 序号。
- `AlertRepository.updateEmit`（dedup 命中）不再只更新计数——同时 INSERT 新 evidence 挂同 alert_id。

#### B5.3 silence 规则表（ADR-0005）
- 新增 `alert_silences` 表：`id BIGINT PK, namespace, match_type(fingerprint|labels|ns_pipeline), match(JSON 统一列), expires_at, note, created_by, created_at` + 索引 `(namespace, expires_at)`。
- **match 用统一 JSON 列**（约束 7）：fingerprint 精确 / label KV / ns+pipeline 三种维度都存 JSON，应用层按 match_type 解析。无需按匹配维度建索引。
- AlertSink 落库前查 silence：按 namespace 拉活跃规则后内存遍历匹配 → 命中不建 alert。silence 规则加内存缓存 + 短 TTL 刷新（热路径性能）。

#### B5.4 处置 API（ADR-0005）
```
POST   /api/v1/namespaces/{ns}/alerts/{id}/ack        {note}  → ACKNOWLEDGED
POST   /api/v1/namespaces/{ns}/alerts/{id}/resolve    {note}  → RESOLVED（用户手动关）
POST   /api/v1/namespaces/{ns}/alerts/{id}/ignore     {note}  → IGNORED
POST   /api/v1/namespaces/{ns}/alert-silences         {match, duration, note}
GET    /api/v1/namespaces/{ns}/alert-silences
DELETE /api/v1/namespaces/{ns}/alert-silences/{id}
```

#### B5.5 AlertEntity/AlertPo/EvidencePo/Mapper/DefaultAlertSink 改造
- `AlertPo`：去 pipeline_labels、加 label_* 三列、加 disposition_* 列、status 枚举扩展。
- `EvidencePo`：PK 改 id、加 alert_id 引用列。
- `DefaultAlertSink.persist`：fingerprint 计算 + 波次 ends_at + silence 查询 + dedup 时 INSERT evidence + 投影 label 列。
- 新增 `AlertSilenceEntity/PO/Mapper/Repository` + `AlertSilenceService` + controller。

**验收**：
- `mvn compile` + `mvn test` 绿
- 波次：同 fingerprint 5min 内多次 emit → dedup +1 + 多条 evidence；波次到 → 系统翻 RESOLVED；再 emit → 新 FIRING 行
- 处置：ack/ignore/resolve API 带备注生效，状态正确
- silence：建规则后，命中维度的 emit 不建 alert
- label 查询：`WHERE label_team='X'` 走索引
- AlertResolveJob `@Scheduled` 接线，过期 FIRING 翻 RESOLVED

**风险**：
- 大批之一：alerts + alerts_evidence + silence 表 + 状态机 + 处置 API。批内按 `表结构/波次` → `evidence 1:N` → `状态机+处置API` → `silence` 子推进，批末整体绿。
- evidence 量增长：波次内高频 emit 写多条 evidence——一期接受，靠归档兜底；测试中发现爆炸再加限频。
- silence 热路径查询：AlertSink 每次 emit 查 silence，必须内存缓存，否则拖慢 pipeline 事务。
- AlertResolveJob 接线：波次生效硬前提，必须本批完成。

## 6. 跨批次注意事项

- **每批只补动到的测试**（约束 3）：不批量补现状空洞。
- **批次切换点必须绿**：B2→B3、B3→B4、B4→B5 切换时仓库可编译可跑可测试绿。
- **CONTEXT.md / ADR 已是设计权威**：本 spec 只管"怎么分批/怎么执行"，设计细节以 ADR 为准；若实施中发现 ADR 需调整，先改 ADR 再改代码。
- ** Flyway 与 H2 `ddl-auto: update` 双轨**：开发期 H2，迁移脚本按模块放 `db/migration/<module>/`，注意 Sybase 兼容（无 JSON 索引、无 FK）。

## 7. 不在本轮范围（远期/二期）

- RBAC / namespace 权限模型（ADR-0002 远期）
- namespace 环境隔离（靠 profile/多实例）
- evidence 限频、数据归档策略（§7.1）
- 分布式 worker / snowflake workerId 协调（ADR-0003 二期）
- EAV label 索引表（ADR-0004 二期演进项）
- misfire 补跑（M2，ADR-0007 二期）
- 死信手动重试 API、延时任务持久化（文档 §7.3）
