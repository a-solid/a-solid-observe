# B4 Cron 每订阅表达式调度（CronScheduler）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 把"全局单 CronSource 固定周期"换成"每 Cron 订阅一个 cron 表达式调度 + CronScheduler 作为 registry 观察者"（ADR-0007 / CONTEXT.md Scheduling）。支持 cron 表达式（Spring CronExpression 6 字段）、misfire 忽略（M1）、并发默认 SKIP。

**Architecture:** `SubscriptionDefinition` 加 cron 字段（cronExpression/cronName/concurrent）。新增 `CronScheduler`（pipeline application，有状态）—— 作为 PipelineRegistry 的观察者：热加载 `registry.replace(snapshot)` 后显式调 `cronScheduler.sync(snapshot)`，diff Cron 订阅起停调度句柄（`ConcurrentMap<subscriptionId, ScheduledFuture>` + 调度线程池）。到点产 TickEvent 投 SourceDispatcher，matcher 按 source name 路由。concurrent=SKIP 用 per-sub `AtomicBoolean`。删除全局 `CronSource` bean + `cronPoolSize`/`cronPeriodMillis` 配置。

**Tech Stack:** Java 17、Spring `CronExpression`、ScheduledExecutorService。

> B4 前置 B3（TickEvent 已就绪）。本份只做 Cron 调度，不动 Event 模型/Alert。

## Global Constraints

- 设计权威：`CONTEXT.md` Scheduling 节 + `docs/adr/0007-cron-per-subscription-scheduler.md`。
- cron 表达式配在 SubscriptionDefinition（sourceType=CRON + cronExpression + 可选 cronName + concurrent）。
- Spring `CronExpression`（6 字段：秒 分 时 日 月 周）。
- CronScheduler 是 registry 观察者，**显式调用**（`PipelineHotReloader.refresh()` 末尾调 `cronScheduler.sync(snapshot)`，非事件订阅）。
- 每订阅一调度句柄（相同表达式也是独立调度）。
- misfire M1：重启错过的 cron 不补跑。
- concurrent 默认 SKIP（per-sub AtomicBoolean，上次没跑完跳过本次）。
- 删除全局 CronSource bean + WorkerProperties.cronPoolSize/cronPeriodMillis。
- 每批结束 `mvn compile` + `mvn test` 全绿。

## File Structure

**Create:**
- `observe-pipeline/application/CronScheduler.java` —— 有状态组件（调度句柄 map + 线程池 + sync diff + 产 TickEvent 投 dispatcher + SKIP 并发控制）。
- `observe-pipeline/src/test/.../CronSchedulerTest.java` —— sync diff（新增/变更/删除起停）、到点产 TickEvent、SKIP 并发、misfire。

**Modify:**
- `observe-config/domain/SubscriptionDefinition.java` —— 加 `cronExpression`/`cronName`/`concurrent` 字段。
- `observe-config/infrastructure/persistence/SubscriptionPo.java` + `SubscriptionMapper.java` —— 加 3 列。
- `observe-controlplane/.../SubscriptionController.java` + `SubscriptionDto.java` + `SubscriptionFields` —— 暴露/接收 cron 字段；create 时校验 cronExpression 可被 `CronExpression.parse()` 解析（sourceType=CRON 时必填）。
- `observe-pipeline/domain/subscription/Subscription.java`（运行态）+ `SourceRef` —— 加 cron 字段透传（运行态 SourceRef 已用 mq slot 作 source name；加 cronExpression/concurrent）。
- `observe-config/application/PipelineRegistryLoader.java` —— 透传 cron 字段到运行态。
- `observe-pipeline/application/PipelineHotReloader.java`（或 WorkerConfig 的 HotReloaderScheduler）—— refresh 后调 `cronScheduler.sync(snapshot)`。
- `observe-bootstrap/worker/config/WorkerConfig.java` —— 删 `cronSource` bean；加 `CronScheduler` bean + 调度线程池配置；`WorkerShutdown` 加 cronScheduler.shutdown()。
- `observe-bootstrap/worker/config/WorkerProperties.java` —— 删 cronPoolSize/cronPeriodMillis，加 cronSchedulerPoolSize。
- `observe-pipeline/infrastructure/source/CronSource.java` —— **删除**（被 CronScheduler 取代）。
- SQL `config/V1__init.sql` —— subscriptions 加 cron_expression/cron_name/concurrent 列。
- 测试：SubscriptionCrudServiceTest、EndToEndFlowTest、SubscriptionControllerTest 适配；新增 CronSchedulerTest。

## Task 分解

### Task 1: SubscriptionDefinition cron 字段 + PO/Mapper/Controller/DTO + 校验

**Files:** SubscriptionDefinition、SubscriptionPo、SubscriptionMapper、SubscriptionController、SubscriptionDto、SubscriptionFields、PipelineRegistryLoader（透传）、运行态 Subscription/SourceRef、SubscriptionCrudService（校验）、SQL、相关测试。

- [ ] Step 1: config SubscriptionDefinition 加 `cronExpression`/`cronName`/`concurrent`（concurrent 枚举 SKIP/ALLOW，默认 SKIP）。
- [ ] Step 2: SubscriptionPo 加 3 列；Mapper 透传。
- [ ] Step 3: 运行态 Subscription/SourceRef 加 cron 字段；Loader 透传。
- [ ] Step 4: controlplane SubscriptionFields/SubscriptionDto 加 cron 字段；create 时 sourceType=CRON 校验 cronExpression 非空 + `CronExpression.parse()` 可解析（无效抛 IllegalArgumentException）。
- [ ] Step 5: SQL subscriptions 加 3 列。
- [ ] Step 6: 测试适配（SubscriptionCrudServiceTest/SubscriptionControllerTest 加 cron 校验用例）。
- [ ] Step 7: 编译 + 模块测试。
- [ ] Step 8: 提交。

### Task 2: CronScheduler 组件 + 单测

**Files:** CronScheduler（create）、CronSchedulerTest（create）。

- [ ] Step 1: 写 CronScheduler：
  - 构造注入 `ScheduledExecutorService` + `EventListener`（SourceDispatcher.onBatch）。
  - `ConcurrentMap<Long, ScheduledFuture<?>> bySubscriptionId` + `ConcurrentMap<Long, AtomicBoolean> running`（SKIP 控制）。
  - `sync(Snapshot)`：diff 新旧 Cron 订阅——新增/变更（cronExpression 或 source name 变）→ cancel 老 + schedule 新；删除 → cancel。用 `CronExpression` 算下一次触发时刻，`scheduler.schedule(task, delay, MS)`，task 内重新 schedule 下一次（自调度链）或用 `scheduleAtFixedRate` 不行（cron 非固定间隔）——用"每次 fire 后算下次 schedule"的自递归。
  - fire task：检查 running AtomicBoolean（SKIP：CAS true 失败则跳过），构造 TickEvent（TickMeta source=cron name、cronName、cronExpression），投 `listener.onBatch(List.of(tick))`，finally CAS false。
  - `shutdown()`：cancel 所有 + 关 SES。
- [ ] Step 2: 写 CronSchedulerTest：sync 新增起调度、变更重起、删除停、到点产 TickEvent（用短表达式如 `* * * * * ?` 每秒 + 等待验证）、SKIP 并发（慢 task + 高频跳过）、misfire（重启不补，验证启动只看未来）。
- [ ] Step 3: 编译 + 测试。
- [ ] Step 4: 提交。

### Task 3: 装配 + HotReloader 显式调用 + 删 CronSource + 全量绿

**Files:** WorkerConfig、WorkerProperties、WorkerShutdown、PipelineHotReloader（或 HotReloaderScheduler）、删 CronSource、EndToEndFlowTest。

- [ ] Step 1: WorkerConfig 删 `cronSource` bean + import；加 `CronScheduler` bean（注入调度线程池 + SourceDispatcher）。WorkerProperties 删 cronPoolSize/cronPeriodMillis，加 cronSchedulerPoolSize（默认 4）。
- [ ] Step 2: HotReloaderScheduler.refresh()（或 PipelineHotReloader.refresh()）末尾调 `cronScheduler.sync(registry.snapshot())`（在 registry.replace 之后）。
- [ ] Step 3: WorkerShutdown 加 `cronScheduler.shutdown()`。
- [ ] Step 4: 删 CronSource.java（grep 确认无引用）。
- [ ] Step 5: EndToEndFlowTest 适配（若有 CronSource 引用则去；可选加一个 cron 订阅 e2e 验证）。
- [ ] Step 6: `mvn clean test` 全绿。
- [ ] Step 7: 提交 + 收尾。

---

## Self-Review

1. **Spec 覆盖**：ADR-0007 全部 = Task 1-3。Spring CronExpression、显式调用 sync、每订阅一调度、M1 misfire、SKIP 并发均覆盖。
2. **关键技术点**：cron 非固定间隔 → 不能用 scheduleAtFixedRate，用"fire 后算下次 schedule"自递归（Task 2 Step 1 明确）。
3. **顺序**：Task 1（字段+校验）→ Task 2（CronScheduler+测试）→ Task 3（装配+删旧+全量绿）。
4. **风险**：CronScheduler 与热加载竞态（ConcurrentMap + cancel 线程安全）；cron 表达式校验（CronExpression.parse 抛异常处理）；自递归调度的取消正确性。
