# B9 Source 契约统一 + AlertsApi 简化 + label 投影 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 落地 spec `docs/superpowers/specs/2026-07-19-b9-source-contract-alertsapi-label-projection-design.md`：EventListener 单事件 + dispatcher 有界队列反压；CronScheduler→CronSource 对齐 Source；AlertsApi 简化 + ADR-0004 label 投影。

**Architecture:** 见 spec §3/§4/§5。三级反压：有界 BlockingQueue → N 分发线程(match) → runnerPool(阻塞提交不丢)。CronSource 实现 Source(start/stop)，sync 保留。pipeline/team/application/pipelineLabels 一等字段全删，alert 加 label_team/label_app/label_line 投影列。

**Tech Stack:** Java 17、Spring、JPA、JMS（IBM MQ）。

> 依赖顺序：T1（Listener/Dispatcher 契约）→ T2（CronSource，依赖 T1 的 onEvent）→ T3（AlertsApi/label，相对独立但 ExecutionMeta 改动波及 runner）。每批结束 `mvn compile` + `mvn test` 全绿。

## Global Constraints

- 设计权威：spec + ADR-0004 + ADR-0002。
- ddl-auto=update（无 Flyway），schema 由 @Entity 驱动；V2 SQL 作生产参考。
- 删 team/application/pipelineLabels 要**全量 grep 清**（PO/domain/meta/DTO/controller/service/mapper/V1 SQL），编译 + 测试拦截遗漏。
- label 投影在 DefaultAlertSink 唯一写入点同步填，不分叉。
- runnerPool 饱和**阻塞不丢**（不用拒绝策略）。
- 每批结束 `mvn -q spotless:apply` + `mvn test` 全绿 + `mvn checkstyle:check`。

## File Structure

**Rename:**
- `observe-pipeline/application/CronScheduler.java` → `CronSource.java`
- `observe-pipeline/test/.../CronSchedulerTest.java` → `CronSourceTest.java`

**改：**
- `EventListener.java`（onBatch→onEvent）
- `SourceDispatcher.java`（有界队列 + 分发线程 + 阻塞 runnerPool）
- `IbmMqCdcSource.java` / `ApiSource.java` / `InMemoryCdcSource.java`（onEvent；MQ 去 batch 逐条 ack）
- `AlertsApi.java`（+3 default）
- `DefaultAlertSink.java`（label 合并 + 投影；删 team/app/pipelineLabels 拷贝）
- `AlertEntity`/`AlertPo`/`AlertMapper`/`AlertDto`（删 team/app/pipelineLabels，加 label_team/app/line）
- `ExecutionMeta`（删 team/app/pipelineLabels，加 labels）
- `ExecutionPo`/`FailedExecutionPo`/`Execution`/`FailedExecution`/`ExecutionDto`/`FailedExecutionDto`（删 team/app）
- `JpaExecutionRecorder.java`（不拷 team/app）
- `DefaultPipelineRunner.java`（meta 填 labels）
- `PipelineDefinition`/`PipelineDefinitionPo`/`CreatePipelineRequest`/`PipelineDto`（删 team/app）
- `WorkerConfig.java`/`WorkerShutdown.java`（CronSource 入 List<Source>；dispatch 队列配置）
- `application.yml`（+dispatch-queue-size/dispatch-threads）
- `ExecutionRepository`/`FailedExecutionRepository`（stats 去 team 维度）
- `AlertQueryService`（findAlerts team 过滤 → label_team 或移除）

**SQL 参考（生产）：** `config/V2__drop_team_app.sql`、`alerting/V2__label_projection.sql`

---

## T1 — EventListener 单事件 + Dispatcher 有界队列反压

- [ ] `EventListener.java`：`onBatch(List<Event>)` → `onEvent(Event)`。
- [ ] `SourceDispatcher.java`：
  - [ ] `implements EventListener.onEvent(Event)`：`queue.put(event)`（阻塞入队）。
  - [ ] 内部 `BlockingQueue<Event> queue`（容量来自配置）。
  - [ ] N 分发线程：`queue.take()` → `matcher.match(event)` → 对 matched 提交 runnerPool（阻塞提交，不丢）。
  - [ ] runnerPool 改有界 + 阻塞提交（Semaphore 或自定义 RejectedExecutionHandler 阻塞调用线程）。
  - [ ] `start()`/`stop()` 生命周期（启/停分发线程，stop 时 drain）。
  - [ ] 保留 DelayedActionHandler 处理。
- [ ] `IbmMqCdcSource.java`：删 eventBuffer/messageBuffer/batchSize/batchTimeoutMillis/flush/lastFlushMs；`onMessage` → `listener.onEvent(event)` + `message.acknowledge()`（逐条，ack 失败 warn）。
- [ ] `ApiSource.java`：单条 onEvent（已是单条，改方法名）。
- [ ] `InMemoryCdcSource.java`：push 单条 onEvent。
- [ ] `WorkerConfig.java`：dispatcher 装配传入队列容量 + 分发线程数；dispatcher 作为有生命周期的 bean（start/stop）。
- [ ] `application.yml`：+`observe.worker.dispatch-queue-size: 1000`、`dispatch-threads: 2`。
- [ ] 测试：
  - [ ] `SourceDispatcherTest`：改 onEvent；验证 match+提交。
  - [ ] `SourceDispatcherBackpressureTest`（新）：灌满队列 → onEvent 阻塞（timeout 断言）；释放后恢复。
  - [ ] MQ 逐条 ack 路径（若现有测试覆盖 batch，改）。
- [ ] `mvn -q spotless:apply && mvn test`（T1 子集绿，编译通过即可推进；全绿在批末）。

## T2 — CronScheduler → CronSource

- [ ] 重命名 `CronScheduler.java` → `CronSource.java`（类名、构造、所有引用）。
- [ ] `implements Source`：`start(EventListener)`（保存 listener + 启 SES）、`stop()`（= 现 shutdown）。
- [ ] 构造不再要求 listener（改 start 注入）；`sync(snapshot)` 内判 listener 非空（防御，未 start 时 sync 记 warn 跳过）。
- [ ] `dispatch`：`listener.onBatch(List.of(event))` → `listener.onEvent(event)`。
- [ ] `WorkerConfig`/`WorkerShutdown`：CronSource 加入 `List<Source>` 统一 start/stop（移除单独管理）。
- [ ] 装配顺序保证：sources start → HotReloader refresh → sync。
- [ ] 测试：`CronSchedulerTest` → `CronSourceTest`；sync/fire/SKIP 保留；新增 start/stop 覆盖。
- [ ] `EndToEndFlowTest`：Cron 路径走 CronSource.start。
- [ ] `mvn -q spotless:apply && mvn test` 绿。

## T3 — AlertsApi 简化 + ADR-0004 label 投影

- [ ] `AlertsApi.java`：+`critical/warning/info(Map<String,Object> annotations)` 三个 default 方法，labels 传 null。
- [ ] `ExecutionMeta.java`：删 team/application/pipelineLabels，加 `labels`（Map<String,String>）。
- [ ] `DefaultPipelineRunner.java`：构造 meta 时从 Pipeline 填 `labels`（pipeline.labels），不填 team/app。
- [ ] `JpaExecutionRecorder.java`：写 ExecutionPo/FailedExecutionPo 不拷 team/app。
- [ ] `ExecutionPo`/`FailedExecutionPo`：删 team/application 列。
- [ ] `Execution`/`FailedExecution` domain：删 team/app 字段。
- [ ] `ExecutionDto`/`FailedExecutionDto`：删 team/app。
- [ ] `ExecutionRepository`/`FailedExecutionRepository`：stats countByStatus 等去 team 维度。
- [ ] `PipelineDefinition`/`PipelineDefinitionPo`：删 team/application，保留 labels。
- [ ] `CreatePipelineRequest`/`PipelineDto`：删 team/application（注意 PipelineController.create/update 签名）。
- [ ] `AlertEntity`/`AlertPo`/`AlertMapper`/`AlertDto`：删 team/application/pipelineLabels，加 `labelTeam`/`labelApp`/`labelLine`。
- [ ] `DefaultAlertSink.persist`：`mergedLabels = merge(meta.labels, signal.labels)`；alert.labels=mergedLabels；labelTeam/App/Line 从 mergedLabels 取 team/app/line 投影（缺失 null）。
- [ ] `AlertQueryService.findAlerts`：team 过滤参数 → 改 labelTeam（或移除，前端改用 stats）。
- [ ] `AlertRepository` stats：若有 team 维度，改 label_team。
- [ ] SQL 参考：`config/V2__drop_team_app.sql`（drop team/application + idx_pipelines_team_app）、`alerting/V2__label_projection.sql`（drop team/application/pipeline_labels，add label_team/label_app/label_line + 索引）。
- [ ] 测试：
  - [ ] `DefaultAlertSinkIntegrationTest`：label 合并（pipeline 打底+脚本覆盖）+ 投影列正确 + 缺 key null。
  - [ ] `AlertsApiSimplificationTest`（新）：critical/warning/info 产出正确 severity+annotations，labels=null。
  - [ ] 现有 ExecutionStatsRepositoryTest/AlertStatsRepositoryTest：去 team 维度断言改 label 或移除。
- [ ] `mvn -q spotless:apply && mvn test` + `mvn checkstyle:check` 全绿。

## 批末验收

- [ ] `mvn compile` + `mvn test` + `mvn checkstyle:check` 全绿
- [ ] `mvn spring-boot:run` 可启动（dispatcher 分发线程 + CronSource.start 正常）
- [ ] 手动验证（若可行）：单事件路径、队列反压、`alerts.critical([msg:'x'])` 产出 + label 投影
- [ ] ADR-0004 落地确认：无 team/application 一等字段、无 pipeline_labels 列、有 label_* 投影列
