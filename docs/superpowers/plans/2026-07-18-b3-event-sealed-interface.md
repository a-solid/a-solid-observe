# B3 Event 模型（sealed interface per-source）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 把 Event 从单一 record 改成 sealed interface + per-source 子类型（CdcEvent/TickEvent/ApiEvent/DelayedEvent），拆 Op 为 CdcOp，matcher pattern-match 分发，各 source 产对应子类型（ADR-0006 / CONTEXT.md Events）。

**Architecture:** kernel 新增 `sealed interface Event permits CdcEvent, TickEvent, ApiEvent, DelayedEvent` + 各子 record + `CdcOp` 枚举（取代旧 Op 的 CDC 部分）。matcher `match(Event)` 内部 switch 分发：CdcEvent→按 db/table/op 匹配 CDC 订阅；TickEvent/ApiEvent→按 source name 匹配；DelayedEvent→不走 matcher。Condition.matches 适配 sealed。Jackson 配 `@JsonTypeInfo`/`@JsonSubTypes` 支持序列化（trigger_event/evidence）。CronSource 产 TickEvent（**B3 阶段用占位固定周期**，B4 换 CronScheduler + 表达式）。

**Tech Stack:** Java 17 sealed interface、Jackson 多态、Spring Boot。

> B3 是独立的架构批，前置 B1/B2（已合并）。B3 必须整批消化 matcher/Condition/Snapshot/sources 的连带改造（否则编译不过）。B3 内部不强求每提交编译通过，批末正确即可。

## Global Constraints

- 设计权威：`CONTEXT.md` Events 节 + `docs/adr/0006-event-sealed-interface-per-source.md`。
- Event sealed permits CdcEvent/TickEvent/ApiEvent/DelayedEvent，全在 kernel.event.model。
- CdcOp{INSERT,UPDATE,DELETE} 取代旧 Op 的 CDC 部分；TICK/API/DELAYED 不再是 op（由 Event 子类型表达）。**旧 Op 枚举删除**。
- TickEvent 无 payload（纯触发）；ApiEvent 带 payload Map；DelayedEvent 嵌套 originalEvent。
- S-implicit：订阅保证类型，脚本 `event.after.xxx`（CDC 订阅下 event 即 CdcEvent），无需转换/判空。
- CdcEvent 经 IbmMqXmlParser 解析（op 用 CdcOp）。
- Jackson 多态：`@JsonTypeInfo(use=Id.NAME, property="@type")` + `@JsonSubTypes`，DelayedEvent 嵌套 Event 递归多态。
- CronSource 在 B3 产 TickEvent（占位固定周期），B4 换 CronScheduler。
- namespace 已在 ExecutionMeta（B2b），各 Event 子类型的 meta 怎么带 namespace？**决策**：CDC 的 namespace 在 matching→pipeline 时确定（event 本身不带 namespace，namespace 是 pipeline 属性）。ExecutionMeta.namespace 从 pipeline 取（B2b 已做）。Event 子类型**不**加 namespace 字段（namespace 是 pipeline 维度，不是 event 维度——event 在 ingest 时还不知道归属哪个 namespace/pipeline，要 matcher 匹配后才知道）。这符合 B2b 的"data-plane matcher 不按 namespace 过滤"的已知延迟（B3 不解决，因为 namespace 不是 event 属性）。
- 每批结束 `mvn compile` + `mvn test` 全绿。

## File Structure

**Create (kernel.event.model):**
- `Event.java`（sealed interface，取代旧 record）
- `CdcEvent.java`、`TickEvent.java`、`ApiEvent.java`、`DelayedEvent.java`
- `CdcOp.java`（enum）
- 各 Meta：`CdcMeta`、`TickMeta`、`ApiMeta`、`DelayedMeta`（或复用统一 EventMeta？**决策**：各子类型自带专用 Meta record，字段贴合该 source。CdcMeta{sourceType,source,db,table,attributes}；TickMeta{source,cronName,cronExpression,attributes}；ApiMeta{source,apiName,attributes}；DelayedMeta{source,attributes 含 schedule_id/subscription_id/original_event/scheduled_at/fired_at/correlation_key}）

**Delete:**
- 旧 `Event.java`（record 形态）+ 旧 `Op.java`（被 CdcOp 取代）

**Modify:**
- `EventPaths.java`（kernel）：`get(Event, path)` 适配 sealed（CdcEvent 解析 before./after./op/meta.db/table；其他子类型按需）。
- `Condition.Fields.resolve(Event, path)`（pipeline domain）：适配 sealed。
- `DefaultSubscriptionMatcher.match(Event)` + `matchesSource`：pattern-match 分发。
- `PipelineRegistry.Snapshot`：多索引（by db/table for CDC；by source for Cron/Api）+ `subscriptionsFor(Event)` 按子类型查。
- 各 Source 产出子类型：
  - `IbmMqXmlParser`（bootstrap）：解析产 CdcEvent（op 用 CdcOp）。
  - `InMemoryCdcSource`（bootstrap）：产 CdcEvent。
  - `CronSource`（pipeline）：产 TickEvent（占位固定周期，meta 带 cron name；B4 换 CronScheduler）。
  - `ApiSource`（pipeline）：产 ApiEvent（payload 从 HTTP body）。
  - `DelayedActionHandler.wrapAsDelayed`：产 DelayedEvent（嵌套 originalEvent）。
- `Subscription.SourceRef`（pipeline runtime）：opTypes 类型从 `Set<Op>` 改 `Set<CdcOp>`。
- `SubscriptionDefinition`（config domain）：opTypes `Set<CdcOp>`；config Mapper/Codec 适配。
- `SubscriptionController`/DTO、`EventController`（controlplane）：API 触发产 ApiEvent，op 字段处理。
- 序列化：`JpaExecutionRecorder.serializeEvent` + `DryRunService`：Jackson 多态。
- 脚本 binding：ScriptNode 注入 event（S-implicit，类型由订阅保证）。
- 测试：ConditionTest、DefaultSubscriptionMatcherTest、SourceDispatcherTest、InMemoryDelayedEventStoreTest、IbmMqXmlParserTest、JpaExecutionRecorderTest、EngineSmokeTest、EndToEndFlowTest 等适配 sealed。

## Task 分解

### Task 1: kernel 新 Event sealed + 子类型 + CdcOp + Meta（数据模型层）

**Files:** Create Event/CdcEvent/TickEvent/ApiEvent/DelayedEvent/CdcOp + 4 Meta；Delete 旧 Event record + Op。

**Interfaces:** Produces 新 Event sealed 体系。

- [ ] Step 1: 写 CdcOp + 4 Meta + sealed Event + 4 子 record（完整代码，含 Jackson 注解）。CdcEvent 带 `@JsonTypeInfo`/`@JsonSubTypes` 在 sealed Event 上。
- [ ] Step 2: 删旧 Event.java（record）+ Op.java。
- [ ] Step 3: kernel 自身编译（预期下游全断，记录，后续 task 修）。
- [ ] Step 4: 提交。

### Task 2: EventPaths + Condition 适配 sealed

**Files:** EventPaths（kernel）、Condition.Fields（pipeline domain）。

- [ ] Step 1: EventPaths.get 适配 sealed（CdcEvent 路径解析；TickEvent/ApiEvent/DelayedEvent 按需）。
- [ ] Step 2: Condition.Fields.resolve 适配 sealed；fieldFilter 仅 CDC 有意义（其他子类型 resolve 返回 null 或按 source 字段）。
- [ ] Step 3: ConditionTest 适配。
- [ ] Step 4: 提交。

### Task 3: Snapshot 多索引 + matcher pattern-match 分发

**Files:** PipelineRegistry.Snapshot、DefaultSubscriptionMatcher、SubscriptionMatcher、运行态 Subscription.SourceRef（opTypes→CdcOp）、PipelineRegistryLoader（透传 CdcOp）。

- [ ] Step 1: Snapshot 加 `subscriptionsByCdcDbTable` + `subscriptionsBySource`；`subscriptionsFor(Event)` 按子类型查。
- [ ] Step 2: matcher.match(Event) switch 分发：CdcEvent→CDC 订阅匹配（opTypes CdcOp）；TickEvent/ApiEvent→按 source name；DelayedEvent→空（不走 matcher）。
- [ ] Step 3: SourceRef opTypes `Set<CdcOp>`；Loader 透传。
- [ ] Step 4: DefaultSubscriptionMatcherTest 适配。
- [ ] Step 5: 提交。

### Task 4: 各 Source 产对应子类型 + Delayed 包装 + Api payload

**Files:** IbmMqXmlParser、InMemoryCdcSource、CronSource、ApiSource、DelayedActionHandler.wrapAsDelayed、ApiSource 的 HTTP 接收（EventController）、SubscriptionDefinition/Mapper/Codec opTypes CdcOp。

- [ ] Step 1: IbmMqXmlParser 产 CdcEvent（op CdcOp）；IbmMqXmlParserTest 适配。
- [ ] Step 2: InMemoryCdcSource 产 CdcEvent。
- [ ] Step 3: CronSource 产 TickEvent（占位固定周期，meta cron name）。
- [ ] Step 4: ApiSource 产 ApiEvent（payload）；EventController API 触发产 ApiEvent。
- [ ] Step 5: DelayedActionHandler.wrapAsDelayed 产 DelayedEvent（嵌套 original）。
- [ ] Step 6: config SubscriptionDefinition/Mapper/Codec opTypes `Set<CdcOp>`；controlplane DTO/Controller op 处理。
- [ ] Step 7: 提交。

### Task 5: 序列化（Jackson 多态）+ Recorder/DryRun + 全量编译绿

**Files:** JpaExecutionRecorder.serializeEvent、DryRunService、ExecutionPo trigger_event 序列化、任何 ObjectMapper 配置。

- [ ] Step 1: sealed Event Jackson 多态配置（@JsonTypeInfo/@JsonSubTypes 在 Event 上；DelayedEvent 嵌套递归多态）。
- [ ] Step 2: serializeEvent 验证 sealed 能序列化/反序列化。
- [ ] Step 3: 全量编译 `mvn clean compile`。
- [ ] Step 4: 提交。

### Task 6: 测试 fixture 适配 + 全量测试绿（B3 收尾）

**Files:** 所有受影响测试（ConditionTest、DefaultSubscriptionMatcherTest、SourceDispatcherTest、InMemoryDelayedEventStoreTest、IbmMqXmlParserTest、JpaExecutionRecorderTest、EngineSmokeTest、EndToEndFlowTest、EventControllerTest、ConditionCodecTest 等）。

- [ ] Step 1: 逐个测试适配 sealed（构造 CdcEvent/TickEvent/ApiEvent/DelayedEvent 替代旧 Event）。
- [ ] Step 2: EndToEndFlowTest 验证 CDC→CdcEvent→match→pipeline→alert 全链路。
- [ ] Step 3: `mvn clean test` 全绿。
- [ ] Step 4: 提交 + 收尾。

---

## Self-Review

1. **Spec 覆盖**：ADR-0006 全部决策 = Task 1-6。CronSource 占位（B4）、S-implicit（订阅保证类型）、namespace 不进 event（B2b 已知延迟）均标注。
2. **占位扫描**：Task 1-2 有完整代码框架；Task 3-6 是改动指引（明确文件 + 改什么）。sealed/Jackson 多态是核心，Task 1/5 重点。
3. **顺序**：Task 1（数据模型）→ Task 2（Paths/Condition）→ Task 3（Snapshot/matcher）→ Task 4（sources）→ Task 5（序列化+编译绿）→ Task 6（测试绿）。跨模块编译 Task 1-4 可能断，Task 5 恢复编译，Task 6 恢复测试。
4. **风险**：Jackson sealed 多态（DelayedEvent 嵌套递归）是最大技术风险，Task 5 重点验证。Op→CdcOp 的 39 个引用点要全改（Task 1 删 Op 后编译器抓全）。
