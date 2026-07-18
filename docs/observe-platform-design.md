# a-solid-observe 平台架构设计

**日期**：2026-07-18
**状态**：Current
**技术栈**：Java 17、Spring Boot、Groovy、关系型 DB、OTel
**目标 DB**：不预设（实际可能用 Sybase ASE，schema 设计需兼容）
**约定**：不使用外键（FK），引用完整性靠应用层保证

> 本文档描述当前已实现 / 一期范围内置的设计。远期演进、二期项、未实现的功能在各节内就地标注「（远期）」，不另设文档。

---

## 0. 目标与定位

a-solid-observe 是一个**部门内多团队**共用的观测/监测平台：

- 监听 CDC MQ 数据作为事件、或被定时任务/HTTP API 触发
- 执行用户配置的检测逻辑（Groovy 脚本，脚本内通过 `db` binding 查询/写入 DB、`alerts.emit` 触发告警）
- 命中规则时落库告警 + 证据
- 告警通过 HTTP API 暴露给 Grafana / AlertManager 拉取，由其负责路由/发送

**非目标（一期不做）**：多租户资源隔离、RBAC、审批流、内置告警发送渠道、完整脚本沙箱（GraalJS 级）、分布式 worker、DAG executor、严格事件顺序、业务级 metrics、数据归档、execution DEBUG 模式、死信手动重试 API、延时任务持久化。

---

## 1. 整体架构

### 1.1 进程模型

两个逻辑进程，初期在同一 JVM 启动（`ObserveApplication`，profile `all-in-one`）：

```
┌─────────────────────────────────────────────────────────────┐
│                     Control Plane 进程                       │
│  ─ 管理 API（pipeline / subscription / version CRUD）       │
│  ─ 告警查询 API（供 Grafana datasource 拉）                  │
│  ─ 执行记录 / dry-run / validate API                        │
└──────────────────────┬──────────────────────────────────────┘
                       │ 读写共享 DB
                       │
┌──────────────────────┴──────────────────────────────────────┐
│                      Bootstrap（Worker）进程                 │
│  ─ CDC MQ 消费器（IBM MQ JMS）                              │
│  ─ Cron 调度器                                              │
│  ─ API 触发端点                                             │
│  ─ Pipeline 执行引擎                                        │
│       订阅匹配 → pipeline 执行（事务） → 告警证据落库       │
│  ─ OTel 埋点导出                                            │
└─────────────────────────────────────────────────────────────┘
                       │
        ┌──────────────┴───────────────┐
        ▼                              ▼
   业务 DB / DataSource           Grafana / AlertManager
   （pipeline 事务范围）          （拉告警 API + 发送/路由）
```

当前实现中 controlplane 与 worker 共用一套 Spring 装配（`observe.worker.config`），`observe.bootstrap.controlplane` 包仅留 `package-info.java` 占位，未来按 profile 拆分时在此补 controlplane-only bean。

### 1.2 端到端数据流（CDC 场景）

```
CDC MQ（全量事件流，峰值 ~1k/s）
  │
  ▼
[CDC Source Adapter]  IbmMqCdcSource（JMS MessageListener）
  │  规整为 Event{ meta, before, after, op, sourceTs }
  │  批量累积（batchSize / batchTimeoutMillis）后投递
  ▼
[SourceDispatcher]（实现 EventListener）
  │ 对每条 event：
  │   1. SubscriptionMatcher.match(event) → 候选 (subscription, pipeline)
  │   2. 按 subscription.action 分流：
  │      - RUN      → 投线程池跑 pipelineRunner.run
  │      - SCHEDULE → DelayedActionHandler → InMemoryDelayedEventStore.schedule
  │      - CANCEL   → DelayedActionHandler → InMemoryDelayedEventStore.cancel
  ▼ 命中的 pipeline（RUN 路径）
[Pipeline Runner]
  │ 开事务（覆盖整个 pipeline）
  │ 创建 ExecutionContext{ meta, data }
  ▼
[Pipeline Executor（线性）]
  │ Node1 → Node2 → ... → NodeN（ScriptNode 执行 Groovy 脚本）
  │   脚本通过 ctx.set / ctx.get 交换数据
  │   脚本通过 alerts.emit 触发告警
  │   节点返回 NodeOutcome{ CONTINUE | SHORT_CIRCUIT }
  │   Runner 在事务内调 AlertSink.drainAndPersist(ctx)
  │     收集 Evidence，渲染 annotations，计算 fingerprint，
  │     INSERT alerts + alerts_evidence
  │   OTel 每次执行一个 root span（节点级 span 为远期项）
  ▼
Pipeline 提交事务（异常则回滚，告警和证据一起回滚）
  │
  ▼
告警在 alerts + alerts_evidence 表（firing 状态）
  │
  ▼ Grafana Infinity datasource 定期拉 GET /api/v1/alerts
  │
Grafana / AlertManager（发送、路由、去重、分组）
```

### 1.3 三类 Source 统一抽象

```
                        ┌─ IbmMqCdcSource（bootstrap） ── JMS consumer → onBatch
                        │   + InMemoryCdcSource（bootstrap，测试/占位）
Source（SPI，pipeline）─┼─ CronSource（pipeline） ──────── Scheduler tick → onBatch（TICK op）
                        │
                        └─ ApiSource（pipeline） ──────── HTTP POST → submit → onBatch（API op）
```

所有 Source 产出 `List<Event>` 经 `SourceDispatcher`（`EventListener` 实现）分发，pipeline 引擎不感知来源。

**IBM MQ CDC 语义**：`IbmMqCdcSource` 用 JMS `Session.CLIENT_ACKNOWLEDGE`，批量累积到 `batchSize` 或 `batchTimeoutMillis` 后 flush；flush 成功才 ack 批内**最后一条**消息（CLIENT_ACKNOWLEDGE 会 ack 整个 session 窗口）；listener 抛异常则不 ack，MQ 重投 → **at-least-once**。消息解析由 `IbmMqXmlParser`（`MessageParser<TextMessage>`）完成，带 XXE 防护；解析失败（`MessageParseException`）记日志后**不 ack**，触发重投。

### 1.4 关键决策一览

| 决策点 | 选择 |
|---|---|
| 触发源 | CDC（接 IBM MQ）+ cron + API，统一抽象为 `List<Event>` |
| 订阅匹配 | db/table/op 索引 + 字段级结构化 DSL 过滤（不用脚本引擎） |
| Event 模型 | `{meta, before, after, op, sourceTs}`，Map 承载，无 schema |
| 脚本引擎 | Groovy |
| 节点类型 | 单一 `script` 类型 |
| SQL/SP 调用 | `db` binding（`queryOne`/`queryAll`/`update`/`call`），命名参数防注入，复用 pipeline 事务；SP 一期纯查询型 |
| 告警触发 | 脚本调用 `alerts.emit(...)` |
| Context 可见性 | 隐式全 pipeline 可见，可选 `provides/reads` 元数据辅助 IDE/dry-run |
| Pipeline 执行 | MVP 线性；Node 抽象保留，预留 DAG 演进 |
| Context | meta（只读）+ data（可变：event、workingSpace、alerts、emittedAlert） |
| 事务边界 | 整个 pipeline 共享一个事务（单 DataSource 约束） |
| 告警存储 | alerts + alerts_evidence 两表，事务内原子写入 |
| 告警发送 | 不内置；提供 HTTP API 供 Grafana datasource 拉 |
| OTel | pipeline 级 root span + 关键 metrics（节点级 span 远期） |
| 配置存储 | 关系型 DB + 内嵌版本号 + 草稿/已发布两态 |
| 模块结构 | DDD 多 module（按 bounded context 划分） |
| 团队/应用 | 直接字符串，不引入团队表 |
| 进程模型 | controlplane 与 worker 分 module；初期同 JVM（profile 切换） |
| **外键约束** | **不使用 FK，引用完整性靠应用层保证** |
| **executions 写入** | **采样写入（pipeline 级 `executionLogSampleRatio`）；失败不写 executions（写 failed_executions）；emit alert 的成功 execution 强制落库** |
| **executions vs failed_executions** | **互斥分工（不双写）；trigger_event 按路径分流单份存储** |
| **alert FIRING→RESOLVED** | **`AlertResolveJob` 设计为后台 job 每分钟扫、每批 1000、`resolved_at=ends_at`；当前实现已建 bean 但未接 `@Scheduled`，翻转功能未生效（见 §4.5）** |
| **fingerprint 并发去重** | **应用层 SELECT-then-INSERT/UPDATE，无 DB 强约束，接受极端并发下重复行** |
| **alert ttl** | **优先级：脚本 emit 时传 ttl 用脚本值；否则按 severity 默认（C60/W30/I15 min）** |

---

## 2. 领域模型与核心抽象

> 跨 bounded context 的契约端口（`AlertSignal`/`Severity`/`AlertSink`/`AlertsApi`/`DbApi`/`ScriptContext`/`GroovyScriptEngine`/`ExecutionRecorder`/`TransactionOperator`/`ErrorType`）统一放在 `observe-kernel`。各业务 module 只通过 kernel 端口交互。

### 2.1 Event

`Event` 与嵌套 `EventMeta`（kernel，`event/model/Event.java`，`EventMeta` 是 `Event` 内嵌 record，非独立文件）：

```java
public record Event(
    EventMeta meta,
    Map<String, Object> before,   // 变更前快照（CDC UPDATE/DELETE 时有值）
    Map<String, Object> after,    // 变更后快照（CDC INSERT/UPDATE 时有值）
    Op op,                        // INSERT / UPDATE / DELETE / TICK / API / DELAYED
    Instant sourceTs
) {
    public record EventMeta(
        SourceType sourceType,
        String source,            // 具体 source 标识（mq queue、cron name、api name）
        String db, String table,
        Map<String, Object> attributes   // 透传属性（cdc position、延时元信息等）
    ) {}
}

public enum Op { INSERT, UPDATE, DELETE, TICK, API, DELAYED }
public enum SourceType { CDC, CRON, API }
```

- `before/after` 是 `Map<String, Object>`，无 schema
- cron/API 场景下 `before/after` 可为 null
- DELAYED 场景下 `before/after` 为 null，原 event 在 `meta.attributes.original_event` 内存引用

### 2.2 Source

`Source` SPI（pipeline，`application/Source.java`）：

```java
public interface Source {
    SourceType type();
    void start(EventListener listener);
    void stop();
}
public interface EventListener {
    void onBatch(List<Event> events);
}
```

实现分布在不同 module：

| 实现 | 模块 / 路径 | 说明 |
|---|---|---|
| `IbmMqCdcSource` | `observe-bootstrap/worker/source/` | 生产 CDC 消费器（JMS，见 §1.3） |
| `InMemoryCdcSource` | `observe-bootstrap/worker/source/` | 测试/占位 CDC source（`push(List<Event>)`） |
| `CronSource` | `observe-pipeline/infrastructure/source/` | 周期 tick（`Op.TICK`） |
| `ApiSource` | `observe-pipeline/infrastructure/source/` | HTTP 触发（`Op.API`，`submit(Event)`） |

`SourceDispatcher`（pipeline，`application/SourceDispatcher.java`，`implements EventListener`）统一接收所有 Source 的 `onBatch`，内部对每条 event 跑订阅匹配并按 action 分流（见 §1.2）。

### 2.3 Subscription（两种形态）

订阅领域模型存在**两个层**，由配置层加载器桥接，不要混淆：

#### 2.3a 持久化形态 —— `config.domain.Subscription`（observe-config）

扁平字段、直接对应 `subscriptions` 表，供 CRUD / 热加载使用：

```java
public record Subscription(
    String id, String pipelineId, int pipelineVersion,
    String mq, String topic, String db, String table,
    Set<Op> opTypes, SourceType sourceType,
    Condition fieldFilter,                         // 可空
    ActionType actionType,                         // RUN / SCHEDULE / CANCEL
    Duration scheduleDelay, String scheduleCorrelationKeyPath,
    String name, String description,
    Status status, String createdBy,               // ACTIVE / INACTIVE
    Instant createdAt, Instant updatedAt
) {
    public enum ActionType { RUN, SCHEDULE, CANCEL }
    public enum Status { ACTIVE, INACTIVE }
}
```

#### 2.3b 运行时形态 —— `pipeline.domain.subscription.Subscription`（observe-pipeline）

执行期使用的紧凑模型，把 source 字段聚合成 `SourceRef`、把 action 聚合成 `Action` sealed interface：

```java
public record Subscription(
    String id, String pipelineId, int pipelineVersion,
    SourceRef source, Condition fieldFilter, Action action
) {
    public record SourceRef(
        String mq, String topic, String db, String table,
        Set<Op> opTypes, SourceType sourceType
    ) {}
}
```

**字段级过滤 DSL**（`Condition` sealed interface，pipeline，独立于 Groovy 脚本引擎）：

```java
public sealed interface Condition permits Condition.And, Condition.Or, Condition.Compare, Condition.In {
    record And(List<Condition> children) implements Condition {}
    record Or(List<Condition> children) implements Condition {}
    record Compare(String field, Op op, Object value) implements Condition {}   // Op = EQ/NE/GT/GE/LT/LE
    record In(String field, Set<Object> values) implements Condition {}
    boolean matches(Event event);
}
```

> 注意 `Condition.Compare.Op`（EQ/NE/...）与 `kernel.event.model.Op`（INSERT/UPDATE/...）是两个不同枚举，勿混。

**延时 action**（`Action` sealed interface，见 §9）：

```java
public sealed interface Action permits Action.Run, Action.Schedule, Action.Cancel {
    record Run() implements Action {}
    record Schedule(Duration delay, String correlationKeyPath, String pipelineId) implements Action {}
    record Cancel(String correlationKeyPath) implements Action {}
}
```

**桥接**：`PipelineRegistryLoader.toPipelineSubscription(SubscriptionPo)` 把持久化形态转成运行时形态；`Action` 的 `Schedule.pipelineId` 永远取订阅外层 `pipelineId`。

**为什么订阅字段过滤不走脚本引擎**：
1. 热路径性能（每条 CDC 都要跑，需要 O(字段数) 快速求值）
2. 结构化 DSL 可建反向索引（事件特征 → 候选订阅）
3. 天然可序列化（JSON 存、JSON 取，`ConditionCodec`）
4. 前端表单友好（结构化表单 vs 代码编辑器）
5. 安全边界（订阅匹配比 pipeline 内脚本更靠近系统边界）
6. pipeline 内部用了 Groovy（更强表达力、更大攻击面），订阅层更要"轻、快、结构化"做前置过滤，把 ~90% 不相关事件挡在脚本引擎之外

### 2.4 ExecutionContext / ExecutionData

`ExecutionContext`（kernel 接口）暴露只读 meta + 可变 data，以及 output 命名空间读写和 alert 收集：

```java
public interface ExecutionContext {
    ExecutionMeta meta();
    ExecutionData data();
    void putOutput(String key, Object value);
    Object getOutput(String key);
    <T> T getOutput(String key, Class<T> type);
    <T> T getNodeOutput(String nodeName, String key, Class<T> type);
    Map<String, Map<String, Object>> nodeOutputs();
    void emitAlert(AlertSignal signal);
}
```

`ExecutionMeta`（kernel，record）—— pipeline 执行的不可变上下文：

```java
public record ExecutionMeta(
    String executionId, String pipelineId, int pipelineVersion,
    String team, String application,
    Map<String, String> pipelineLabels,    // 从 pipeline 拷贝，denormalize 历史保真
    String traceId, String spanId,
    SourceType triggerType, Event triggerEvent,
    Instant triggeredAt,
    String subscriptionId                  // null if not from CDC
) {}
```

`ExecutionData`（kernel，**class 非 record**，public 可变字段）—— 执行期可变状态：

```java
public class ExecutionData {
    public Event event;
    public Map<String, Map<String, Object>> workingSpace;   // nodeName → (key → value)
    public List<AlertSignal> alerts;
    public boolean emittedAlert;                            // AlertSink drain 后置 true，决定 execution 是否强制落库

    public List<AlertSignal> drainNewAlerts();              // 取走尚未落库的告警快照并清空
}
```

- `workingSpace` 按 `nodeName` 命名空间隔离
- `getNodeOutput(name, key, type)` 提供类型安全 + 自动转换（走 `TypeConverter`）
- 平台向脚本暴露 `ScriptContext` 适配器（见 §3.1），底层包装 `ExecutionContext`
- `pipelineLabels` 从 pipeline 拷贝到 ExecutionMeta，emit alert 时再拷贝到 alert.pipeline_labels（历史保真）
- `emittedAlert` 标志用于"emit alert 的成功 execution 强制落库"逻辑

### 2.5 Pipeline 与 NodeSpec

`Pipeline`（pipeline，`domain/Pipeline.java`，record）—— 执行模型：

```java
public record Pipeline(
    String id, int version,
    String team, String application,
    Map<String, String> labels,
    String name,
    Status status,
    List<NodeSpec> nodes,
    Instant createdAt, Instant publishedAt,
    double executionLogSampleRatio              // 采样率：0=NEVER, 1=ALWAYS, 0.1=10%
) {
    public enum Status { DRAFT, PUBLISHED, ARCHIVED }
}

public record NodeSpec(
    String name,
    String scriptSource,            // Groovy 源码
    ErrorPolicy errorPolicy,        // FAIL（默认）/ SKIP_NODE
    Set<String> provides,           // 可选：声明本节点写哪些 ctx key，供 IDE/dry-run 使用
    Set<String> reads               // 可选：声明本节点读哪些 ctx key，供 IDE/dry-run 使用
) {}

public enum ErrorPolicy { FAIL, SKIP_NODE }
```

- `provides/reads` 是**辅助元数据**，平台运行期不做强校验；前端编辑器用它们做变量提示和依赖可视化
- 一期 `nodes` 是有序线性列表；DAG 演进时换成 Graph，Node 抽象不变
- `executionLogSampleRatio` 控制本 pipeline 的 executions 采样率

### 2.6 Node 接口

```java
public interface Node {
    NodeOutcome execute(NodeSpec spec, ExecutionContext ctx) throws NodeExecutionException;
}
public enum NodeOutcome { CONTINUE, SHORT_CIRCUIT }
```

- 唯一内置实现：`ScriptNode`（执行 Groovy 脚本）
- `NodeOutcome.SHORT_CIRCUIT` 用于"命中即停"——脚本 `return true` 或 `AlertSpec.shortCircuit=true` 触发
- Node 接收 `NodeSpec` 参数（而非构造时绑定），允许同一 Node 实例服务多个 NodeSpec

### 2.7 AlertSignal + Evidence

```java
public record AlertSignal(
    String fingerprint,            // 可空，落库时算
    Severity severity,
    Map<String, String> labels,    // 告警路由 labels（Grafana 用）
    Map<String, Object> annotations,
    EvidenceSpec evidence,
    boolean shortCircuit,          // 是否同时让 pipeline 短路
    Duration ttl                   // 可空；不传则按 severity 默认（C60/W30/I15 min）
) {
    public enum Severity { INFO, WARNING, CRITICAL }
    public record EvidenceSpec(
        List<String> capture,      // 从 workingSpace 按 key 取
        boolean attachOutputs,     // 默认 true，按节点捕获
        boolean attachTriggerEvent // 默认 true
    ) {}
}
```

> `AlertSpec`（脚本 `alerts.emit` 入参）字段与 `AlertSignal` 一一对应，复用 `AlertSignal.EvidenceSpec`（无独立 `AlertSpec.EvidenceSpec`）。

持久化实体（observe-alerting，`domain/`）：

```java
public record AlertEntity(
    String id,
    String team, String application,
    Map<String, String> pipelineLabels,
    String pipelineId, int pipelineVersion,
    String executionId,            // NOT NULL：必然由 execution 产生
    String fingerprint,
    Severity severity,
    Map<String, String> labels,
    Map<String, String> annotations,
    Instant startsAt,              // 首次 emit，永不改
    Instant lastSeenAt,            // 每次 emit 更新
    Instant endsAt,                // 每次 emit 更新 = lastSeenAt + ttl
    Instant resolvedAt,            // 翻转 job 写 = ends_at（当前未生效，见 §4.5）
    AlertStatus status,
    int dedupCount,                // 每次 emit +1
    String generatorURL,           // 注：PO 字段名为 generatorUrl，DB 列 generator_url
    String traceId
) {}
public enum AlertStatus { FIRING, RESOLVED }

public record EvidenceEntity(
    String alertId,
    String pipelineId, int pipelineVersion, String executionId,
    String nodeName,               // 只留 node_name（无 node_id）
    // trigger_event 不存（JOIN executions 取）
    Map<String, Object> outputs,   // 合并 rule_context + outputs
    String traceId, String spanId,
    Instant capturedAt,
    boolean truncated
) {}
```

> `alerts_evidence` 表另有 `size_bytes INT NOT NULL` 列（PO 有、`EvidenceEntity` 无），由 `DefaultAlertSink.persistEvidence` 在落库时按真实字节数回填。

**两层 labels 不混**：
- `pipelineLabels`：pipeline 自身的业务标签（创建 pipeline 时填的，emit alert 时从 ExecutionMeta 拷贝——历史保真，pipeline 后续改 labels 不影响历史 alert）
- `labels`：单条告警的路由 labels（`alerts.emit` 时声明的，给 Grafana/AlertManager 用）

**ttl 优先级**：脚本 emit 时传 ttl 用脚本值；否则按 severity 默认（CRITICAL 60min / WARNING 30min / INFO 15min）。

### 2.8 Executor SPI

```java
public interface PipelineRunner {
    void run(Pipeline pipeline, Event triggerEvent, String subscriptionId);
}
public interface PipelineExecutor {
    PipelineOutcome execute(Pipeline pipeline, ExecutionContext ctx);
}
public enum PipelineOutcome { SUCCESS, SHORT_CIRCUITED, FAILED }
```

- `PipelineRunner`（实现 `DefaultPipelineRunner`）：事务、span、context 创建、execution 记录
- `PipelineExecutor`（实现 `LinearPipelineExecutor`）：跑节点序列（在事务内）
- 两层职责分开，DAG 演进时只换 Executor
- `AlertSink`（kernel 端口，实现 `DefaultAlertSink`）是 Spring bean，Runner 的协作者，**不是 Node**

---

## 3. Groovy 脚本引擎与运行时

### 3.1 脚本绑定（用户视角的 stdlib）

平台在 Groovy 脚本执行前注入这些 binding：

| 绑定 | 类型 | 说明 |
|---|---|---|
| `event` | `Event` | 触发事件，`event.after.xxx` / `event.before.xxx` / `event.meta.xxx` / `event.op` / `event.sourceTs` |
| `ctx` | `ScriptContext` | `ctx.get(key)` / `ctx.get(key, Class<T>)` / `ctx.set(key, value)` / `ctx.keys()` |
| `alerts` | `AlertsApi` | `alerts.emit(AlertSpec)` / `alerts.emit(severity, labels, annotations, evidence)` |
| `db` | `DbApi` | `db.queryOne(sql, params)` / `queryAll` / `update` / `call(spName, params)`；命名参数防注入；跑在 pipeline 事务内；SP 一期纯查询型 |
| `now` | `() -> Instant` | 当前时间 |

> 一期脚本支持上述 5 个 binding。`db` 返回 `Map`/`List<Map>`，脚本用 Groovy Map 语法糖（`row.field`）+ `as BigDecimal`（走 TypeConverter）处理，不引入实体类。`thresholds` binding 不在一期（阈值靠脚本硬编码）。脚本若调用未提供的变量，Groovy 抛 `MissingPropertyException`（明确失败）。

相关 SPI 签名（kernel）：

```java
public interface ScriptContext {
    Object get(String key);
    <T> T get(String key, Class<T> type);
    void set(String key, Object value);
    Set<String> keys();
}

public interface AlertsApi {
    void emit(AlertSpec spec);
    void emit(Severity severity, Map<String,String> labels,
              Map<String,String> annotations, AlertSignal.EvidenceSpec evidence);
}

public interface DbApi {
    Map<String,Object> queryOne(String sql, Map<String,Object> params);   // 无结果 → null
    List<Map<String,Object>> queryAll(String sql, Map<String,Object> params);
    int update(String sql, Map<String,Object> params);
    List<Map<String,Object>> call(String spName, Map<String,Object> params);  // SP 结果集；一期不支持 OUT
}
```

> `AlertsApi.emit` 便捷重载的 `annotations` 是 `Map<String,String>`（脚本侧字符串），而 `AlertSpec.annotations` 是 `Map<String,Object>`（平台侧允许 GString，渲染时 toString）——重载内部做适配。

### 3.2 TypeConverter

```java
public final class TypeConverter {
    public static Object convert(Object value, Class<?> targetType);
    public static BigDecimal toDecimal(Object value);
    public static Long toLong(Object value);
    public static Integer toInteger(Object value);
    public static Instant toInstant(Object value);
    public static String toStringValue(Object value);
}
```

处理 CDC 常见坑：字符串数字 → Long/Integer/Decimal、Decimal 类型统一、时间戳（long/string）→ Instant、null → null。脚本里 `as Long` / `as BigDecimal` 通过 Groovy 的类型转换走 `TypeConverter`。

### 3.3 Groovy 引擎封装

`GroovyScriptEngine` 是 kernel 接口，实现 `GroovyScriptEngineImpl`（pipeline，`infrastructure/script/`）：

```java
public interface GroovyScriptEngine {
    Object execute(String scriptSource, ScriptContext ctx);
    void compile(String scriptSource);
}
```

**安全约束**（设计水位：AST 白名单 + receiver 黑名单 + statement 黑名单 + timeout + 语句数上限）：
- import 白名单（`java.lang.Math`、`java.time.*`、`java.math.*`、`java.util.{Map,List,Set}` 等）
- receiver 黑名单：`System` / `Runtime` / `Thread` / `Class` / `ClassLoader` / `ProcessBuilder` / `ScriptEngine` / `GroovyClassLoader`
- 禁用语句：`while` / `doWhile` / 无界 `for(;;)`（for-each 集合遍历允许）
- 语句数上限 200（防巨型脚本）
- 单节点执行 timeout 5s（`TIMEOUT_MS = 5_000`，`Thread.interrupt()` + watchdog）
- 编译缓存：`sourceHash → Class<?>`
- `Script` 实例不跨线程复用，每次执行 new 一个 instance

比表达式引擎强，比 GraalJS 弱。适合部门内可信规则作者；不可信场景的沙箱演进是远期项。

### 3.4 编译期校验

保存 pipeline 时（`PipelineValidator`）静态校验：
- Groovy 能编译（`engine.compile(scriptSource)`）
- 沙箱白名单检查（无禁用 import / receiver / statement）
- 语句数 ≤ 200
- 节点脚本非空

校验失败返回错误列表（`ValidationResult{ ok, errors }`）。

### 3.5 完整 pipeline 结构

pipeline 由若干 `NodeSpec`（name + Groovy 脚本 + errorPolicy + 可选 provides/reads）线性组成。脚本内通过 `event.after.*` 取字段、用硬编码阈值，或用 `db.queryOne(sql, [param: value])` 回查 DB，命中条件时 `alerts.emit(severity: ..., labels: [...], annotations: [...], evidence: [...], shortCircuit: true)` 触发告警并 `return true` 短路。延时触发场景见 §9。

---

## 4. Pipeline 执行引擎

### 4.1 整体执行流程

```
[Source Adapter] ── 产出 List<Event> ──→
[SourceDispatcher] ── 对每条 event：
    │   SubscriptionMatcher.match(event) → 候选 (subscription, pipeline)
    │   按 subscription.action 分流：
    │     RUN      → 投 pipelineRunnerPool → PipelineRunner.run
    │     SCHEDULE → DelayedActionHandler → InMemoryDelayedEventStore.schedule
    │     CANCEL   → DelayedActionHandler → InMemoryDelayedEventStore.cancel
[Pipeline Runner]（对每个 RUN 命中的 pipeline）:
    │
    1. 创建 ExecutionContext（meta + data，triggerEvent 装入 data.event）
    2. 开事务（TransactionOperator.execute）
    3. 创建 OTel root span（pipeline 级）
    4. PipelineExecutor.execute(pipeline, ctx)：
         for each NodeSpec:
            a. ScriptNode.execute(spec, ctx)  ← 注入 bindings → groovyEngine.execute
            b. NodeOutcome == SHORT_CIRCUIT → break
            （ErrorPolicy.SKIP_NODE 时节点异常降级为 CONTINUE）
    5. AlertSink.drainAndPersist(ctx)         ← 事务内落库本 pipeline emit 的 alert
    6. 提交事务（异常则回滚）
    7. 关闭 root span
    8. 记录 metrics
    9. ExecutionRecorder.recordSuccess(...)   ← 采样写 executions（emit alert 时强制写）
       ── 失败时不写 executions，recordFailure 写 failed_executions（互斥）
```

### 4.2 DefaultPipelineRunner

`DefaultPipelineRunner implements PipelineRunner`（pipeline，`infrastructure/engine/`）。构造注入四个协作者：

- `PipelineExecutor executor`
- `AlertSink alertSink`
- `TransactionOperator transactionOperator`（端口，bootstrap 注入 `SpringTransactionOperator`）
- `ExecutionRecorder executionRecorder`（端口，bootstrap 注入 `JpaExecutionRecorder`）

职责：在 `transactionOperator.execute(...)` 事务内跑 `executor.execute(pipeline, ctx)`，再 `alertSink.drainAndPersist(ctx)`；成功后按 `pipeline.executionLogSampleRatio()` 采样落 executions（`ctx.data().emittedAlert` 为 true 时强制落库），异常时走 `recordFailure` 写 failed_executions。业务代码直接用 OTel API（`GlobalOpenTelemetry`），counter `pipeline.executions` / `pipeline.failures`。

> `transactionOperator` 是端口：生产装配 `SpringTransactionOperator`（跑完提交），dry-run 装配 `DryRunTransactionOperator`（跑完 `setRollbackOnly`）。pipeline 不感知事务怎么开/回滚，只声明"这段要在事务里"。

### 4.3 LinearPipelineExecutor

`LinearPipelineExecutor implements PipelineExecutor`（pipeline，`infrastructure/engine/`）。构造注入 `Function<NodeSpec, Node> nodeFactory`（bootstrap 装配为 `spec -> new ScriptNode(...)`）。

职责：线性遍历 `pipeline.nodes()`，对每个 `NodeSpec` 调 `node.execute(spec, ctx)`；遇 `NodeOutcome.SHORT_CIRCUIT` 提前返回 `SHORT_CIRCUITED`；全部跑完返回 `SUCCESS`。节点抛 `NodeExecutionException` 时按 `spec.errorPolicy()` 处理：`SKIP_NODE` 降级为 `CONTINUE` 继续，`FAIL`（默认）抛给 Runner → 事务回滚 + failed_executions。告警落库（`AlertSink.drainAndPersist`）由 Runner 在事务内调用，Executor 不感知。

### 4.4 ScriptNode

`ScriptNode implements Node`（pipeline，`infrastructure/engine/`）。构造注入三个依赖（Supplier/Function 模式，允许同一实例服务多个 NodeSpec）：

- `GroovyScriptEngine engine`
- `Function<ExecutionContext, AlertsApi> alertsApiFactory`（生产 `DefaultAlertsApi`，dry-run `DryRunAlertsApi`）
- `Supplier<DbApi> dbApiSupplier`（生产 `JdbcDbApi` 复用 pipeline 事务，dry-run `NoopDbApi`）

职责：`execute(spec, ctx)` 时向 `ScriptContext` 注入 5 个 binding（`event`/`ctx`/`alerts`/`db`/`now`），调 `engine.execute(spec.scriptSource(), scriptCtx)`；若结果为 `true` 或本次产生了 `shortCircuit=true` 的 alert，返回 `SHORT_CIRCUIT`。

### 4.5 AlertSink

`DefaultAlertSink implements AlertSink`（observe-alerting，`infrastructure/DefaultAlertSink.java`，在 `AlertingPipelineConfig` 装配）。构造注入 `AlertRepository`、`EvidenceRepository`、`EvidenceCollector`、`AnnotationRenderer`。

`drainAndPersist(ctx)` 流程：
1. `ctx.data().drainNewAlerts()` 取走未落库告警，置 `ctx.data().emittedAlert = true`
2. 对每条 `AlertSignal`：
   - `EvidenceCollector.collect(signal, ctx)` 收集证据（按节点 outputs + capture keys，超 256KB 截断 + `truncated=true`）
   - `AnnotationRenderer.render(signal.annotations(), ctx)` 渲染 annotations（值 toString）
   - fingerprint：`signal.fingerprint()` 非空用脚本值，否则 `AlertFingerprintCalculator.compute(pipelineId, labels)`（`"pipeline=<id>;<k>=<v>;..."` 排序后 sha256）
   - ttl：`signal.ttl()` 非空用脚本值，否则按 severity 默认（C60/W30/I15 min）；`endsAt = now + ttl`
   - **应用层 SELECT-then-INSERT/UPDATE**：`findFirstByFingerprintAndStatusOrderByIdAsc(fingerprint, "FIRING")` 命中 → `updateEmit`（更新 `last_seen_at`/`ends_at`/`dedupCount+1`）；未命中 → 新建 `AlertEntity`（UUID id、status FIRING、dedupCount 1）`save` + 落 evidence（`size_bytes` 按真实字节数回填）

**fingerprint 并发去重**：简单 SELECT-then-INSERT/UPDATE，无锁无唯一约束；接受极端并发下可能产生重复 FIRING 行（影响可控，Grafana 展示重复但 fingerprint 相同运营能识别）。普通索引 `(fingerprint, status)` 支撑 SELECT。不依赖 DB 部分唯一索引（Sybase 不支持）。

**ends_at 顺延**：每次 emit 推到 `last_seen_at + ttl`。

**事务原子性**：AlertSink 在 pipeline 事务内写入 `alerts + alerts_evidence`。pipeline 抛异常 → 事务回滚 → 告警和证据一起回滚。死信路径（§4.7）独立兜底失败记录。

**FIRING → RESOLVED 翻转（`AlertResolveJob`）—— ⚠️ 当前未生效**：

`AlertResolveJob`（observe-alerting，`infrastructure/AlertResolveJob.java`）设计为后台 job：每分钟扫 `status='FIRING' AND ends_at < now()`，每批 1000 行，`UPDATE status='RESOLVED', resolved_at=ends_at`，循环直到无过期 FIRING。

> **当前实现状态**：`AlertResolveJob` bean 已在 `AlertingPipelineConfig.alertResolveJob(...)` 装配，类提供了 `resolveExpiredAlerts()` 方法，但**没有任何 `@Scheduled` 方法调用它**（`AlertResolveJob` 类本身无 `@Scheduled` 注解，config 里也未注册定时触发）。因此 FIRING→RESOLVED 翻转功能当前未生效，alert 会一直停留在 FIRING。这是待接线项。

### 4.6 异常处理矩阵

| 异常类型 | error_type 枚举 | 处理策略 |
|---|---|---|
| `ScriptCompilationException`（Groovy 编译失败） | SCRIPT_COMPILATION | 保存时拒绝；运行期不应出现 |
| `ScriptSandboxException`（违反白名单） | SCRIPT_SANDBOX | 保存时拒绝；运行期不应出现 |
| `ScriptTimeoutException`（5s 超时） | SCRIPT_TIMEOUT | FAIL + 中断线程 + 进 `failed_executions` |
| `ScriptExecutionException`（运行期异常） | SCRIPT_EXECUTION | 默认 FAIL pipeline；可配置 SKIP_NODE；FAIL 时进 `failed_executions` |
| `NodeExecutionException`（包装业务异常，带 nodeName） | NODE_EXECUTION | 默认 FAIL；可配置 SKIP_NODE；FAIL 时进 `failed_executions` |
| `AlertPersistenceException` | — | FAIL（事务回滚）；走 OTel metric 告警，不落 `failed_executions` |
| `DataSourceException` | — | FAIL + 熔断；走 OTel metric 告警，不落 `failed_executions` |
| `MessageParseException`（CDC 解析失败） | — | source adapter 层不 ack → MQ 重投；不落 `failed_executions` |
| Evidence 超过大小上限 | — | 截断 + truncated=true，**不 FAIL** |
| `RuntimeException`（未预期） | UNKNOWN | FAIL（平台兜底） + 进 `failed_executions` |
| pipeline 总超时 30s 强制中断 | PIPELINE_TIMEOUT | 进 `failed_executions` |
| 优雅关闭 60s 强制中断 | GRACEFUL_SHUTDOWN_KILL | 进 `failed_executions` |

> `ErrorType` 枚举值：`SCRIPT_COMPILATION / SCRIPT_SANDBOX / SCRIPT_TIMEOUT / SCRIPT_EXECUTION / NODE_EXECUTION / PIPELINE_TIMEOUT / GRACEFUL_SHUTDOWN_KILL / UNKNOWN`。`AlertPersistenceException` / `DataSourceException` / `MessageParseException` 有异常类但**不进** `ErrorType`（走 metric，不落死信）。

`errorPolicy` 支持 `FAIL`（默认）和 `SKIP_NODE`。

### 4.7 死信队列

失败执行不入 executions 表，进死信表（`failed_executions`），用于：
1. **排查**：前端查看失败原因（含 trigger_event、failed_node、stack_trace、trace_id）
2. **告警**：OTel 监控 `pipeline.failures` / `dead_letter.pending` metric，超阈值给平台运维告警

**死信进入路径（3 类）**：

| 路径 | error_type |
|---|---|
| 脚本异常 / 节点执行异常 / 脚本超时（FAIL 策略） | SCRIPT_EXECUTION / NODE_EXECUTION / SCRIPT_TIMEOUT |
| pipeline 总超时 30s 强制中断 | PIPELINE_TIMEOUT |
| 优雅关闭 60s 强制中断 | GRACEFUL_SHUTDOWN_KILL |

**不进入死信的失败**：
- DataSource 熔断 / AlertPersistence 异常：单 worker + 单 DataSource 下走 OTel metric 告警，不落 `failed_executions`
- 线程池队列满（§4.8）：拒绝时记 metric + 日志，不落死信
- Event 大小超限（§7.1）：source adapter 层拒绝 + 日志，不落死信

**executions vs failed_executions 互斥分工**：

| 表 | 写入规则 |
|---|---|
| executions | 采样写入（pipeline 级 `executionLogSampleRatio`）；emit alert 时强制 100% 落库（evidence 需要 trigger_event 来源） |
| failed_executions | 全量写入失败（100%，不受 sample_ratio 影响） |

- 同一 execution 只写其中一张表（互斥）
- trigger_event 单份存储（按路径分流到 executions 或 failed_executions）
- alerts_evidence 不存 trigger_event（JOIN executions 取——emit alert 的 execution 强制落库，必然能取到）

**一期不做手动重试 API**：`failed_executions` 表保留 `status(PENDING/RESOLVED/IGNORED)` 用于运营标记，但一期不提供 `POST /api/v1/failed-executions/{id}/retry` 接口。手动重试随 Admin UI 一起做（远期）。

一期用 DB 表实现（不引入独立 MQ）。

### 4.8 并发模型

```
Source Adapter（CDC consumer / cron / api）
  │ 投递 List<Event> → onBatch
  ▼
[SourceDispatcher]（dispatcher 线程内同步）
  │ SubscriptionMatcher.match(event)
  │ 按 action 分流：
  │   RUN → pipelineRunnerPool.submit(...)
  │   SCHEDULE/CANCEL → DelayedActionHandler（同步）
  ▼
[Pipeline Runner Pool]（pipelineRunnerPool，core/max/queue 可配，每个 pipeline 一个 task）
```

- 订阅匹配在 dispatcher 线程内同步进行（不单设 subscription-matcher 线程池）
- 同一 pipeline 多次执行可能并发，**不保证事件顺序**，规则靠 fingerprint 兜底
- 跨 pipeline 完全独立
- DB 连接池大小 = runner 线程数 + 余量
- MQ 消费者侧 prefetch / batch 限制，避免内存爆炸
- 线程池队列上限（`runner-queue`，默认 1000；超出拒绝 + 记 metric/日志，不阻塞 MQ）

### 4.9 优雅关闭

`WorkerShutdown`（`@PreDestroy`，bootstrap）协调关闭：

1. 停止所有 Source（`List<Source>`，含 MQ consumer / cron / api）
2. `pipelineRunnerPool.shutdown()` + await（最长 `GRACE_SECONDS=60`），未完成则 `shutdownNow()`
3. `delayedStore.shutdown()`（关闭延时 SES）
4. 未完成的 pipeline 走强制中断 + 事务回滚 + 进 `failed_executions`（error_type=GRACEFUL_SHUTDOWN_KILL）
5. OTel exporter flush、线程池关闭

### 4.10 幂等性

- pipeline 执行**不是严格幂等的**——脚本节点的 `db.update(...)` / `db.call(...)` 写入 DB 可能有副作用
- 一期约束：pipeline 应设计成近似幂等（告警靠 fingerprint 去重，SQL 副作用靠业务约束）
- 严格幂等（执行前查 executions 去重）推后

---

## 5. 可观测性（OTel 埋点）

> 业务代码（`DefaultPipelineRunner` 等）直接用 `opentelemetry-api`（`GlobalOpenTelemetry.getTracer()` / `getMeter()`）。一期未接真后端，`GlobalOpenTelemetry` 默认 noop（行为等价不发数据）；接 Prometheus/Jaeger 时在 bootstrap 装配 OTel SDK，业务代码不改。kernel 不引 OTel（零依赖红线）。

### 5.1 Span 层级

```
pipeline.execute (root span，DefaultPipelineRunner 创建)
  └─ alert.persist (span, 每次 emit 一个)
```

> 节点级 span（`node.compute_total` / `node.check_fraud`）为远期项，当前未实现。

### 5.2 Span 属性

**基础属性（所有 span）**：`pipeline.id` / `pipeline.version` / `pipeline.team` / `pipeline.application` / `execution.id` / `trace.id` / `trigger.type`

**Alert span 额外**：`alert.fingerprint` / `alert.severity` / `alert.deduplicated`

**CDC 触发还带**：`event.source` / `event.db` / `event.table` / `event.op` / `subscription.id`

### 5.3 Metrics

**Counters**：
```
pipeline.executions{pipeline_id, status=success|short_circuited|failed}
pipeline.failures{pipeline_id, error_type=...}
subscriptions.matched{subscription_id}
alerts.triggered{pipeline_id, severity}
alerts.deduplicated{pipeline_id}
alerts.resolved{pipeline_id}
```

**Histograms**（buckets: [5ms, 10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s]）：
```
pipeline.duration{pipeline_id}
node.duration{pipeline_id, node_name}
```

**Gauges**：
```
pipeline.queue.depth{source_type}
dead_letter.pending
active.pipelines.executing
```

### 5.4 异常埋点

```java
span.recordException(exception);
span.setStatus(StatusCode.ERROR, exception.getMessage());
span.setAttribute("error.type", exception.getClass().getSimpleName());
```

### 5.5 TraceId 透传

落库到：`executions.trace_id`、`alerts.trace_id`、`alerts_evidence.trace_id`、`failed_executions.trace_id`。

### 5.6 OTel 配置（规划）

```yaml
otel:
  exporter:
    type: otlp
    endpoint: http://otel-collector:4317
  service:
    name: a-solid-observe-worker
  traces:
    sampler: parentbased_traceidratio
    ratio: 0.1
    always_sample_roots: [error, short_circuited]
  metrics:
    interval: 60s
```

`ratio: 0.1` 而非全采样——1k events/s × N pipelines × ~3 spans/pipeline 全采会打爆 collector heap。错误和 short-circuit 的 root span 强采，保证异常链路 100% 可观测。

---

## 6. 领域模型与模块结构

### 6.1 Maven Module 结构

```
a-solid-observe/
├── observe-kernel/                  ← 共享内核（零业务依赖）
│   └── event/model/                 ← Event(+EventMeta), Op, SourceType, ExecutionMeta, ExecutionData, ExecutionContext
│       alert/model/                 ← AlertSignal, AlertSpec, Severity
│       alert/spi/                   ← AlertsApi, AlertSink
│       script/spi/                  ← DbApi, ScriptContext, GroovyScriptEngine
│       execution/spi/               ← ExecutionRecorder
│       execution/model/             ← ErrorType
│       transaction/spi/             ← TransactionOperator
│       error/                       ← ObserveException + 8 子类
│       util/                        ← JsonUtil, HashUtil, TypeConverter, Map↔JSON converter
│
├── observe-pipeline/                ← 核心 domain + 执行
│   ├── domain/                      ← Pipeline, NodeSpec, NodeOutcome, ErrorPolicy,
│   │   │                               Execution, FailedExecution
│   │   └── subscription/            ← Subscription(运行时), SourceRef, Condition, Action
│   ├── application/                 ← Source, EventListener, SourceDispatcher,
│   │                                   SubscriptionMatcher, DelayedActionHandler,
│   │                                   PipelineRunner, PipelineExecutor, PipelineOutcome,
│   │                                   PipelineRegistry, Node, ExecutionQueryService, DryRunService
│   └── infrastructure/
│       ├── engine/                  ← DefaultPipelineRunner, LinearPipelineExecutor, ScriptNode
│       ├── script/                  ← DefaultExecutionContext, DefaultScriptContext,
│       │                               GroovyScriptEngineImpl, DryRunAlertsApi
│       ├── source/                  ← ApiSource, CronSource, EventPaths, MessageParser
│       ├── subscription/            ← DefaultSubscriptionMatcher
│       ├── persistence/             ← ExecutionPo/Repository, FailedExecutionPo/Repository,
│       │                               JpaExecutionRecorder
│       ├── delayed/                 ← InMemoryDelayedEventStore
│       └── transaction/             ← SpringTransactionOperator, DryRunTransactionOperator
│
├── observe-alerting/                ← 告警领域
│   ├── domain/                      ← AlertEntity, EvidenceEntity, AlertStatus
│   ├── application/                 ← AlertQueryService
│   └── infrastructure/
│       ├── DefaultAlertSink, AlertResolveJob
│       ├── alert/                   ← DefaultAlertsApi
│       ├── evidence/                ← EvidenceCollector, AnnotationRenderer
│       ├── fingerprint/             ← AlertFingerprintCalculator
│       └── persistence/             ← alert/(AlertPo/Mapper/Repository), evidence/(EvidencePo/Mapper/Repository)
│
├── observe-config/                  ← 规则配置领域
│   ├── domain/                      ← PipelineDefinition, PipelineVersion, Subscription(持久化)
│   ├── application/                 ← PipelineCrudService, VersionPublishService,
│   │                                   SubscriptionCrudService, PipelineRegistryLoader,
│   │                                   PipelineHotReloader, ConfigLoader, PipelineValidator
│   └── infrastructure/
│       ├── ConditionCodec           ← Condition sealed interface 多态序列化协议
│       └── persistence/             ← pipelines / pipeline_versions / subscriptions 的 PO/Mapper/Repository,
│                                       StringSetToCsvConverter
│
├── observe-controlplane/            ← 薄适配器层（接口契约，无 domain/application）
│   ├── interfaces/                  ← REST controllers, DTOs（interfaces/dto/）
│   └── config/                      ← GlobalExceptionHandler
│
└── observe-bootstrap/               ← main 入口 + 装配
    ├── ObserveApplication           ← @SpringBootApplication（worker + controlplane 同 JVM，profile all-in-one）
    ├── controlplane/                ← 仅 package-info.java 占位（未来拆 controlplane-only bean）
    ├── demo/                        ← DemoMain, DemoPipelineFactory（独立 main，内嵌 Noop 实现，无 Spring）
    ├── worker/
    │   ├── source/                  ← IbmMqCdcSource, IbmMqXmlParser, InMemoryCdcSource
    │   ├── db/                      ← JdbcDbApi（db binding 的 JDBC 实现，复用 pipeline 事务）
    │   └── config/                  ← WorkerConfig, CoreConfig, AlertingPipelineConfig,
    │                                   WorkerProperties, IbmMqProperties, WorkerShutdown
```

> 不单设 `interfaces/` 层：pipeline/alerting/config 的 application 层 public service 即内部 API。controlplane 是薄适配器，`interfaces/` 是其适配器目录（非业务 module 的 interfaces）。

模块职责要点：
- kernel 承载跨 bounded context 的契约端口。pipeline 只通过 kernel 端口调用，不直接依赖 alerting/config。
- pipeline 直接引 `opentelemetry-api`（默认 noop），kernel 不引 OTel（零依赖红线）。
- 一期脚本支持 `event`/`ctx`/`alerts`/`db`/`now` 五个 binding（见 §3.1）。`db` 接口在 kernel，实现在 bootstrap（`JdbcDbApi`，复用 pipeline 事务），dry-run 用 pipeline 内嵌 Noop 实现。
- `ConditionCodec` 保留（Condition sealed interface 的多态序列化协议，服务 `subscriptions.field_filter` 列）。
- 无 UDF registry / @Udf 注解 / 自描述端点。

### 6.2 依赖方向（强约束）

```
controlplane → config → pipeline → kernel ← alerting
                                  (AlertSink 接口端口)
            ↘ alerting → kernel
bootstrap → 所有 module（装配层）
```

- `kernel` 不依赖任何其他 module
- `pipeline` 依赖 `kernel`，**不依赖** `config` / `alerting`
- `alerting` 独立于 `pipeline`（alerting 实现 kernel `AlertSink` 端口；pipeline 只通过接口拿 AlertSignal，不感知 AlertEntity / AlertPo）
- `config` 依赖 `pipeline`（用 pipeline 的领域模型）
- `controlplane` 依赖 `config` 和 `alerting` 的 application 接口（以及 pipeline 的 `ApiSource`、`DryRunService`、`ExecutionQueryService`）
- `bootstrap` 装配所有（含把 alerting 的 `DefaultAlertSink` bean 注入 pipeline）

### 6.3 持久化形态

```java
// observe-config 里的持久化模型
public record PipelineDefinition(
    String id, String team, String application, Map<String,String> labels,
    String name, String description, String createdBy,
    Status status, Integer currentVersion,
    Instant createdAt, Instant updatedAt
) { public enum Status { DRAFT, PUBLISHED, ARCHIVED } }

public record PipelineVersion(
    String pipelineId, int version,
    String definitionJson,         // 完整 Pipeline 模型 JSON（含 nodes[].script）
    String definitionHash,         // SHA256(definition_json)，编译缓存命中 + 去重
    Status status,
    String publishedBy, Instant createdAt, Instant publishedAt
) { public enum Status { DRAFT, PUBLISHED, ARCHIVED } }
```

**Pipeline（执行模型，observe-pipeline）vs PipelineDefinition/PipelineVersion（持久化形态，observe-config）分离**：
- `Pipeline` 从 `PipelineVersion.definitionJson` 反序列化得到，worker 用
- 执行引擎不感知"哪个版本是 current"、"怎么序列化"
- 配置领域不感知执行细节

### 6.4 数据库 Schema

迁移工具用 Flyway，脚本按模块放在 `db/migration/<module>/`。

**约定**：
- 不使用外键（FK），引用完整性靠应用层保证
- 不使用部分索引（Sybase 不支持）
- JSON 字段用 `LONG VARCHAR` / `VARCHAR(n)`（Sybase 无 JSON 类型，应用层做序列化/反序列化）
- 时间戳命名：alerts 用 `starts_at` / `ends_at`（对齐 AlertManager 协议）；其他表用 `started_at` / `ended_at`

#### 6.4.1 `pipelines`

```sql
CREATE TABLE pipelines (
    id VARCHAR(64) PRIMARY KEY,
    team VARCHAR NOT NULL,
    application VARCHAR NOT NULL,
    labels VARCHAR(16384),                  -- JSON 字符串；上限 16KB
    name VARCHAR NOT NULL,
    description VARCHAR,                    -- 可选说明
    status VARCHAR NOT NULL,                -- DRAFT / PUBLISHED / ARCHIVED
    current_version INT,                    -- DRAFT 阶段 NULL；PUBLISHED 后必填
    created_by VARCHAR,                     -- 审计
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_pipelines_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);

CREATE INDEX idx_pipelines_team_app ON pipelines(team, application);
CREATE INDEX idx_pipelines_status_updated ON pipelines(status, updated_at);
```

- `current_version` 与 `pipeline_versions.status='PUBLISHED'` 的一致性靠应用层同事务保证
- 应用层：删 pipeline 前校验无 PUBLISHED version；强制软删（status=ARCHIVED）

#### 6.4.2 `pipeline_versions`

```sql
CREATE TABLE pipeline_versions (
    pipeline_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    definition_json LONG VARCHAR NOT NULL,  -- 含 Groovy 脚本源码；上限 64KB
    definition_hash VARCHAR(64) NOT NULL,   -- SHA256(definition_json)
    status VARCHAR NOT NULL,                -- DRAFT / PUBLISHED / ARCHIVED
    published_by VARCHAR,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    PRIMARY KEY (pipeline_id, version),
    CONSTRAINT ck_pv_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);

CREATE INDEX idx_pv_status_published ON pipeline_versions(status, published_at DESC);
```

- `version` 自增策略：应用层 `SELECT max(version)+1 WHERE pipeline_id=?` within txn
- 应用层：校验 pipeline_id 存在
- `definition_hash` 支撑编译缓存跨 version 共享 + 去重展示

#### 6.4.3 `subscriptions`

```sql
CREATE TABLE subscriptions (
    id VARCHAR(64) PRIMARY KEY,
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version INT NOT NULL,

    -- source 拆列（原 source_ref JSON 拆为一级列，支撑热路径订阅匹配）
    mq VARCHAR,
    topic VARCHAR,
    db VARCHAR,
    table_name VARCHAR,                    -- 改名避保留字（原 table）
    op_types VARCHAR(256),                 -- CSV 字符串（如 INSERT,UPDATE），StringSetToCsvConverter 解析
    source_type VARCHAR,                   -- CDC / CRON / API

    -- 字段级过滤
    field_filter LONG VARCHAR,             -- Condition AST JSON；上限 16KB

    -- 延时 action（见 §9 延时事件）
    action_type VARCHAR NOT NULL DEFAULT 'RUN',  -- RUN / SCHEDULE / CANCEL
    schedule_delay_ms BIGINT,              -- 仅 SCHEDULE 用
    schedule_correlation_key_path VARCHAR, -- SCHEDULE / CANCEL 用

    -- 元信息
    name VARCHAR,
    description VARCHAR,
    status VARCHAR NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / INACTIVE
    created_by VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT ck_sub_action_type CHECK (action_type IN ('RUN','SCHEDULE','CANCEL')),
    CONSTRAINT ck_sub_schedule CHECK (
        (action_type = 'SCHEDULE' AND schedule_delay_ms IS NOT NULL
         AND schedule_correlation_key_path IS NOT NULL)
        OR (action_type = 'CANCEL' AND schedule_correlation_key_path IS NOT NULL)
        OR (action_type = 'RUN' AND schedule_delay_ms IS NULL
            AND schedule_correlation_key_path IS NULL)
    ),
    CONSTRAINT ck_sub_status CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- 核心热路径索引：SubscriptionMatcher 按 db/table/op 反查候选订阅
CREATE INDEX idx_sub_source ON subscriptions(db, table_name, status);
```

- `source_ref` 拆为一级列（mq/topic/db/table_name/op_types/source_type）支撑热路径订阅匹配
- `action` 拆为 `action_type` / `schedule_delay_ms` / `schedule_correlation_key_path` 三列
- SCHEDULE 永远跑订阅外层的 `pipeline_id`（去掉 `schedule_pipeline_id`）
- 应用层：创建/更新订阅时校验 pipeline 存在且 version 是 PUBLISHED

#### 6.4.4 `executions`

```sql
CREATE TABLE executions (
    id VARCHAR(36) PRIMARY KEY,             -- UUID（时间有序，B-tree 插入性能好）
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version INT NOT NULL,
    team VARCHAR NOT NULL,                  -- 从 pipeline 拷贝
    application VARCHAR NOT NULL,           -- 从 pipeline 拷贝
    trigger_type VARCHAR NOT NULL,          -- CDC / CRON / API / DELAYED
    trigger_event LONG VARCHAR,             -- 上限 1MB；DELAYED 场景只存 original_event
    subscription_id VARCHAR,                -- 可空：cron/api 触发无

    status VARCHAR NOT NULL,                -- SUCCESS / SHORT_CIRCUITED（FAILED 不写本表）
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP NOT NULL,
    duration_ms BIGINT,
    trace_id VARCHAR,

    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_exec_status CHECK (status IN ('SUCCESS','SHORT_CIRCUITED'))
);

CREATE INDEX idx_exec_pipeline ON executions(pipeline_id, started_at DESC);
CREATE INDEX idx_exec_team ON executions(team, started_at DESC);
CREATE INDEX idx_exec_status ON executions(status, started_at DESC);
CREATE INDEX idx_exec_trace ON executions(trace_id);
CREATE INDEX idx_exec_sub ON executions(subscription_id, started_at DESC);
CREATE INDEX idx_exec_trigger ON executions(trigger_type, started_at DESC);
```

- **采样写入**：pipeline 级配置 `executionLogSampleRatio`（0=NEVER, 1=ALWAYS, 0.1=SAMPLE 10%）
- **emit alert 的成功 execution 强制落库**（不受 sample_ratio 影响）——evidence 需要 trigger_event 来源
- **FAILED 不写本表**（写 failed_executions）
- DEBUG 模式为远期项
- 归档策略为远期项，schema 已预留 `created_at` + 索引

#### 6.4.5 `failed_executions`

```sql
CREATE TABLE failed_executions (
    id VARCHAR(36) PRIMARY KEY,             -- UUID
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version INT NOT NULL,
    execution_id VARCHAR,                   -- 可空：强杀可能未生成
    team VARCHAR NOT NULL,
    application VARCHAR NOT NULL,
    trigger_type VARCHAR NOT NULL,          -- CDC / CRON / API / DELAYED
    trigger_event LONG VARCHAR,             -- 上限 1MB
    subscription_id VARCHAR,                -- 死信排查"哪个订阅的 pipeline 经常死"

    node_name VARCHAR,                      -- 对齐 alerts_evidence.node_name
    error_type VARCHAR,                     -- 标准化枚举
    error_message TEXT,
    stack_trace LONG VARCHAR,               -- 上限 32KB，应用层截断

    status VARCHAR NOT NULL,                -- PENDING / RESOLVED / IGNORED
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,                  -- status 离开 PENDING 的时间（RESOLVED 或 IGNORED 都写）

    CONSTRAINT ck_fe_status CHECK (status IN ('PENDING','RESOLVED','IGNORED')),
    CONSTRAINT ck_fe_error_type CHECK (error_type IN (
        'SCRIPT_COMPILATION','SCRIPT_SANDBOX','SCRIPT_TIMEOUT',
        'SCRIPT_EXECUTION','NODE_EXECUTION','PIPELINE_TIMEOUT',
        'GRACEFUL_SHUTDOWN_KILL','UNKNOWN'
    )),
    CONSTRAINT ck_fe_resolved CHECK (
        (status = 'PENDING' AND resolved_at IS NULL) OR
        (status IN ('RESOLVED','IGNORED') AND resolved_at IS NOT NULL)
    )
);

CREATE INDEX idx_fe_status ON failed_executions(status, created_at DESC);
CREATE INDEX idx_fe_execution ON failed_executions(execution_id);
CREATE INDEX idx_fe_pipeline ON failed_executions(pipeline_id, created_at DESC);
CREATE INDEX idx_fe_team ON failed_executions(team, created_at DESC);
CREATE INDEX idx_fe_trigger ON failed_executions(trigger_type, created_at DESC);
CREATE INDEX idx_fe_error_type ON failed_executions(error_type, created_at DESC);
CREATE INDEX idx_fe_sub ON failed_executions(subscription_id, created_at DESC);
```

- **全量写入失败**（不受 sample_ratio 影响）
- 与 executions 互斥（同一 execution 只写其中一张表）
- 手动重试 API 为远期项
- 归档策略为远期项

#### 6.4.6 `alerts`

```sql
CREATE TABLE alerts (
    id VARCHAR(36) PRIMARY KEY,             -- UUID
    team VARCHAR NOT NULL,
    application VARCHAR NOT NULL,
    pipeline_labels LONG VARCHAR,           -- JSON；上限 16KB；denormalize 历史保真
                                            -- 来源：emit alert 时从 ExecutionMeta.pipelineLabels 拷贝
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version INT NOT NULL,
    execution_id VARCHAR NOT NULL,          -- 必然由 execution 产生
    fingerprint VARCHAR(256) NOT NULL,      -- 上限 256 字符
    severity VARCHAR NOT NULL,              -- INFO / WARNING / CRITICAL
    labels LONG VARCHAR NOT NULL,           -- JSON；上限 16KB；告警路由 labels
    annotations LONG VARCHAR,               -- JSON；上限 16KB；渲染后字符串

    starts_at TIMESTAMP NOT NULL,           -- 首次 emit，永不改
    last_seen_at TIMESTAMP NOT NULL,        -- 每次 emit 更新
    ends_at TIMESTAMP NOT NULL,             -- 每次 emit 更新 = last_seen_at + ttl
    resolved_at TIMESTAMP,                  -- 翻转 job 写 = ends_at（当前未生效，见 §4.5）

    status VARCHAR NOT NULL,                -- FIRING / RESOLVED
    dedup_count INT NOT NULL DEFAULT 1,     -- 每次 emit +1
    generator_url VARCHAR,
    trace_id VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT ck_alerts_status CHECK (status IN ('FIRING','RESOLVED')),
    CONSTRAINT ck_alerts_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))
);

CREATE INDEX idx_alerts_status_ends ON alerts(status, ends_at);       -- 翻转 job 用
CREATE INDEX idx_alerts_team_time ON alerts(team, starts_at DESC);
CREATE INDEX idx_alerts_fingerprint ON alerts(fingerprint, status);   -- SELECT-then-INSERT/UPDATE 用
CREATE INDEX idx_alerts_status_time ON alerts(status, starts_at DESC);
CREATE INDEX idx_alerts_pipeline ON alerts(pipeline_id, starts_at DESC);
CREATE INDEX idx_alerts_sev_status ON alerts(severity, status, starts_at DESC);
CREATE INDEX idx_alerts_trace ON alerts(trace_id);
CREATE INDEX idx_alerts_exec ON alerts(execution_id);
```

- fingerprint 并发去重：应用层 SELECT-then-INSERT/UPDATE，无 DB 强约束，接受极端并发下重复行
- ttl 优先级：脚本 emit 时传 ttl 用脚本值；否则按 severity 默认（CRITICAL 60min / WARNING 30min / INFO 15min）
- FIRING → RESOLVED 翻转 job：设计为每分钟扫、每批 1000、`resolved_at=ends_at`（**当前未生效**，见 §4.5）
- `pipeline_labels` 保留（denormalize 历史保真）
- 归档策略为远期项

#### 6.4.7 `alerts_evidence`

```sql
CREATE TABLE alerts_evidence (
    alert_id VARCHAR(36) PRIMARY KEY,       -- PK=FK，1:1 with alerts（应用层保证）
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version INT NOT NULL,
    execution_id VARCHAR NOT NULL,
    node_name VARCHAR,                      -- 无 node_id 列，只留 node_name

    -- trigger_event 不存（JOIN executions 取——emit alert 的 execution 强制落库，必然能取到）

    outputs LONG VARCHAR,                   -- 合并 rule_context + outputs；上限 256KB
                                            -- 结构 {node_name: {key: value}}

    trace_id VARCHAR,
    span_id VARCHAR,
    captured_at TIMESTAMP NOT NULL,
    truncated BOOLEAN NOT NULL,
    size_bytes INT NOT NULL                 -- DefaultAlertSink 落库时按真实字节数回填
);

CREATE INDEX idx_ev_pipeline ON alerts_evidence(pipeline_id, captured_at DESC);
CREATE INDEX idx_ev_exec ON alerts_evidence(execution_id);
```

- 只有 `node_name`（NodeSpec 没有 node_id 概念）
- 无 `rule_expression`（脚本节点无判定表达式概念）
- 单 `outputs` 列（合并 rule_context + outputs，脚本节点边界模糊）
- trigger_event 不存（JOIN executions 取）
- 不加 `capture_config`（需要时 JOIN pipeline_versions 反查脚本）
- 归档策略为远期项（跟随 alerts）

### 6.5 Controlplane API

```
Pipeline 管理:
  POST   /api/v1/pipelines
  GET    /api/v1/pipelines
  GET    /api/v1/pipelines/{id}
  PUT    /api/v1/pipelines/{id}
  POST   /api/v1/pipelines/{id}/archive

版本管理:
  POST   /api/v1/pipelines/{id}/versions
  GET    /api/v1/pipelines/{id}/versions
  POST   /api/v1/pipelines/{id}/versions/{v}/publish
  POST   /api/v1/pipelines/{id}/versions/{v}/archive

订阅管理:
  POST   /api/v1/subscriptions
  GET    /api/v1/subscriptions
  GET    /api/v1/subscriptions/{id}
  PUT    /api/v1/subscriptions/{id}
  DELETE /api/v1/subscriptions/{id}

事件触发（API Source）:
  POST   /api/v1/events                     ← 单 event + op=API，投递 ApiSource

告警查询:
  GET    /api/v1/alerts
  GET    /api/v1/alerts/{id}
  GET    /api/v1/alerts/{id}/evidence       ← evidence 不含 trigger_event，需 JOIN executions

执行记录:
  GET    /api/v1/executions
  GET    /api/v1/executions/{id}
  GET    /api/v1/failed-executions
  GET    /api/v1/failed-executions/{id}

校验 / Dry-run:
  POST   /api/v1/validate/pipeline          ← 静态校验（编译 + 沙箱白名单）
  POST   /api/v1/validate/dry-run           ← 用模拟 event 真跑一遍（复用生产 DataSource + 回滚事务，db.* 真查真执行但回滚，告警截获返回前端，DB 零副作用）

Grafana datasource:
  GET    /api/v1/grafana/alerts             （远期）
  GET    /api/v1/grafana/annotations       （远期）
```

### 6.6 进程装配（`application.yml`，当前实际配置）

```yaml
spring:
  profiles:
    active: all-in-one
  datasource:
    url: jdbc:h2:mem:observe;DB_CLOSE_DELAY=-1;MODE=MySQL   # 开发期 H2；生产替换为目标 DB
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
    open-in-view: false

observe:
  worker:
    enabled: true
    runner-core: 4                # pipelineRunnerPool core（生产建议 16）
    runner-max: 16                # pipelineRunnerPool max（生产建议 32）
    runner-queue: 1000            # pipelineRunnerPool 队列上限（超出拒绝 + 记 metric）
    cron-pool-size: 2
    cron-period-millis: 60000
    ibm-mq:
      enabled: false              # true 时装配 IbmMqCdcSource
      host: localhost
      port: 1414
      queue-manager: QM1
      channel: DEV.APP.SVRCONN
      queue: APP.Q
      batch-size: 50              # CDC 批量累积阈值
      batch-timeout-millis: 200   # CDC 批量 flush 超时
```

> 订阅匹配在 `SourceDispatcher` 线程内同步进行，不单设 subscription-matcher 线程池；延时 SES 池大小取 `delayed-pool-size`（`WorkerProperties.delayedPoolSize`，默认 4，见 §9）。

未来按 profile 拆 worker-only / controlplane-only 进程。

### 6.7 配置热更新

Worker 通过 30s 轮询 DB 感知规则变更（`PipelineHotReloader`，由 `WorkerConfig.HotReloaderScheduler` 以 `@Scheduled(fixedDelay = 30_000)` 触发）：

- `ConfigLoader.load()` 全量重建 `PipelineRegistry.Snapshot`（pipelines + subscriptions 索引）
- `PipelineHotReloader.refresh()` 调 `registry.replace(loader.load())`，异常时保留上一份 snapshot
- 订阅索引重建是原子的（一次替换整个 Snapshot 对象）

#### 冷启动

worker 启动时若 MQ consumer 在 `ConfigLoader` 首次拉到配置之前就开始消费，registry 为空 → 所有 CDC 事件匹配不到 pipeline → **静默丢告警**。约束：

- worker 启动顺序：**`WorkerConfig.workerColdStart`（`ApplicationRunner`）先同步加载一次配置（`configLoader.load()`）再启动 MQ consumer**
- `SubscriptionMatcher.isLoaded()` 暴露加载状态
- readiness probe 应暴露此状态：未加载完成 → not ready → 流量不进入（远期项）

---

## 7. 错误处理与边界场景

### 7.1 关键边界场景

#### CDC 事件乱序
**策略**：不保证顺序。脚本作者需考虑乱序（用幂等 SQL、fingerprint 去重）。

#### CDC 事件重复
**策略**：容忍重复（至少一次，`IbmMqCdcSource` flush 失败不 ack → MQ 重投）。pipeline 设计为近似幂等；告警靠 fingerprint 去重。

#### 大 Event / 大 Evidence
**策略**：
- Event 上限 1MB（source adapter 层校验，超限拒绝 + 日志/metric，不落死信）
- Evidence 上限 256KB（`EvidenceCollector`，`MAX_BYTES = 256*1024`，超限截断 + `truncated=true`）
- `outputs` 按节点丢弃（保留小节点、丢大节点）
- `executions.trigger_event` 同样截断到 1MB

#### 背压与限流
**策略**：
- MQ 消费者 batch / prefetch 限制
- 线程池队列上限 `runner-queue`（默认 1000；超出拒绝 + 日志/metric，不落死信）
- 监控 `pipeline.queue.depth` > 800 → OTel 告警

#### DB 连接池耗尽
**策略**：
- 连接池 max-size = pipeline-runner 线程数 + 10
- 单 SQL 节点查询超时 5s（可配置）
- pipeline 总超时 30s（强制中断 + 回滚 + 进 `failed_executions`，error_type=PIPELINE_TIMEOUT）
- 连接池使用率 > 80% 持续 1min → OTel 告警

#### Pipeline 版本切换中的执行
**策略**：执行开始时锁定版本号，整次执行用该版本。

#### 慢 pipeline
**策略**：默认总超时 30s。`pipeline.duration` P99 > 5s → OTel 告警。

#### Worker 重启
**策略**：`WorkerShutdown` graceful（最长 60s 等待 + 强制中断 + 死信，error_type=GRACEFUL_SHUTDOWN_KILL）。MQ 未 ack 消息会重投。

#### 时钟漂移
**策略**：所有内部时间用 worker 时钟。CDC 的 sourceTs 仅作为业务字段透传。

#### 脚本超时
**策略**：单节点脚本 5s watchdog 触发 `Thread.interrupt()`；脚本应捕获 `InterruptedException` 清理资源。超时进死信（error_type=SCRIPT_TIMEOUT）。

#### Groovy 编译缓存膨胀
**策略**：编译缓存以 `sourceHash → Class<?>` 形式常驻，估算每脚本 ~10KB，1k 脚本 ~10MB，可接受。LRU 上限 5000，超出淘汰。

#### executions 采样
**策略**：pipeline 级配置 `executionLogSampleRatio`（0=NEVER, 1=ALWAYS, 0.1=SAMPLE 10%）。失败不写 executions（写 failed_executions）；emit alert 的成功 execution 强制落库（evidence 依赖）。DEBUG 模式为远期项。

#### 数据增长与归档（远期，预留口子）
**策略**：一期不实现归档。schema 已预留：
- 所有按时间增长的热表（executions / alerts / alerts_evidence / failed_executions）有 `created_at` 或 `started_at` 列 + 对应索引
- 后续按周 job 实现 DELETE
- 不引入分区表（Sybase 分区语法与 Postgres/MySQL 差异大，"不预设 DB"下不做）

**预估日增长**（按 100 pipeline 执行/s、1% 失败率、0.1% 告警率、10% executions 采样）：

| 表 | 日增长 | 30 天体量 |
|---|---|---|
| executions | 860K（10% 采样） | 26M 行 |
| alerts | 8.6K | 260K 行 |
| alerts_evidence | 8.6K | 260K 行 |
| failed_executions | 86K（全量） | 2.6M 行 |

### 7.2 平台自身的告警（非业务告警）

| 平台指标 | 告警阈值（示例） |
|---|---|
| `pipeline.failures` rate | > 10/min 持续 5min |
| `pipeline.duration` P99 | > 5s 持续 5min |
| `pipeline.queue.depth` | > 800 持续 1min |
| `dead_letter.pending` | > 100 持续 10min |
| DB 连接池使用率 | > 80% 持续 1min |
| `script.timeout.rate` | > 1/min 持续 5min |

走 OTel metrics → Grafana/Prometheus 告警，**和业务告警完全分开**。

### 7.3 一期明确不做

- 严格事件顺序（orderingKey）
- 事件去重表（executions 去重查询）
- 分布式 worker
- 跨 DataSource 事务（JTA）
- Pipeline 编排并行（DAG executor、branch/parallel 节点）
- GraalJS 级脚本沙箱（一期用 Groovy AST 白名单，可信用户场景）
- 业务级 metrics（用户自定义 recordMetric）
- 告警发送渠道（push 到 AlertManager）
- 告警恢复事件（基于"业务恢复"判定；当前 FIRING→RESOLVED 翻转 job 也未接线，见 §4.5）
- RBAC / 审批 / 权限
- 多租户资源隔离
- 告警分组 / 静默窗口 / 升级
- Pipeline 测试回放 UI（保留 dry-run API）
- 灰度发布（按百分比切流量）
- 延时任务持久化 / 重启恢复（一期内存化、接受重启丢失）
- 死信手动重试 API（随 Admin UI 一起做）
- 数据归档策略（远期）
- execution DEBUG 模式（远期）
- 节点级 OTel span（远期）
- UDF 重载（一期每个 UDF name 对应单签名）

---

## 8. 前端编辑体验

pipeline 配置本质是「几个 Groovy 脚本节点 + 结构化订阅」。前端工程独立仓库（`a-solid-ui-template`，Vite + React + shadcn），构建产物打进 Spring Boot 静态目录。前端编辑器分阶段增强：

### 8.1 MVP（必做）

- **Monaco Editor**：Groovy 语法高亮、括号匹配、自动缩进、多光标
- **错误标记**：后端 `POST /api/v1/validate/pipeline` 返回错误列表，前端 marker 显示
- **代码片段模板（snippets）**：常见 pipeline 模式（emit alert、date 格式化等）做成可点击插入片段

### 8.2 下一期

- **节点画布**：React Flow，pipeline 节点列表可视化，支持拖拽排序
- **节点 metadata 表单**：`name` / `errorPolicy` / `provides` / `reads` 用表单填写，脚本部分继续用 Monaco

### 8.3 之后

- **dry-run UI**：调 `POST /api/v1/validate/dry-run`，返回每节点后的 ctx 快照 + 截获告警
- **上下文变量可视化**：编辑某节点时，左侧面板显示「上游节点 provides 的所有 key + 类型 + 当前值」

### 8.4 远期

- **AI 辅助**：自然语言 → Groovy 脚本草稿
- **错误自动修复建议**：脚本异常时，AI 给出修复候选

---

## 9. 延时事件（内存化实现）

延时事件支持「收到事件 A → T+N 后跑 pipeline 检查；若期间收到关联事件 B → 取消检查」这类状态超时告警场景。

典型：trade `in_use=1` 入库 → schedule 3h 后检查 `in_use`，仍为 1 → 告警；中途 `in_use=0` → 取消该 schedule。

**实现策略**：延时任务在 worker 进程内以 `ScheduledExecutorService` + `ConcurrentHashMap` 维护，**不写 DB**。worker 重启时所有未 fire 的 PENDING 任务全部丢失，业务侧靠 CDC 事件重发 + fingerprint 去重补偿。持久化（Redis 或 DB）为远期项。

### 9.1 设计决策

| 决策点 | 选择 |
|---|---|
| 关联事件识别 | JSONPath 提取 `correlationKey`（`EventPaths.get(event, path)`） |
| 存储 | 进程内内存（`ScheduledExecutorService` + `ConcurrentHashMap`） |
| cancel 范围 | 同 `correlation_key` 全取消 |
| cancel 找不到目标 | 静默 no-op（已 fire / 已重启丢失 / 从未 schedule） |
| 同 key 多次 schedule | Replace：老 `ScheduledFuture.cancel(false)` + put 新 SF |
| fire 精度 | SES 调度精度（通常 <100ms） |
| fire 时 pipeline 拿到 | DELAYED 包装事件，保留 `original_event` + schedule 元信息 |
| schedule / cancel 触发点 | 订阅层 `action` 字段配置（pipeline 作者不感知调度） |
| fire 被强杀 | 走 §4.9 优雅关闭路径，进 `failed_executions`（error_type=GRACEFUL_SHUTDOWN_KILL），延时层不独立登记 |
| 重启行为 | PENDING 任务全部丢失，无恢复机制 |

### 9.2 架构（增量）

**延时任务不作为第 4 类 Source**：fire 触发的 pipeline 是固定的、不需要订阅匹配、不需要 EventBatch。本设计让 SES 到期时**直接调用 `pipelineRunner.run`**，绕过 Source 通道。

`Event.Op.DELAYED` 仍保留——pipeline 脚本通过 `event.op == DELAYED` / `event.meta.attributes.original_event` 识别延时触发场景。

```
CDC / Cron / API Source Adapter ─→ onBatch ──→ SourceDispatcher
                                                       │
                                                       │ 命中 action=Schedule/Cancel
                                                       ▼
                                       DelayedActionHandler（application，委托）
                                                       │
                                                       ▼
                                       InMemoryDelayedEventStore（infrastructure/delayed）
                                                       │
                                              ScheduledExecutorService
                                                       │ 到期触发（精准）
                                                       ▼
                                       fire() ──直接调──→ PipelineRunner.run
                                                            （事务 + AlertSink 落库 + 失败兜底）
```

新增组件（不新增 module）：
- `observe-pipeline/application/DelayedActionHandler` — 处理 Schedule / Cancel action，内部委托给 `InMemoryDelayedEventStore`
- `observe-pipeline/infrastructure/delayed/InMemoryDelayedEventStore` — `ScheduledExecutorService` + `ConcurrentHashMap<correlationKey, ScheduledFuture>`，到期投递 `pipelineRunner.run`

> 注：`DelayedActionHandler`（application 层）当前直接 import `InMemoryDelayedEventStore`（infrastructure 层），是已知的层间耦合点。

**线程池隔离**：SES 用独立的 2-4 线程池（`delayed-scheduler`），只做 fire 派发（轻量）。fire 触发时构造 DELAYED event + 调用 `pipelineRunner.run` 后立即返回；pipeline 实际跑在 §4.8 的 `pipelineRunnerPool`。

> 注意：`pipelineRunner.run` 是同步调用——它在 SES 线程里阻塞跑完整 pipeline。所以 SES 池线程数 = 同时 in-flight 的 DELAYED pipeline 数上限。配置项 `observe.worker.delayed-pool-size`（`WorkerProperties.delayedPoolSize`，默认 4）控制。监控 `delayed_events.fire_in_flight` gauge 防止 SES 池打满。

### 9.3 领域模型增量

#### Event.Op 加 DELAYED

```java
public enum Op { INSERT, UPDATE, DELETE, TICK, API, DELAYED }
```

DELAYED event 的 `before/after` 可为 null；`EventMeta.attributes` 透传：

- `schedule_id`（内存生成的 uuid，不持久化）
- `subscription_id`
- `original_event`（内存对象引用，pipeline 可读不可改）
- `scheduled_at` / `fired_at`
- `correlation_key`

#### Subscription.action 扩展

见 §2.3b 的 `Action` sealed interface（`Run` / `Schedule(delay, correlationKeyPath, pipelineId)` / `Cancel(correlationKeyPath)`）。

### 9.4 InMemoryDelayedEventStore

`InMemoryDelayedEventStore`（pipeline，`infrastructure/delayed/`）。构造注入 `ScheduledExecutorService scheduler` + `PipelineRunner runner`。内部 `ConcurrentMap<String, ScheduledFuture<?>> byCorrelationKey`。

公共方法：
- `schedule(Subscription, Event originalEvent, Pipeline pipeline, Duration delay, String correlationKeyPath)` — JSONPath 抽 correlationKey；`ses.schedule(() -> fire(...), delay.toMillis(), MS)`；同 key 老 SF `cancel(false)`（Replace 语义）；metric `delayed_events.scheduled`
- `cancel(Subscription, Event triggerEvent, String correlationKeyPath)` — JSONPath 抽 key；`map.remove(key)`；非 null 则 `cancel(false)` + metric `cancelled{found=true}`；null 静默 no-op + metric `cancelled{found=false}`
- `pendingCount()` — 当前 map 大小
- `shutdown()` — 关 SES（优雅关闭由 §4.9 主流程协调）

`fire(...)` 私有方法：在 OTel span `delayed.fire` 内构造 DELAYED 包装 event → `pipelineRunner.run(pipeline, delayed, null)`；`finally` 清理 map（cancel 路径可能已 remove，幂等）；pipeline 失败/超时由 PipelineRunner 自己处理（事务回滚 + failed_executions），延时层不感知。

关键点：
- `byCorrelationKey` 单 value（Replace 语义保证同 key 最多 1 个 SF）
- `fire` 用 `finally` 清理 map，无论 pipeline 成功失败都清
- `cancel(false)` 不中断已在执行的 fire 线程，让跑着的 pipeline 自然完成
- `pipelineRunner.run` 失败的处理完全在 Runner 内（事务回滚 + `failed_executions`），延时层不感知

### 9.5 执行流程

#### Schedule 路径（in_use=1）

```
CDC Source Adapter ── Event(op=UPDATE, after.in_use=1) ──→
SourceDispatcher ── SubscriptionMatcher 命中订阅（action=Schedule） ──→
DelayedActionHandler.handle(sub, event, pipeline):
  1. correlationKey = EventPaths.get(event, action.correlationKeyPath)
  2. 抽不到 → fail-fast 进 failed_executions（correlation_key 提取失败）
  3. InMemoryDelayedEventStore.schedule(...):
       a. ses.schedule(() -> fire(...), delay.toMillis(), MS)
       b. 同 key 老 SF cancel(false)（Replace）
  4. metrics: delayed_events.scheduled{subscription_id, delay_bucket}
```

#### Cancel 路径（in_use=0）

```
CDC Source Adapter ── Event(op=UPDATE, after.in_use=0) ──→
SourceDispatcher ── SubscriptionMatcher 命中订阅（action=Cancel） ──→
DelayedActionHandler.handle(sub, event, pipeline):
  1. correlationKey = EventPaths.get(event, action.correlationKeyPath)
  2. 抽不到 → fail-fast 进 failed_executions
  3. InMemoryDelayedEventStore.cancel(key):
       a. SF sf = map.remove(key)
       b. sf != null → sf.cancel(false) + metric cancelled{found=true}
       c. sf == null → 静默 no-op + metric cancelled{found=false}
```

#### Fire 路径（SES 到期触发）

```
ses 内部 timer 到期（精准）:
  ↓
fire(sub, originalEvent, pipeline, correlationKey, scheduledAt):
  try (span = "delayed.fire"):
    1. wrappedEvent = Event(
         meta.sourceType = CDC,                              // 保留原始
         meta.source = "delayed:" + sub.id(),
         meta.attributes = {schedule_id, subscription_id,
                            original_event, scheduled_at,
                            fired_at, correlation_key},
         before = null, after = null,
         op = DELAYED, sourceTs = now
       )
    2. pipelineRunner.run(pipeline, wrappedEvent, subscriptionId=null)
       ↑ 完全走 §4.1-§4.5 正常 pipeline 流程：
         - 事务、OTel span、AlertSink 落库
         - 失败 → 事务回滚 + failed_executions（§4.6 + §4.7）
         - 超时 → watchdog 中断 + failed_executions
  finally:
    3. byCorrelationKey.remove(correlationKey)
```

**事务边界**：只有 pipeline 事务，无延时层独立事务。pipeline 失败/超时/被强杀的处理路径与 CDC/Cron/API 触发的 pipeline 完全一致。

### 9.6 边界场景

| 场景 | 行为 |
|---|---|
| Worker 重启时 PENDING 任务 | **丢失**（不 fire）。CDC 事件重发时自动补 schedule；不可重发场景显式接受漏检 |
| Cancel 在 fire 之后到达 | `map.remove(key)` 找不到 SF → 静默 no-op（幂等） |
| Fire 时 pipeline FAILED | pipeline 走 §4.7 进 `failed_executions`；延时层不感知 |
| Fire 时 pipeline 超时 | 同上，watchdog 中断 + `failed_executions`（error_type=SCRIPT_TIMEOUT 或 PIPELINE_TIMEOUT） |
| Fire 时 worker 被 SIGTERM 强杀 | 走 §4.9 优雅关闭 60s 路径，未完成的进 `failed_executions`（error_type=GRACEFUL_SHUTDOWN_KILL） |
| 同 key CDC 重复（at-least-once） | 老 SF cancel(false) + 新 SF put（Replace） |
| Schedule 抖动（事件 A → A → A） | 每次都 Replace，最终只 fire 一次 |
| `delay=0` 或负值 | SES 立即 schedule（等同于同步触发） |
| Schedule / Cancel 时 correlation_key 抽不到 | fail-fast 进 `failed_executions` |
| 大 trigger_event（>1MB） | source adapter 层拒绝（到不了延时层） |
| SES 池打满 | SES 用默认无界队列（`DelayedQueue`），fire 任务不会因为池满被拒绝——但会排队延迟。监控 `delayed_events.fire_in_flight` 接近 `delayed-pool-size` 持续 1min 时告警，提示运维扩容池或排查 pipeline 慢 |
| 大量长延时任务堆积（万级） | 单 SF ~200 bytes，1 万任务 ~2MB，可接受；监控 `delayed_events.pending{sub}` > 10000 告警 |

### 9.7 fingerprint 建议

延时场景的告警 fingerprint 推荐包含 `correlation_key`：

```
fingerprint = "trade-locked:" + correlation_key
```

原因：同 trade_id 抖动多次 schedule → fire 多次 → 同 fingerprint → §4.5 去重生效。若用 schedule_id 做 fingerprint，每次 fire 都是新 fingerprint，会重复告警。

推荐而非强制——pipeline 作者通过 `AlertSpec.fingerprint` 自己决定。

### 9.8 可观测性

```
delayed.fire (root span，由 InMemoryDelayedEventStore.fire 创建)
  └─ pipeline.execute        ← 与 §5.1 一致（PipelineRunner 接管后）
```

Span 属性：`delayed.schedule_id` / `delayed.correlation_key` / `delayed.scheduled_at` / `delayed.fired_at` / `delayed.due_at_drift_ms`（fired_at - due_at，正常 <100ms）。

Metrics：

```
# Counters
delayed_events.scheduled{subscription_id, delay_bucket}
delayed_events.cancelled{subscription_id, found=true|false}
delayed_events.fired{subscription_id, status=success|failed}

# Gauges
delayed_events.pending{subscription_id}                  ← map.size()
delayed_events.fire_in_flight                            ← 当前正在跑的 DELAYED pipeline 数
```

**告警阈值**：

| 指标 | 阈值 | 含义 |
|---|---|---|
| `delayed_events.pending{sub}` | > 10000 持续 5min | 单订阅堆积过多任务，重启丢失风险大 |
| `delayed_events.fire_in_flight` | 接近 `delayed-pool-size` 持续 1min | SES 池将打满 |
| `delayed.due_at_drift_ms` P99 | > 1s 持续 5min | SES 池阻塞或 fire 派发异常 |
| `delayed_events.fired{status=failed}` rate | > 10/min 持续 5min | fire 后 pipeline 频繁失败 |
| JVM heap 使用率 | > 80% 持续 5min | 长延时任务堆积导致内存压力 |

### 9.9 配置

```yaml
observe:
  worker:
    delayed-pool-size: 4           # SES 池大小（WorkerProperties.delayedPoolSize，同时 in-flight DELAYED pipeline 上限）
    # max-pending-per-subscription 软上限告警为远期项
```

订阅通过 `POST /api/v1/subscriptions` 配置 `actionType=SCHEDULE`/`CANCEL` + `scheduleDelayMs` + `scheduleCorrelationKeyPath`（见 §2.3a / §6.4.3）；SCHEDULE 永远跑订阅外层 `pipelineId`。

### 9.10 范围与不做项

**范围**：§9.1-§9.9 的全部内容（延时事件一期已实现）。

**不做**（远期项）：

| 项 | 理由 |
|---|---|
| 多 worker 共享调度 | 当前单 worker；多 worker 场景随分布式 worker 一并解决 |
| 重启恢复 PENDING 任务 | 显式接受丢失，业务靠 CDC 重发 + fingerprint 去重补偿 |
| 持久化（DB / Redis） | 不在一期设计范围 |
| 复合 correlation_key | JSONPath 单字段；复合 key 暂用拼接表达式 escape hatch |
| Cancel 模式可配（latest/all） | 默认 all |
| Cancel 主动 RESOLVED 已有告警 | 依赖告警 ttl 自然过期；自动恢复事件为远期项 |
| 延时编排（schedule 链） | 单跳：一个 schedule → 一次 fire |
