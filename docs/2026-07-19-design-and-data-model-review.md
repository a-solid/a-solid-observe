# a-solid-observe 设计与数据模型 Review

**日期**：2026-07-19
**状态**：Review（待评审）
**评审范围**：B1–B4 改造完成后的当前 master 快照（HEAD = `2179bc1`）
**关联文档**：`docs/observe-platform-design.md`（Current 架构）、`docs/adr/0001`–`0007`、`docs/superpowers/specs/2026-07-18-observe-refactor-batches-design.md`

> 本文档是为明日 review 整理的「现状评审 + 发现项」清单，重点放在**数据模型设计**与**已完成改造的落地一致性**。不重复架构设计文档已有的内容，只标注差异、风险、待办。

---

## 0. TL;DR — 评审结论

| 维度 | 结论 |
|---|---|
| 整体设计一致性 | ✅ 良好。B1–B4 落地与 ADR 基本一致，命名/边界清晰 |
| 数据模型地基（snowflake id / namespace / 无 FK） | ✅ 一致，符合 ADR-0002/0003 |
| Event sealed interface（B3） | ✅ 干净落地，旧 `Op` 枚举拆分到位 |
| Cron per-subscription（B4） | ✅ 落地完整，旧的 `CronSource` 已删除 |
| **ADR-0005（Alert 1:N + 状态机）vs 实际 schema** | ⚠️ **最大落差**：ADR 已 accepted，但 schema/enum 仍是 2 态 + 1:1 evidence |
| 死代码 | ⚠️ 少量确认死代码（见 §5，另附独立清理清单） |
| Control-plane API 对前端的支持 | ⚠️ CRUD 完整，但**缺聚合/时间序列/分页/筛选**，看板图表无法直接支撑（见 §6） |

---

## 1. 数据模型全景

### 1.1 表清单（8 张表，3 个 schema）

| Schema | 表 | 用途 | PK 策略 |
|---|---|---|---|
| config | `namespaces` | 顶层隔离命名空间 | snowflake `id` |
| config | `pipelines` | pipeline 元数据（业务键 `namespace+name`） | snowflake `id` |
| config | `pipeline_versions` | pipeline 定义版本（不可变 JSON） | **复合 PK `(pipeline_id, version)`** ← 唯一非 snowflake |
| config | `subscriptions` | 订阅（事件源 → pipeline 版本绑定） | snowflake `id` |
| alerting | `alerts` | 告警（dedup + wave） | snowflake `id` |
| alerting | `alerts_evidence` | 告警证据（节点输出） | `alert_id`（**1:1**，见 §3） |
| pipeline | `executions` | 成功/短路执行记录（采样） | snowflake `id` |
| pipeline | `failed_executions` | 失败执行记录（全量） | snowflake `id` |

### 1.2 横切约定（所有 PO 一致）

- **物理 PK = snowflake BIGINT**，应用层 `SnowflakeIdGenerator` 分配，无 DB 自增（ADR-0003）。趋势递增、跨实例唯一、对聚簇友好。
- **业务键 = `(namespace, name)`**，唯一约束在 `pipelines`/`subscriptions`/`namespaces`。API 只按业务键寻址，BIGINT `id` **不对外暴露**。
- **无 FK 约束**，所有跨表引用（`execution_id`/`alert_id`/`pipeline_id`/`subscription_id`）都是普通 BIGINT + 索引，引用完整性靠应用层（ADR-0003 + 记忆 [[feedback_no_foreign_keys]]）。
- PO 用**字段访问**（`public` 字段，无 getter/setter）。
- 时间字段统一 `java.time.Instant`（ISO-8601 UTC）。

### 1.3 字段级清单

详见 `docs/observe-platform-design.md` 与各 `V1__init.sql`。这里只列**评审要点**，不复述全部字段：

- `SubscriptionPo`（subscriptions）：B4 新增 `cron_expression`/`cron_name`/`concurrent`；`action_type` 跨字段 CHECK 约束把 `RUN/SCHEDULE/CANCEL` 与 delay/correlation 列绑定（`config/V1__init.sql:79-85`）——设计良好，DB 层就挡住了不一致组合。
- `AlertPo`（alerts）：`severity` ∈ INFO/WARNING/CRITICAL；`status` ∈ FIRING/RESOLVED；`fingerprint` 是 dedup/wave 键；`starts_at/last_seen_at/ends_at` 构成 wave 窗口。
- `ExecutionPo`（executions）：`status` ∈ SUCCESS/SHORT_CIRCUITED；`trigger_event` LONG VARCHAR(1M) 存 JSON 序列化的 sealed `Event`。
- `FailedExecutionPo`（failed_executions）：`error_type` 枚举齐全（SCRIPT_*、NODE_EXECUTION、PIPELINE_TIMEOUT、GRACEFUL_SHUTDOWN_KILL、UNKNOWN）；`status` ∈ PENDING/RESOLVED/IGNORED 且与 `resolved_at` 有跨字段 CHECK。
- `EvidencePo`（alerts_evidence）：PK = `alert_id`（1:1）。

---

## 2. Event 模型（B3 sealed interface）✅

`observe-kernel/.../event/model/Event.java` 是 `sealed interface`，4 个 record 子类型：

| 子类型 | 产出方 | 关键字段 |
|---|---|---|
| `CdcEvent` | `IbmMqCdcSource` | `before`/`after`/`op(CdcOp)` + `CdcMeta{db,table,attributes}` |
| `TickEvent` | `CronScheduler`（B4） | 无 payload（cron 纯信号）+ `TickMeta{cronName,cronExpression}` |
| `ApiEvent` | `ApiSource` | `payload` + `ApiMeta{apiName}` |
| `DelayedEvent` | `InMemoryDelayedEventStore` | 嵌套 `originalEvent`（递归 sealed）+ `DelayedMeta` |

- **`@type` 判别**：Jackson `Id.NAME`，写入/读取 `"@type":"CdcEvent"` 等简单类名。`trigger_event` 列正是靠它反序列化。
- **旧 `Op` 枚举已拆分**：数据变更 → `CdcOp{INSERT,UPDATE,DELETE}`（CDC 专属）；触发源不再用 op，而是 Event 子类型本身。✅ 与 ADR-0006 一致。
- `ExecutionMeta`（运行上下文，不落库）携带 `triggerType(SourceType)` + `triggerEvent(Event)`，是 `JpaExecutionRecorder` 写 PO 的数据源。

**结论**：B3 落地干净，`Source.type()`（旧派发机制）已无生产调用方（见 §5 死代码 #8）。

---

## 3. ⚠️ ADR-0005 vs 实际实现落差（重点评审项）

`docs/adr/0005-alert-wave-1n-evidence-state-machine.md` 状态为 **accepted**，但当前实现只完成了**一部分**。这是本次 review 最值得讨论的点：**到底把 ADR-0005 当作「设计目标」还是「已建成」？**

| ADR-0005 条款 | 当前实现 | 状态 |
|---|---|---|
| Wave（TTL 窗口）告警收敛 | `starts_at/last_seen_at/ends_at` + `AlertResolveJob.resolveExpiredAlerts()` | ✅ schema 在，但 ⚠️ job 未接 `@Scheduled`（见下） |
| 1:N alert → evidence | `alerts_evidence` PK 仍是 `alert_id`（1:1） | ❌ **未迁移**到 snowflake PK + `alert_id` ref + `(alert_id, captured_at)` 索引 |
| 4 态状态机（FIRING/RESOLVED/ACKNOWLEDGED/IGNORED） | `AlertStatus` 只有 FIRING/RESOLVED；DB CHECK 也只允许这两值 | ❌ 缺 ACKNOWLEDGED/IGNORED |
| `disposition`/`ack_*` 列 | 无 | ❌ 缺 |
| `alert_silences` 规则表 | 整张表 + PO 都不存在 | ❌ 缺 |

**两个相关的「半成品」需要决策：**

1. **`AlertResolveJob.resolveExpiredAlerts()` 生产无人调用**（§5 死代码 #7）。bean 已装配（`AlertingPipelineConfig.java:52`），但没有任何 `@Scheduled` 调它，FIRING→RESOLVED 翻转实际未生效。设计文档自己也标注了这点。
   → **决策**：补一个 `@Scheduled` 定时调它？还是显式承认这是未完成功能、在文档里降级 ADR-0005 状态？
2. **`alerts_evidence` 1:1 → 1:N 迁移**需要改 PK（涉及历史数据），属于 schema 变更。

**建议**：在 review 里明确 ADR-0005 的「已建 / 待建」边界，要么补实现、要么把 ADR 拆成 0005a（已落地）/ 0005b（待办）。

---

## 4. 其它设计发现项（按优先级）

### 4.1 ⚠️ `EvidencePo.size_bytes` 命名误导（中）
`EvidenceMapper.java:43` 存的是 `outputs.size()`（Java Map entry 数），不是字节数。建议改名 `output_field_count` 或计算真实序列化长度。

### 4.2 ⚠️ `trigger_type` 写 `"UNKNOWN"` 绕过枚举（中）
`JpaExecutionRecorder.java:64,97` 在 `SourceType` 为 null 时写字符串 `"UNKNOWN"`，但 `SourceType` 枚举里**没有** UNKNOWN 常量，DB 也没有 CHECK 约束 `trigger_type`。这是一个「裸字符串」逃逸点。建议要么给 `SourceType` 加 `UNKNOWN`、要么保证写入前非 null。

### 4.3 ℹ️ 采样与证据解耦（确认是设计）
成功执行按 `sampleRatio` 采样写 `executions`（`JpaExecutionRecorder:52`），但证据每次 emit 都写。这是 ADR-0005 §2 明确否决过「两者绑定」的方案，解耦是有意的。✅ 不是 bug，但 review 时确认一下采样率配置即可。

### 4.4 ℹ️ `pipeline_versions` 是唯一非 snowflake PK 的 PO（确认是设计）
复合 PK `(pipeline_id, version)` via `@IdClass`，符合 ADR-0003「版本按自然键寻址」。✅

### 4.5 ℹ️ 无 FK 的孤儿行风险（已知约束）
所有跨聚合引用都是裸 BIGINT。按 [[feedback_no_foreign_keys]] 这是有意为之，但意味着任何「跳过按业务键 lookup」的写入都会产生孤儿行。当前 writer 都走 `*CrudService`，OK；review 时确认未来批量导入路径也守约。

---

## 5. 死代码清单（B1–B4 改造残留 + 历史遗留）

详细审计见独立清理任务（已执行）。确认死代码如下，**已列出待你确认后删除**（本轮按约定先列后删）：

| # | 位置 | 类型 | 置信度 | 处理 |
|---|---|---|---|---|
| 1 | `kernel/error/AlertPersistenceException.java` | 整个类零引用 | 极高 | 删整文件 |
| 2 | `pipeline/application/PipelineRegistry.java:34` `pipeline(Long)` | 公共方法零调用 | 极高 | 删 |
| 3 | `PipelineRegistry.Snapshot.subscriptionCount()` `:152` | 公共方法零调用 | 极高 | 删 |
| 4 | `pipeline/application/PipelineOutcome.java:6` `FAILED` | 枚举常量零使用 | 高 | 删（注意 `DryRunService` 用的字符串 `"FAILED"` 无关） |
| 5 | `kernel/event/model/ExecutionData.java:13` `workingSpace` | 字段初始化后从不读 | 高 | 删字段+初始化 |
| 6 | `PipelineRegistry.Snapshot.dbTableKey(...)` `:161` | 仅同文件内调用 | 高 | `public static` → `private static` |
| 7 | `AlertResolveJob.resolveExpiredAlerts()` | bean 装配但生产零调用 | 中（设计决策） | **不删**，见 §3 决策项 |
| 8 | `Source.type()` | 接口方法零生产调用（B3 后过时） | 中（API 破坏） | 见 §5.1 |

### 5.1 需要你拍板的两个（不是纯机械删除）
- **`Source.type()`**：B3 后已无生产调用（源类型改由 Event 子类型决定）。删除会破坏 `Source` 接口 + 3 个实现者。建议删，但属于 API 变更。
- **`AlertEntity.generatorURL`**（字段 + PO 列 + DB 列 `generator_url`）：生产中永远为 `null`，从未被写入非 null。删除涉及 schema 变更（drop column）。

---

## 6. Control-plane API 对前端的支撑度（详见独立 API 审计）

**CRUD 完整度**：pipeline / subscription / namespace 的增删改查齐全（pipeline 用 `archive` 软删，无硬删；subscription 有 `DELETE`）。validate / dry-run / 事件提交 OK。

**统一格式**：⚠️ **无统一响应信封**。控制器直接返回裸 record / `List` / `ResponseEntity`，无 `ApiResponse<T>`/`Result<T>`/`PageResponse`。错误统一走 `GlobalExceptionHandler` 返回 `{"error":"<msg>"}`（单 key），但 **404 是空 body**（控制器手动 `ResponseEntity.notFound().build()`），前端只能靠 HTTP 状态区分。**前端开发前建议先统一信封**。

**看板/图表缺口**（阻断前端核心价值）：

| 前端需求 | 现状 |
|---|---|
| 聚合/统计接口（告警数、成功率、按严重度分组） | ❌ 完全没有 |
| 时间序列（alerts-per-hour、executions-per-min） | ❌ 没有，只有原始行 |
| 时间范围筛选 `?from=&to=` | ❌ 所有列表都没有 |
| 按严重度筛告警 `?severity=` | ❌ 没有（DTO 有字段，查询层不支持） |
| 分页（page/offset/cursor/total） | ❌ 只有 `?limit=`，无 total/hasMore |
| 执行历史按 status/team 筛 | ❌ 只有 `pipeline_id` |
| 告警 ack/resolve/silence 写接口 | ❌ 只读（status 字段无写路径） |
| 失败执行重试 | ❌ 没有 |
| 跨 namespace 汇总 | ❌（ADR-0002 软隔离，可接受） |

---

## 7. 建议的下一步顺序

1. **先统一 API 信封**（`ApiResponse<T>` + 分页 + 错误码 + 404 也带 body）——这是前端开工的前置地基。
2. **补聚合/时间序列接口**（至少 `alerts/stats`、`executions/stats` + 时间范围筛选）——否则看板做不出来。
3. **决策 ADR-0005 边界**（补实现 vs 降级 ADR），顺便决定 `AlertResolveJob` 接 `@Scheduled`。
4. **删确认死代码**（§5 表，等你点头）。
5. **前端页面规划**（独立文档，3 类用户）。

---

## 附录：评审检查清单

- [ ] ADR-0005 已建/待建边界是否拆分
- [ ] `AlertResolveJob` 是否接 `@Scheduled`
- [ ] `trigger_type` 写 `"UNKNOWN"` 是否补枚举或保证非 null
- [ ] `size_bytes` 命名是否修正
- [ ] 死代码 §5 表是否确认删除
- [ ] API 信封是否统一
- [ ] 聚合/时间序列接口是否纳入下个批次
