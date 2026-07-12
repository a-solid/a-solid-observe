# a-solid-observe Roadmap 与未来工作

**日期**：2026-07-11
**状态**：Current
**文档关系**：本文档由 `observe-platform-design.md` 的 §10 演进路线 + §11 二期及之后计划拆分而来。主文档只描述当前已实现 / 一期范围内置的设计；超出部分全部在此。

---

## 1. 已识别的二期项

### 1.1 数据归档策略

**问题**：一期不实现归档，`executions` / `alerts` / `alerts_evidence` / `failed_executions` 四张表会持续增长。预估 30 天体量：executions 26M 行、failed_executions 2.6M 行、alerts + alerts_evidence 各 260K 行。

**计划**：
- 后续按周 job 实现 DELETE
- 不引入分区表（Sybase 分区语法与 Postgres/MySQL 差异大，"不预设 DB"下不做）
- executions.trigger_event 7 天后清空（成功路径）/ 30 天后清空（失败路径）
- alerts + alerts_evidence 30 天后归档表，90 天后清空
- failed_executions 30 天后清空 stack_trace + trigger_event，90 天后整行删除

**一期已预留**：所有热表有 `created_at` 或 `started_at` 列 + 对应索引，后续加 job 无需改 schema。

### 1.2 execution DEBUG 模式

**问题**：一期的 `execution_log.sample_ratio` 只能控制"采不采样"，无法 100% 落库 + 额外保留节点 outputs 快照（用于排查"为什么这条 execution 没出告警"）。

**计划**：
- pipeline 级配置 `execution_log.mode`：`ALWAYS` / `NEVER` / `SAMPLE` / `DEBUG`
- DEBUG 模式：100% 落库 + 节点 outputs 全保留（存 `executions.snapshot` 或独立 `execution_snapshots` 表）
- 配合 snapshot viewer UI
- 触发条件：开发期 pipeline 调试 / 排查特定 pipeline 的执行异常

**一期简化**：只有 `sample_ratio`（0=NEVER, 1=ALWAYS, 0.1=SAMPLE 10%）。

### 1.3 死信手动重试 API

**问题**：一期不提供 `POST /api/v1/failed-executions/{id}/retry`。运营只能手动 replay MQ 消息或重发事件。

**计划**：
- 随 Admin UI 一起做（下一期）
- API：`POST /api/v1/failed-executions/{id}/retry`，传入 retry 参数
- UI：死信列表 + 重试按钮 + 确认对话框 + evidence 预览
- 重试时重新触发 pipeline 执行（不保证幂等，运营需确认）

### 1.4 Admin UI for platform state

**问题**：一期只有 HTTP API，运营通过 curl 操作，不可持续。

**计划**：
- 内部 web UI（推荐 server-rendered Thymeleaf + HTMX，非 SPA）
- pipeline 列表（version / lifecycle）
- 死信队列浏览器（filter by status + retry 按钮）
- executions 浏览器（最近 N + filters）
- subscription 列表
- hot-reload 状态面板
- queue-depth gauge
- snapshot viewer（配合 §1.2 DEBUG 模式）
- admin auth（reuse Grafana token auth pattern）

**依赖**：所有 HTTP API 端点先落地（UI 是 thin shell）。

### 1.5 CLI scaffold（validate + dry-run）

**问题**：每次 pipeline 校验要起 worker + POST API，30s+ 往返。CLI <1s。

**计划**：
- 新 Gradle module `observe-cli`
- `observe-cli validate <file>`：跑 JSONPath / Groovy / rule 校验
- `observe-cli dry-run <file> --event <json>`：用模拟 event 跑 pipeline，打印 alert/no-alert + evidence preview + span tree
- CI gate：`observe-cli validate` on every PR touching pipeline configs
- Distribution：JReleaser → Homebrew tap + GitHub Releases，或 `curl | sh` installer
- 优化：GraalVM native-image for validate（hot path），dry-run 走 JVM（Groovy 反射难 native-compile）

### 1.6 阈值历史版本

**问题**：一期 thresholds 表只存当前值，改后老值丢失。审计"昨天阈值是 100，今天改成 200 后误报"无法回溯。

**计划**：
- 加 `thresholds_history` 表（name / value / updated_by / updated_at）
- 每次 UPDATE 同时 INSERT history 行
- 保留 N 天后归档

### 1.7 延时任务持久化

**问题**：下一期内存化实现，worker 重启时 PENDING 任务全部丢失。

**计划**：
- 替换 `InMemoryDelayedEventStore` 为 `RedisDelayedEventStore`（首选）或 `DbBackedDelayedEventStore`
- 外部 API（`DelayedActionHandler.schedule/cancel`、`Subscription.action`）不变，迁移是无侵入加法
- 多 worker 共享调度需配合 §2.2 分布式 worker

### 1.8 Threshold coherence for distributed worker

**问题**：一期 threshold UDF 假设单 worker 状态。多 worker 时同 key 会被 double-count。

**计划**：
- 选项 A：centralize threshold state in shared DB with pessimistic locking per key
- 选项 B：shard events by fingerprint-key so each worker owns a disjoint key set
- 阻塞于实际多 worker 需求

### 1.9 Field-filter DSL expressiveness 扩展

**问题**：一期订阅 DSL 只支持 db/table/op 精确匹配 + 结构化字段谓词（eq/in/like/range）。无法 JSONPath 嵌套谓词、OR 跨谓词、not 操作。

**计划**：
- 嵌套 JSONPath 谓词（`after.status == "PAID"`）
- OR 语义跨谓词
- `not` 操作符
- DSL ergonomics deep review 必须先做

### 1.10 Alert 主动恢复 API

**问题**：一期 FIRING → RESOLVED 翻转靠后台 job（ttl 过期），无法主动 resolve（如运营确认业务已恢复）。

**计划**：
- API：`POST /api/v1/alerts/{id}/resolve`
- 直接 UPDATE `status='RESOLVED', resolved_at=now(), ends_at=now()`
- Admin UI 加按钮

---

## 2. 远期演进项

按优先级递减：

1. **DAG Pipeline Executor** —— 当线性 pipeline 无法表达某些场景时
2. **分布式 worker** —— 单机吞吐不够时（需要分布式锁、共享订阅索引）
3. **业务级 metrics** —— 用户能声明 `recordMetric("xxx", value)`
4. **GraalJS 替换 / 补充 Groovy** —— 当规则作者扩展到不可信场景时，GraalJS 提供结构级沙箱；可保留 Groovy 作为可信场景选项
5. **跨 DataSource 事务** —— 多库场景
6. **告警恢复事件** —— 检测到"业务恢复"时主动 resolve（与 §1.10 互补：自动检测 vs 手动触发）
7. **灰度发布** —— 按百分比 / 租户切流量
8. **RBAC** —— 当需要权限控制时
9. **Pipeline 测试回放 UI** —— 前端可视化重跑历史 CDC
10. **延时任务持久化** —— 已在 §1.7 列出

每个演进项都是加法（加新 module / 加新 Executor / 加新 source 类型 / 加新脚本引擎），不需要重构现有抽象。

---

## 3. 分类

按主题归类：

### 运维支撑类
- §1.1 数据归档策略
- §1.2 execution DEBUG 模式
- §1.3 死信手动重试 API
- §1.4 Admin UI for platform state
- §1.5 CLI scaffold
- §1.10 Alert 主动恢复 API

### 持久化与可靠性类
- §1.6 阈值历史版本
- §1.7 延时任务持久化
- §1.8 Threshold coherence for distributed worker
- §2.2 分布式 worker
- §2.5 跨 DataSource 事务

### 表达力扩展类
- §1.9 Field-filter DSL expressiveness 扩展
- §2.1 DAG Pipeline Executor
- §2.3 业务级 metrics
- §2.4 GraalJS 替换 / 补充 Groovy
- §2.9 Pipeline 测试回放 UI

### 治理与权限类
- §2.6 告警恢复事件
- §2.7 灰度发布
- §2.8 RBAC

