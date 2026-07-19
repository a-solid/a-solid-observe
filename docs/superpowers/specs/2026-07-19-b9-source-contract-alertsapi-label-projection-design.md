# B9 Source 契约统一 + AlertsApi 简化 + label 投影设计

**日期**：2026-07-19
**状态**：Proposed
**批次**：B9（三件合一：EventListener 单事件 + 反压队列、CronScheduler→CronSource 对齐 Source、AlertsApi 简化 + ADR-0004 label 投影落地）
**前置依赖**：B7（AlertEntity/AlertPo/AlertMapper/AlertDto/DefaultAlertSink/AlertRepository.applyDisposition 现状）、ADR-0004（drop pipelineLabels + label 投影列，accepted）、ADR-0002（namespace 顶层过滤）。
**关联**：`docs/adr/0004-drop-pipelinelabels-label-projection-columns.md`、`docs/adr/0002-namespace-top-level-isolation.md`、`docs/2026-07-19-design-and-data-model-review.md`

> 本 spec 一次到位三件耦合的改造：Source/EventListener 契约重构（方向 1+2）与 AlertsApi/label 落地（方向 3）。

---

## 1. 背景与目标

三个现状问题：
1. **EventListener 只有 `onBatch(List<Event>)`**：单事件场景（Cron/Api/InMemory）被迫包成 `List.of(event)`；batch 概念对 CDC 无意义——瓶颈在 pipeline 异步执行，CDC 攒批不提速，反压该用队列表达。
2. **CronScheduler 不实现 Source 接口**：生命周期（start/stop）单独管理，与其他 3 个 Source 不一致；类名 Scheduler 也误导。
3. **AlertsApi 对 Groovy 不友好**：脚本要手搓 `AlertSpec`（含 labels/fingerprint）；且 ADR-0004（accepted）长期未落地——`team`/`application` 仍是一等领域字段、`pipeline_labels` denormalize 副本仍在、alert 无 label 投影列（Sybase 无 JSON 索引，按维度查告警走全表扫）。

**B9 目标**：
- `EventListener` 单事件为主 + 有界队列反压；batch 从接口消失。
- `CronScheduler` 改名 `CronSource` 并实现 `Source`（start/stop 统一），保留 `sync(snapshot)`。
- `AlertsApi` 提供 `critical/warning/info(annotations)` Groovy 简化 API；**落地 ADR-0004**：pipeline 维度统一进 labels、alert 加 `label_team/label_app/label_line` 投影列、删 pipeline_labels denormalize 副本。

**非目标**：
- 不做 EAV 键值索引表（ADR-0004 二期）。
- 不改 namespace 顶层隔离模型。
- 不动 silence/ack/resolve（B7 已落地）。

---

## 2. 设计决策（已与用户确认）

| 决策 | 选择 |
|---|---|
| EventListener 形态 | 单事件 `onEvent(Event)` 为主；**去掉 onBatch**（CDC 也不再 batch） |
| CDC 反压 | `SourceDispatcher` 内部有界 `BlockingQueue<Event>` + N 分发线程；队列满 → `onEvent` 阻塞 → 消费方（MQ onMessage / Cron fire）反压 |
| MQ ack 边界 | 逐条：`onMessage` 直接 `listener.onEvent(event)` + 单条 `acknowledge`（at-least-once 保留） |
| Cron 对齐 | `CronScheduler` → `CronSource implements Source`；`sync(snapshot)` 保留不进接口，仍由 HotReloader 显式调 |
| Groovy 告警 API | `alerts.critical/warning/info(annotations)` 三 default 方法，只接 annotations |
| label 合并 | `alert.labels = merge(pipeline.labels 打底, 脚本 spec.labels 覆盖)` |
| 维度字段 | **删 team/application 一等领域字段**（pipeline + ExecutionMeta + executions + failed_executions + alerts）；维度统一进 labels |
| Sybase 索引 | alerts 加 `label_team`/`label_app`/`label_line` 投影列（B-tree），emit 时从 labels JSON 投影 |
| namespace | 保持一等列（顶层过滤，不进 label 投影） |
| 执行表维度 | executions/failed_executions 删 team/application，不加投影 |

---

## 3. 方向 1：EventListener 单事件 + Dispatcher 有界队列反压

### 3.1 EventListener 接口
```java
public interface EventListener {
    void onEvent(Event event);   // 唯一方法
}
```
去掉 `onBatch`。所有消费方改单事件。

### 3.2 SourceDispatcher：有界队列 + 分发线程 + runnerPool（三级）
- 内部 `BlockingQueue<Event> queue`（容量可配，默认如 1000，`observe.worker.dispatch-queue-size`）。
- `onEvent(event)` → `queue.put(event)`（阻塞直到有位 → 反压消费方）。
- N 个**分发线程**（`observe.worker.dispatch-threads`，默认如 2）：循环 `queue.take()` → `matcher.match(event)` → 对每个 matched 提交 `runnerPool.execute(run)`。
- `runnerPool`（`ThreadPoolExecutor`）**改为有界队列 + 阻塞提交**（`beforeExecute`/自定义 `RejectedExecutionHandler` 或用 `Semaphore` 限流）：runnerPool 满时分发线程**阻塞在提交上**，而非拒绝丢弃——保证「入队即会被执行」，不丢事件。
- **反压链**：runnerPool 满 → 分发线程阻塞在提交 → 停止 `queue.take` → 队列堆积 → 队列满 → `onEvent.put` 阻塞 → MQ 不 ack（JMS 暂停投递/重投）/ Cron fire 阻塞（轻微延后）。
- **不丢事件**：事件一旦 `onEvent` 入队成功（`put` 返回），保证最终被 match + 提交执行；runnerPool 饱和靠阻塞反压，不靠拒绝丢弃。MQ 在 `put` 返回后 ack——此时事件已在 dispatcher 队列，由分发线程保证执行。
- 生命周期：dispatcher `start()` 启分发线程、`stop()` 关闭（drain 队列后停）。

### 3.3 消费方调整
- **IbmMqCdcSource**：删 batch 缓冲 / `batchSize` / `batchTimeoutMillis` / `flush`。`onMessage`：`listener.onEvent(event)` 后 `message.acknowledge()`（逐条）。反压靠 `onEvent` 阻塞（队列满 → onMessage 阻塞 → 不 ack）。
- **ApiSource**：已是单条，`listener.onEvent`。
- **InMemoryCdcSource**：`push` 单条 → `onEvent`。
- **CronSource.dispatch**：`listener.onEvent(event)`。

### 3.4 at-least-once 语义
- **保证**：事件入 dispatcher 队列后必被执行（runnerPool 饱和靠阻塞，不丢）；MQ 在入队成功后 ack，下游 pipeline 失败不回 MQ（沿用现状——pipeline 异步失败不 nack）。
- **反压**：runnerPool/队列满 → 整条链阻塞回压到 MQ（不 ack → 重投/暂停投递）。
- Cron：fire 阻塞可接受（定时触发，延后即轻微 misfire，SKIP 策略仍在）。

---

## 4. 方向 2：CronScheduler → CronSource 对齐 Source

- 类改名 `CronScheduler` → `CronSource`，`implements Source`。
- `start(EventListener listener)`：保存 listener 引用 + 启动内部 `ScheduledExecutorService`（当前构造时持 listener，改成 start 注入）。
- `stop()`：等价现 `shutdown()`（取消句柄、清状态、关 SES）。`Source` 接口的 `stop` 即此。
- `sync(PipelineRegistry.Snapshot)`：签名不变，**保留**，仍由 `PipelineHotReloader.refresh()` 末尾调。不进 `Source` 接口（cron 专属热加载）。
- **装配顺序**：worker 启动 → sources start（含 CronSource.start 注入 listener）→ HotReloader 首次 refresh → `cronSource.sync(snapshot)`。保证 sync 在 start 之后（sync 内判 listener 非空，防御）。
- `WorkerShutdown` / source 生命周期：CronSource 加入 `List<Source>` 统一 start/stop（之前单独管理）。
- 测试：`CronSchedulerTest` → `CronSourceTest`，新增 `start/stop` 覆盖。

---

## 5. 方向 3：AlertsApi 简化 + ADR-0004 label 投影落地

### 5.1 pipeline 去 team/application，只用 labels
- `PipelineDefinition` / `PipelineDefinitionPo`：删 `team` / `application` 字段，保留 `labels`（Map）。
- `pipelines` 表（config V2 迁移参考）：删 `team` / `application` 列 + `idx_pipelines_team_app` 索引；保留 `labels` JSON。
- `CreatePipelineRequest` / `PipelineDto`：删 team/application。
- `ExecutionMeta`：删 `team` / `application` / `pipelineLabels`，**加 `labels`（Map<String,String>，pipeline 的 labels）**。
- `ExecutionPo` / `FailedExecutionPo` / `Execution` / `FailedExecution` domain：删 team/application 列/字段（不加投影）。
- `ExecutionDto` / `FailedExecutionDto`：删 team/application。
- `JpaExecutionRecorder`：写 ExecutionPo/FailedExecutionPo 时不再拷 team/application（meta 已无这俩）；ExecutionMeta 改读 `labels`（执行记录不落 labels，仅 alert 落）。
- `ExecutionRepository`/`FailedExecutionRepository` stats 查询：`countByStatus` 等去掉 team 维度过滤（仍支持 namespace/pipelineId/trigger_type）。
- `DefaultPipelineRunner`（构造 ExecutionMeta 处）：从 `Pipeline` 领域对象填 `labels`（pipeline.labels），不再填 team/application。

### 5.2 alert label 投影列（ADR-0004 §2）
- `alerts` 表（alerting V2 迁移参考）：删 `team` / `application` / `pipeline_labels` 列；**加 `label_team` / `label_app` / `label_line`（VARCHAR，nullable）**；加索引 `idx_alerts_label_team(label_team)`、`idx_alerts_label_app(label_app)`（或组合，按查询模式定）。
- `AlertPo`：删 team/application/pipelineLabels 字段；加 `labelTeam` / `labelApp` / `labelLine`。
- `AlertEntity` / `AlertDto` / `AlertMapper`：同步（删旧、加投影）。

### 5.3 label 合并 + 投影（DefaultAlertSink）
`persist` 时：
```
mergedLabels = merge(meta.labels /* pipeline 打底 */, signal.labels /* 脚本覆盖 */)
alert.labels = mergedLabels                        // 固化进 JSON 列
alert.labelTeam = mergedLabels.get("team")         // 投影
alert.labelApp  = mergedLabels.get("app")
alert.labelLine = mergedLabels.get("line")
```
- 投影 key 固定 `team`/`app`/`line`（pipeline 配 labels 时用这三个 key）。
- 缺失 key → 投影列 null。
- namespace 仍从 meta 拷到 alert.namespace 一等列。

### 5.4 fingerprint
`AlertFingerprintCalculator.compute(pipelineId, mergedLabels)` 不变；mergedLabels 现含 team/app/line → 同业务不同维度自然分指纹。

### 5.5 AlertsApi 简化（kernel SPI）
```java
public interface AlertsApi {
    void emit(AlertSpec spec);   // 逃生口（脚本要传额外 labels/fingerprint 时用）

    default void critical(Map<String,Object> annotations) {
        emit(new AlertSpec(null, Severity.CRITICAL, null, annotations, null, false, null));
    }
    default void warning(Map<String,Object> annotations) { ... WARNING ... }
    default void info(Map<String,Object> annotations)    { ... INFO ... }
}
```
- 三方法 labels 传 null（label 打底由 sink 从 pipeline.labels 做，脚本无感）。
- annotations 只接描述性详情。
- `DefaultAlertsApi` / `DryRunAlertsApi`：default 方法自动可用；`emit(AlertSpec)` 内 label 合并下沉到 sink（API 层不再合并，保持原样透传 spec.labels 给 sink）。

### 5.6 Labels vs Annotations 边界（文档化）
- **labels**：维度/路由/分组键（低基数，进 fingerprint + 投影列）：team/app/line/entity 等。pipeline 打底 + 脚本可选额外。
- **annotations**：描述/详情（高基数，给人看）：当前值、阈值、消息。`critical(annotations)` 传的就是这个。

---

## 6. 配置（application.yml）

```yaml
observe:
  worker:
    dispatch-queue-size: 1000
    dispatch-threads: 2
    # runner-core/runner-max 保留
```

---

## 7. 测试

### 7.1 EventListener / Dispatcher
- `SourceDispatcherTest`：改单事件；验证有界队列反压（队列满 `onEvent` 阻塞，用 `CountDownLatch`/超时断言）；分发线程正确 match+提交。
- `SourceDispatcherBackpressureTest`（新）：灌满队列 → `onEvent` 阻塞 → 释放后恢复。
- `IbmMqCdcSource` 相关：逐条 ack（若现有测试覆盖 batch，改为逐条）。

### 7.2 CronSource
- `CronSchedulerTest` → `CronSourceTest`：sync diff / fire / SKIP 保留；新增 start(注入 listener)/stop 覆盖。
- `EndToEndFlowTest`：Cron 路径走 CronSource.start。

### 7.3 AlertsApi + label 投影
- `DefaultAlertSinkIntegrationTest`：emit 后 alert.labels = pipeline.labels 打底 + 脚本覆盖；label_team/label_app/label_line 投影正确；缺 key → null。
- `AlertsApiSimplificationTest`（新）：`alerts.critical([...])` / `warning` / `info` 产出正确 severity + annotations；labels 为 null（由 sink 打底）。
- `DryRunAlertsApi`：default 方法可用。

### 7.4 schema 迁移
- H2 由 ddl-auto 驱动（PO 改完自动建）；config V2 / alerting V2 SQL 作生产参考（drop team/app + pipeline_labels、加 label_* 投影列 + 索引）。

---

## 8. 验收标准

1. `mvn compile` + `mvn test` + `mvn checkstyle:check` 全绿
2. `mvn spring-boot:run` 可启动，dispatcher 分发线程 + CronSource.start 正常
3. 手动验证：
   - 单事件路径：MQ/Cron/Api 各产事件经 dispatcher 队列分发
   - 反压：队列满时消费方阻塞（观察日志/不 ack）
   - Groovy 脚本 `alerts.critical([msg:'x'])` 产出 alert，label_team 等从 pipeline.labels 投影
4. ADR-0004 落地：无 team/application 一等字段、无 pipeline_labels 列、有 label_* 投影列

---

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 删 team/application 一等列波及面大（pipeline/meta/execution/alert 多处） | 全量 grep 清；编译 + 测试拦截 |
| label 投影列与 JSON 不一致 | 投影在 sink 唯一写入点同步填，不分叉 |
| 反压队列阻塞 Cron fire | 可接受（定时延后）；SKIP 策略保留 |
| MQ 逐条 ack 性能 | 逐条 ack 为定稿（非"远期优化"）。瓶颈在 pipeline 执行，dispatcher 队列 + Semaphore 反压锁死消费速率，onMessage 不可能攒批；批量 ack 唯一省的是确认帧网络往返（相对 pipeline 执行耗时可忽略），且会引入部分重投语义，反不如逐条干净 |
| dispatch 队列丢事件（异常停机） | at-least-once 由 MQ 重投保证；Cron 无持久化（misfire 忽略，ADR-0007） |
| ddl-auto vs 生产迁移 | V2 SQL 作生产参考；上线前真实库冒烟 |

---

## 10. 改动文件清单（预估）

**EventListener/Dispatcher（observe-pipeline）**：
- `EventListener.java`（onBatch→onEvent）
- `SourceDispatcher.java`（有界队列 + 分发线程）
- `CronScheduler.java` → **改名 `CronSource.java`** + `implements Source`

**Source 消费方**：
- `IbmMqCdcSource.java`（删 batch，逐条 onEvent+ack）
- `ApiSource.java` / `InMemoryCdcSource.java`（onEvent）
- `WorkerConfig.java` / `WorkerShutdown.java`（CronSource 入 List<Source> 统一生命周期 + dispatch 队列配置）

**AlertsApi + label 投影**：
- `AlertsApi.java`（+3 default 方法）
- `DefaultAlertSink.java`（label 合并 + 投影）
- `AlertEntity` / `AlertPo` / `AlertMapper` / `AlertDto`（删 team/app/pipelineLabels，加 label_team/app/line）
- `ExecutionMeta`（删 team/app/pipelineLabels，加 labels）
- `ExecutionPo` / `FailedExecutionPo` / `Execution` / `FailedExecution` / `ExecutionDto` / `FailedExecutionDto`（删 team/app）
- `PipelineDefinition` / `PipelineDefinitionPo` / `CreatePipelineRequest` / `PipelineDto`（删 team/app）
- `ExecutionRepository` / `FailedExecutionRepository` stats（去 team 维度）
- `AlertQueryService`（findAlerts 的 team 过滤 → 改 label_team 过滤，或移除）

**配置**：`application.yml`（+dispatch-queue-size/dispatch-threads）

**SQL 参考**：`config/V2__drop_team_app.sql`、`alerting/V2__label_projection.sql`（删 team/app/pipeline_labels，加 label_* + 索引）

**测试**：`SourceDispatcherTest` / `SourceDispatcherBackpressureTest`(新) / `CronSourceTest`(改名) / `DefaultAlertSinkIntegrationTest` / `AlertsApiSimplificationTest`(新)
