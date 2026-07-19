# a-solid-observe

部门内多团队共用的观测/监测平台：监听 CDC（IBM MQ）/cron/API 触发，执行 Groovy 脚本检测逻辑，命中即落库告警 + 证据，告警通过 HTTP API 供 Grafana/AlertManager 拉取。

## Language

### 模型分层

**配置态（Configuration form）**:
被版本化、可热加载、含审计字段（name/description/createdBy/status/timestamps）的领域模型，对应 DB 表，供 CRUD 与 30s 轮询热加载使用。命名一律加 `Definition` 后缀。
_Avoid_: Spec、Config（Config 在本项目特指 observe-config 这个 module/领域）、Dto

**运行态（Runtime form）**:
执行期使用的紧凑模型，剥离审计/热加载字段，聚合成执行语义（如 `SourceRef`、`Action` sealed interface）。裸名，无后缀。
_Avoid_: RuntimeXxx、ExecutionContext-bound model

**PipelineDefinition**:
Pipeline 的配置态：`observe-config` 的 `PipelineDefinition`（pipelines 表）+ `PipelineVersion`（pipeline_versions 表，definition_json/definition_hash）。
_Avoid_: 直接用裸名 Pipeline 指代配置态

**Pipeline**:
Pipeline 的运行态：从 `PipelineVersion.definitionJson` 反序列化得到，含 nodes/labels/executionLogSampleRatio，执行引擎消费。
_Avoid_: 用 PipelineDefinition 指代运行态

**SubscriptionDefinition**:
Subscription 的配置态：扁平字段、对应 subscriptions 表、含 CRUD/热加载/审计字段。`observe-config.domain.SubscriptionDefinition`。
_Avoid_: Subscription（裸名在运行态语境指运行态模型）、SubscriptionSpec

**Subscription**:
Subscription 的运行态：把 source 字段聚合成 `SourceRef`、把 action 聚合成 `Action` sealed interface 的紧凑模型。`observe-pipeline.domain.subscription.Subscription`。
_Avoid_: 用裸 Subscription 指代配置态（造成同名歧义）

**订阅状态（ACTIVE/INACTIVE）**:
订阅的生命周期状态。`ACTIVE`（默认）= 参与触发；`INACTIVE` = 停用（不删配置、可恢复）。`PipelineRegistryLoader.load` 只收 `ACTIVE` 订阅进运行态 registry → `INACTIVE` 订阅被 matcher 天然路由不到、不触发 pipeline。停用/启用靠 `/deactivate`、`/activate` 接口，状态翻转后靠 30s 热加载 poll 生效（不主动 refresh）。与 `delete` 的区别：保留配置、可恢复。

**PipelineRegistryLoader**:
把配置态（PipelineDefinition/PipelineVersion/SubscriptionDefinition）反序列化 + 聚合成运行态（Pipeline/Subscription）的桥接器，产出 `PipelineRegistry.Snapshot`。

## Namespacing & Identity

**Namespace**:
顶层隔离维度，承担 (a) 名字作用域（同 namespace 内 pipeline/subscription 名唯一，跨 namespace 可重名）与 (b) 可见性/RBAC 边界（跨 namespace 互不可见，未来 RBAC 的挂载点）两个职责。显式资源（namespaces 表，CRUD + metadata）。取代旧 `team`/`application` 字段做顶层隔离。
_Avoid_: tenant（多租户资源隔离一期不做，namespace 不等于 tenant）、environment（环境隔离靠 profile/多实例，不靠 namespace）

**业务名 (name)**:
资源在 namespace 内的人类可读唯一业务名。与 namespace 组成业务键 `(namespace, name)`。对外 API 用业务键寻址。
_Avoid_: id（id 特指物理主键）

**id (snowflake)**:
资源物理主键，BIGINT，由 snowflake 算法在应用层分配（**非 DB 自增**），趋势递增、跨实例唯一。仅用于聚簇索引、跨表引用、日志关联。对外 API 不暴露。
_Avoid_: 自增 id（项目不用 DB 自增列）、uuid（一期 id 全部 BIGINT snowflake）

**软隔离**:
namespace 隔离靠应用层所有读写必带 namespace 过滤实现，DB 无强制约束。漏带 namespace 过滤即串数据 bug（铁律）。

**Label**:
资源的业务分类标签（KV），用于按维度筛选（如原 `team`/`application` 降级为 label）。labels 是逻辑分类的唯一维度——`team`/`application`/`line` 等不再是一等领域概念，都是 label。

**Line**:
业务线（business line），label 的一个约定维度（如"支付/清算/风控"）。物化投影列 `label_line`。
_Avoid_: domain（与 DDD bounded context 撞车）、product（偏 to-C）

**Label 投影列（label_*）**:
为支撑 alerts 高频过滤查询，从 alert.labels JSON 投影出的独立索引列（`label_app`/`label_team`/`label_line`），普通 B-tree 索引。labels JSON 是 source of truth，投影列是性能投影，可增删。一期仅 alerts 表投影；pipelines/subscriptions 的 label 查询走 JSON（低频，可接受全表扫）。Sybase 必须手动物化；SQL Server 计算列 / MySQL 函数索引可自动同步。
_Avoid_: 把 label_team/label_app 当独立领域字段（它们是 label 的投影）

## Alerting

**Wave（波次）**:
告警收敛的时间窗口，等于该告警的 ttl。波次内同 fingerprint 的 emit 视为同一告警（dedup，计数 +1，每次写新 evidence）；波次边界（ends_at 到期）系统自动将告警 `status` 翻为 `EXPIRED`；下次同 fingerprint emit 开一条新 `ACTIVE` alert 行 = 新波次。波次动力学（dedup/续期/开新/到期）由 `domain/WavePolicy` 纯函数承载（ADR-0005 addendum）。默认按 severity 分级（CRITICAL 30min / WARNING 10min / INFO 5min），脚本 emit 时传 ttl 可覆盖。
_Avoid_: ttl（波次吸收了 ttl 概念，统一称波次）、收敛窗口

**Fingerprint**:
告警的去重键。脚本可显式传；否则由 `pipeline=<id>` + 排序后 labels KV 的 sha256 计算。同一波次内同 fingerprint 合并为一条 alert。
_Avoid_: 把 fingerprint 当永久全局唯一键——它是"波次内去重键"，波次结束后同 fp 会开新 alert 行。

**Alert 状态机（两维分离，ADR-0005 addendum）**:
两个正交维度——`status`（系统态：`ACTIVE`/`EXPIRED`，时间驱动）与 `disposition`（用户处置：`NONE`/`ACKNOWLEDGED`/`IGNORED`，人驱动）。`status=ACTIVE` 波次活跃、`EXPIRED` 波次 TTL 到点（**非**业界"条件恢复"——本项目事件驱动+TTL，问题未必解决，下次同 fingerprint emit 开新 ACTIVE 行）。`disposition` 独立于 `status`：ack/ignore 只改 disposition 列、不动 status，任意 status 行（含 EXPIRED）都能被打 ack/ignore。resolveJob 只看 `status=ACTIVE and ends_at<now → EXPIRED`，不看 disposition（ACK/IGNORE 告警到期照常翻）。**不设用户手动 close**——要关靠 silence 或等到期。
_Avoid_: 把系统态和用户处置混进同一枚举（旧四态 FIRING/RESOLVED/ACKNOWLEDGED/IGNORED 已拆）；用 RESOLVED 表达本项目 TTL 到点（会误导为"问题解决"，用 EXPIRED）

**ACKNOWLEDGED**:
用户处置维度（disposition）的值——确认"已知/在处理"，带备注 + 操作人 + 时间。独立于 status，不代表问题消失、不阻止系统到期。
_Avoid_: resolved（本项目无用户 resolve，系统到期用 EXPIRED）

**IGNORED**:
用户处置维度的值——主动忽略**单条**告警，带备注。区别于 SILENCE（作用于未来同类）。

**SILENCE 规则（静默）**:
按 fingerprint 精确 / label 维度 / namespace+pipeline 维度 + 有效期（如 10 天）定义的静默规则（`alert_silences` 表）。emit 阶段（AlertSink）命中静默规则则不建 alert。作用于"未来同类告警"，区别于对单条告警的 IGNORED。
_Avoid_: 把 silence 和单条 IGNORED 混为一谈

**Evidence（1:N）**:
一次 emit 的证据快照（nodeOutputs + capture keys）。一个 alert 在其波次内可有**多条** evidence（每次 emit 一条，含 dedup 命中），挂同一 alert_id。evidence 量靠归档治理。
_Avoid_: 1:1（现状是 1:1，改造后为 1:N）

## Events

**Event（sealed）**:
触发 pipeline 执行的事件，sealed interface，permits `CdcEvent`/`TickEvent`/`ApiEvent`/`DelayedEvent`（全在 kernel）。每种子类型只含该 source 真实拥有的字段，不靠 null 兜底。
_Avoid_: 单一 Event record + 可空字段（旧设计，已废弃——非 CDC source 没有 db/table/before/after 概念，不该用 null 字段表达）

**CdcEvent**:
CDC 数据变更事件，含 `before`/`after` 快照 + `CdcOp`（INSERT/UPDATE/DELETE）。由 `IbmMqCdcSource` 产出。

**TickEvent**:
Cron 定时触发事件，**无 payload**（纯触发信号），含 `TickMeta`（cron name / cron 表达式 / 触发时刻）。pipeline 脚本在节点里 `db.queryOne` 主动查 DB。由 `CronSource` 产出。
_Avoid_: 给 TickEvent 塞 before/after（cron 无数据变更语义）

**ApiEvent**:
HTTP 触发事件，含 `payload`（HTTP POST body 反序列化的 Map）+ `ApiMeta`（api name）。由 `ApiSource` 产出。

**DelayedEvent**:
延时触发事件，**嵌套原始 Event**（`originalEvent`，通常是 CdcEvent）+ `DelayedMeta`（subscriptionId 路由键 + correlationKey 业务键）。语义：延时 = 延迟重放原始事件。由 `DelayedActionHandler.fire` 到点构造，经 `SourceDispatcher.onEvent` 回流——与其它 Event 子类型一样走 matcher，按 subscriptionId 路由回原订阅扇出（见 ADR-0006 addendum）。

**CdcOp**:
CDC 数据变更语义枚举：`INSERT`/`UPDATE`/`DELETE`，仅挂 `CdcEvent`。
_Avoid_: 与旧 `Op` 枚举混——旧 `Op` 把数据变更语义（INSERT/UPDATE/DELETE）和触发来源（TICK/API/DELAYED）混在一个枚举，L1 后拆分，TICK/API/DELAYED 不再是 op，而是 Event 子类型本身

**opTypes**:
订阅的字段，声明该订阅接收哪些 `CdcOp`。**仅对 CDC 订阅有意义**；Cron/Api 订阅不配 opTypes（它们的触发语义由 Event 子类型表达）。

**S-implicit（订阅保证类型，脚本零样板）**:
订阅声明 source 类型（CDC/Cron/Api），matcher 只把对应 Event 子类型分发给该订阅的 pipeline，引擎注入对应子类型为 `event` binding。脚本直接 `event.after.amt`（CDC 订阅下 event 即 CdcEvent），无需 `asCdc()` 转换、无需判空。类型安全由订阅层保证。

## Scheduling (Cron)

**Cron 订阅**:
`sourceType=CRON` 的订阅，带 `cronExpression`（Spring `CronExpression` 6 字段：秒 分 时 日 月 周）+ 可选 `cron name` + `concurrent`（默认 SKIP）。配在 SubscriptionDefinition（与 CDC 订阅配 db/table 对称）。

**CronSource**:
有状态组件（B9 §4 起对齐 `Source` 契约，旧名 `CronScheduler`），作为 PipelineRegistry 的观察者。热加载（registry.replace）时 diff 新旧快照的 Cron 订阅，为新增/变更的 Cron 订阅起一个调度句柄，删除的取消。每 Cron 订阅一个调度句柄（调度单元 = 订阅，相同表达式也是独立调度）。生命周期与其他 Source 一致：`start(listener)` 注入 dispatcher listener、`stop()` 取消句柄 + 关闭调度线程池；`sync(snapshot)` 不进 Source 接口，仍由 HotReloader 显式调。到点产出 `TickEvent` 投给 SourceDispatcher，由 matcher 按 source（= mq）/ subscriptionId 路由到订阅了该 cron 的 pipeline。

**Cron misfire（M1 忽略）**:
worker 重启错过的 cron 调度不补跑，只看未来调度。与一期延时任务"重启丢失"风格一致。cron 无外部重发源（不像 CDC 有 MQ 重投），漏检靠业务容忍。

**Cron 并发（默认 SKIP）**:
同一 cron 上次没跑完、下次触发到时，默认跳过本次（串行不堆积）。订阅可配 `concurrent=ALLOW` 允许并发。

## Conventions

- **配置态 vs 运行态两层是既有决策（见 ADR-0001）**，不合并。新增领域类型时默认按此分层：可版本化/热加载/审计 → 配置态 `XxxDefinition`；纯执行消费 → 运行态裸名。
- **依赖倒置**：application 层不 import infrastructure 层具体类，通过 application 层端口接口交互（参考 `DelayedEventStore`）。
- **不在 production 代码里留 demo / 手动 main 入口**。引擎冒烟验证由测试承接（如 `EndToEndFlowTest`）；需要"无 Spring 跑 pipeline"时写测试，不写 main。
