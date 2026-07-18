# B6 看板查询接口设计

**日期**：2026-07-19
**状态**：Proposed
**批次**：B6（聚合统计 + 时间序列 + 筛选）
**前置依赖**：**B5**（统一响应信封 `ApiResponse`/`PageResponse`、`ErrorCode`、`@Valid` 校验、service 分页返回 Spring `Page<T>`）。B6 所有新接口复用 B5 信封与错误体系。
**关联**：`docs/2026-07-19-controlplane-api-audit.md`（缺口来源）、`docs/adr/0002-namespace-top-level-isolation.md`（namespace 软隔离）、B5 spec

---

## 1. 背景与目标

API 审计（§4）发现 control-plane **完全无聚合/时间序列接口**，所有列表只返回原始行且无时间范围筛选。前端的看板/图表（角色 C 大盘、角色 B 告警趋势）做不出来。

**B6 目标**：在不动现有 CRUD 的前提下，新增「按维度聚合统计」与「按时间桶序列」两类只读查询接口，并为现有列表补「时间范围 + 多维度」筛选。所有新接口走 B5 信封。

**非目标**：
- 不动告警 schema / 状态机 / ack/resolve（→ B7）。
- 不引入跨 namespace 汇总（与 ADR-0002 软隔离冲突，远期）。
- 不做实时推送（一期前端轮询）。

---

## 2. 设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 查询机制 | **JPQL `@Query` + `count/group by` + 投影 record**，可选过滤用 `(:x is null or a.x = :x)` 惯用法 | 复用现有 `AlertRepository` JPQL 先例（`findExpiredFiringIds`）；不引入 `JpaSpecificationExecutor`（仓库零先例，外来模式） |
| 时间桶 | **JPQL `EXTRACT(YEAR/MONTH/DAY/HOUR FROM ts)` + `GROUP BY`**，桶起点在 Java 侧由年月日时重建为 `Instant` | JPA 标准、Hibernate 按 dialect 翻译；避免 `DATE_FORMAT`/native SQL（不可移植） |
| namespace 过滤 | **下推到 JPQL `where`**，不用现有「`findAll` 后内存 filter」模式 | 聚合若内存 filter 会全表扫描，不可接受；这是对 `findAlerts` 现有内存过滤模式的**有意偏离**，仅限新 stats 方法 |
| 时间列 | alerts 用 `starts_at`（主时间列，现有索引前缀）；executions 用 `started_at`；failed_executions 用 `created_at`（唯一时间列） | 走现有索引，避免新索引 |
| 失败率 | executions 计数 与 failed_executions 计数 **分两次查询，service 层相除**，不做 JPQL join | 两表无 FK/关联，跨表 join 不可行 |
| 端点寻址 | namespace 走 **`?namespace=` 必填 query param**（沿用 `AlertController` 现有模式） | 与 ADR-0002 软隔离 + 现有 alerts/executions 端点一致 |
| 返回形态 | stats 单资源 `ApiResponse<XxxStatsDto>`；timeseries 列表 `ApiResponse<List<XxxPointDto>>` | stats 不是分页列表，不带 `page` |

---

## 3. 接口清单（全部 `?namespace=` 必填）

### 3.1 告警统计
```
GET /api/v1/stats/alerts?namespace=&from=&to=&status=&severity=&team=&pipeline_id=
→ ApiResponse<AlertStatsDto>
```
`AlertStatsDto { namespace, from, to, bySeverity:Map<String,Long>, byStatus:Map<String,Long>, total:long }`

### 3.2 告警时间序列
```
GET /api/v1/stats/alerts/timeseries?namespace=&from=&to=&bucket=1h&severity=
→ ApiResponse<List<TimeseriesPointDto>>
```
`TimeseriesPointDto { bucketStart:Instant, count:long }`；`bucket` ∈ `1h|1d`（枚举校验，默认 `1h`）。
聚合维度：alert 计数（不按 severity 拆，按 severity 筛）。若需 severity 拆分序列，加 `?groupBy=severity`（可选，P2）。

### 3.3 执行统计 + 成功率
```
GET /api/v1/stats/executions?namespace=&from=&to=&pipeline_id=&trigger_type=
→ ApiResponse<ExecutionStatsDto>
```
`ExecutionStatsDto { namespace, from, to, byStatus:Map<String,Long>(SUCCESS/SHORT_CIRCUITED), total:long, failedCount:long, successRate:double }`
- `byStatus`/`total` 来自 `executions`；
- `failedCount` 来自 `failed_executions`（同窗口 `created_at`）；
- `successRate = total / (total + failedCount)`（service 层算，分母 0 则 1.0）。

### 3.4 现有列表补筛选（B6 顺手补，非新端点）
- `GET /alerts` 加 `?from=&to=&severity=`（时间范围走 `starts_at`，severity 新筛选项）
- `GET /executions` 加 `?from=&to=&status=`
- `GET /failed-executions` 加 `?from=&to=&status=&error_type=`
- 分页复用 B5 的 `page/size`（B5 已落地 service `Page<T>`，这里只补 query param 透传到 JPQL where）

> 这些是 B5 改造过的 controller/service 的**增量补丁**——B5 只加了分页，B6 在同方法上加时间/维度筛选。

---

## 4. 组件设计

### 4.1 投影 record（新增，放对应模块的 `infrastructure/persistence/` 或 `application/`）
```
DimensionCount(String dimension, long count)        — 通用「维度→计数」投影
TimeseriesBucket(int year, int month, int day, int hour, long count) — 时间桶投影（hour 对 1d 桶为 0）
```
repository 方法返回 `List<DimensionCount>` / `List<TimeseriesBucket>`，service 侧聚合成 DTO。

### 4.2 repository 新增方法（扩展现有 repo，不新建）
**`AlertRepository`**（observe-alerting）：
```java
@Query("select new com.imsw.observe...DimensionCount(a.severity, count(a)) "
     + "from AlertPo a where a.namespace = :ns "
     + "and (:status is null or a.status = :status) "
     + "and (:severity is null or a.severity = :severity) "
     + "and (:team is null or a.team = :team) "
     + "and (:pipelineId is null or a.pipelineId = :pipelineId) "
     + "and a.startsAt >= :from and a.startsAt < :to group by a.severity")
List<DimensionCount> countBySeverity(@Param ...);

// 同款 countByStatus(...)
// timeseries: count grouped by extract(year/month/day/hour from a.startsAt)
```

**`ExecutionRepository` / `FailedExecutionRepository`**（observe-pipeline）：同款 `countByStatus` / `countByErrorType` / timeseries（时间列 `started_at` / `created_at`）。

### 4.3 service 新增方法（扩展现有 service）
- `AlertQueryService.alertStats(namespace, from, to, status, severity, team, pipelineId)` → `AlertStats`
- `AlertQueryService.alertTimeseries(namespace, from, to, bucket, severity)` → `List<TimeseriesPoint>`
- `ExecutionQueryService.executionStats(namespace, from, to, pipelineId, triggerType)` → `ExecutionStats`
- 同时给现有 `findAlerts/findExecutions/findFailedExecutions` 加 `from/to` + 维度参数（透传到 repo where）

### 4.4 controller + DTO（observe-controlplane）
- 新增 `StatsController`（`@RequestMapping("/api/v1/stats")`）承载 3.1–3.3 三个端点。
- 新增 DTO：`AlertStatsDto`、`ExecutionStatsDto`、`TimeseriesPointDto`（放 `interfaces/dto/`）。
- 3.1–3.3 的 `from/to` 为必填 `@RequestParam`（`Instant`），`bucket` 枚举校验。
- 现有 `AlertController`/`ExecutionController` 加 `from/to/severity/status/error_type` 可选参数。

### 4.5 时间桶 Java 侧重建
`TimeseriesBucket(year,month,day,hour,count)` → `Instant`：
- `1h` 桶：`LocalDateTime.of(year,month,day,hour,0).atZone(ZoneOffset.UTC).toInstant()`
- `1d` 桶：忽略 hour，取当日 00:00 UTC
- 缺桶补零：service 侧按 `from→to` 步进生成完整桶序列，repo 返回的桶填入对应 count，空桶 count=0（图表连续性需要）。

---

## 5. 方言可移植性风险（必须在 spec 标注）

- **现状**：测试仅 H2 in-memory（`MODE=MySQL`，`H2Dialect`），生产目标 Sybase ASE（设计文档约定），**无 Sybase profile，不可测**。
- **`EXTRACT(...)` 在 H2Dialect 已验证可用**（Hibernate 翻译）；Sybase ASE 方言理论上翻译为 `datepart`，但**当前无法验证**。
- **缓解**：
  1. B6 用 JPA 标准 `EXTRACT`（非 native SQL、非 `DATE_FORMAT`），把方言适配责任交给 Hibernate。
  2. 写 H2 集成测试锁定 `EXTRACT` + `GROUP BY` 行为（现有 `JpaExecutionRecorderTest` / `DefaultAlertSinkIntegrationTest` 已用 H2，照此模式）。
  3. spec 显式标注「Sybase 适配为未验证风险，上线前需在真实 Sybase 跑一次桶查询冒烟」。
- **兜底**：若将来 Sybase `EXTRACT` 不工作，退路是「SELECT 原始 `(ts, dim)` 后 Java 侧桶聚合」（纯比较，100% 可移植），但窗口大时拉行多。一期不预先实现兜底。

---

## 6. 测试

### 6.1 repository 集成测试（H2）
- `AlertRepositoryStatsTest`（新建）：灌入不同 severity/status/时间的 alert 行，断言 `countBySeverity`/`countByStatus`/timeseries 返回正确计数与桶。
- `ExecutionRepositoryStatsTest`（新建）：同款 + 成功率分子分母。
- 覆盖：空窗口、全命中、单维度筛选、多维度组合、`from/to` 边界（半开区间 `[from, to)`）、缺桶补零。

### 6.2 service 单测
- `AlertQueryService`/`ExecutionQueryService` stats 方法：mock repo 返回 `DimensionCount`/`TimeseriesBucket`，断言聚合成 DTO 正确、`successRate` 计算正确、缺桶补零。

### 6.3 controller MockMvc（复用 B5 引入的 MockMvc 模式）
- `/stats/alerts` 返回 `{data:{bySeverity:{...},total:N}}`
- `from/to` 必填校验（缺 → 400 `VALIDATION`）
- `bucket` 枚举校验（非法值 → 400 `VALIDATION`）
- 现有列表新筛选：`/alerts?severity=CRITICAL&from=...&to=...` 正确过滤

---

## 7. 验收标准

1. `mvn compile` + `mvn test` + `mvn checkstyle:check` 全绿
2. `mvn spring-boot:run` 可启动
3. 手动 curl：
   - `GET /api/v1/stats/alerts?namespace=ops&from=...&to=...` → `{data:{bySeverity:{CRITICAL:3,...},total:...}}`
   - `GET /api/v1/stats/alerts/timeseries?namespace=ops&from=...&to=...&bucket=1h` → `{data:[{bucketStart:...,count:...}]}`
   - `GET /api/v1/stats/executions?namespace=ops&from=...&to=...` → `{data:{...,successRate:0.95}}`
   - `GET /api/v1/alerts?namespace=ops&severity=CRITICAL&from=...&to=...` 正确过滤
4. 时间序列空桶补零（图表连续无断点）

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Sybase `EXTRACT`/`GROUP BY` 不可测 | §5 标注风险 + H2 测试锁定行为；上线前 Sybase 冒烟 |
| 大窗口 timeseries 拉行多 | 一期窗口短（看板默认 24h/7d）；超大规模走归档表（远期） |
| `successRate` 分母 0 | service 层判 0 返回 1.0（无执行视为健康） |
| 可选过滤组合爆炸 | 单条 JPQL + `(:x is null or ...)` 惯用法，不写 N 个方法 |
| 与 B7 时序 | B6 不依赖 B7；B7 ack/resolve 不影响 stats（stats 只读计数） |

---

## 9. 与后续批次关系

- **B7**：告警 ack/resolve/silence 落地后，`AlertStatsDto.byStatus` 会多出 `ACKNOWLEDGED`/`IGNORED` 维度——B6 的 `countByStatus` 不需改（枚举值由 DB 行决定）。B6 在 B7 之前实现，stats 自然兼容后续新状态。
- **B8**：独立。

---

## 附录：改动文件清单（预估）

**新增（observe-controlplane）**：
- `interfaces/StatsController.java`
- `interfaces/dto/AlertStatsDto.java`、`ExecutionStatsDto.java`、`TimeseriesPointDto.java`

**新增（observe-alerting / observe-pipeline）**：
- 投影 record：`DimensionCount`、`TimeseriesBucket`
- `AlertRepositoryStatsTest`、`ExecutionRepositoryStatsTest`

**改造**：
- `AlertRepository` / `ExecutionRepository` / `FailedExecutionRepository`：加 `@Query` stats/timeseries 方法 + 时间范围/维度过滤
- `AlertQueryService` / `ExecutionQueryService`：加 stats 方法 + 现有 find 方法补 `from/to`/维度参数
- `AlertController` / `ExecutionController`：现有端点补 `from/to/severity/status/error_type` query param
