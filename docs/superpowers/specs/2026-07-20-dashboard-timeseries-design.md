# Dashboard Timeseries API 设计

**日期**: 2026-07-20
**状态**: 设计已确认，待实现

## 背景

前端 dashboard 目前使用 mock 数据渲染 Alert Trend 时序图（Hourly Stacked Area）和 Execution Throughput 时序图（Hourly Bar）。需要后端提供带 severity/status 维度的时序 API，替换 mock。

## 设计决策记录

| 决策 | 选项 | 结论 |
|------|------|------|
| Alert timeseries 响应格式 | A) 扁平列表 vs B) 按 severity 分组 | **A — 扁平列表**：`[{bucketStart, count, severity}]`，改动最小，灵活 |
| Execution timeseries 实现 | A) 扩展现有 `/executions` vs B) 独立端点 | **B — 独立端点**：`/executions/timeseries`，与 `/alerts/timeseries` 对称 |
| Bucket 粒度 | 1h / 1d / 5m / ... | **1h / 1d / 5d / 7d** |
| Dashboard namespace 参数 | 确认 | 已使用 `namespace`，无需改动 |

---

## P0: Alert Timeseries — 加 severity 维度 + 扩展 bucket

### API

```
GET /api/v1/stats/alerts/timeseries
```

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `namespace` | string | 是 | - | |
| `from` | datetime | 是 | - | 时间范围起点 |
| `to` | datetime | 是 | - | 时间范围终点 |
| `bucket` | string | 否 | `"1h"` | `"1h"` / `"1d"` / `"5d"` / `"7d"` |
| `severity` | string | 否 | - | CRITICAL/WARNING/INFO，不传返全量 |

### 响应

```json
{
  "data": [
    { "bucketStart": "2026-07-20T08:00:00Z", "count": 12, "severity": "CRITICAL" },
    { "bucketStart": "2026-07-20T08:00:00Z", "count": 47, "severity": "WARNING" },
    { "bucketStart": "2026-07-20T08:00:00Z", "count": 3,  "severity": "INFO" },
    { "bucketStart": "2026-07-20T09:00:00Z", "count": 8,  "severity": "CRITICAL" }
  ]
}
```

不传 severity 过滤时，同一 bucketStart 每种 severity 一行；传了则只返回对应 severity 的行。

### 改动点

| 层 | 文件 | 改动 |
|----|------|------|
| Domain | `observe-alerting/.../TimeseriesBucket.java` | 加 `severity: String` 字段 |
| Domain | `observe-alerting/.../TimeseriesPoint.java` | 加 `severity: String` 字段 |
| Repository | `observe-alerting/.../AlertRepository.java` | `timeseriesHourly`/`timeseriesDaily` GROUP BY 加 `a.severity`；新增 epoch-based 查询支持 5d/7d |
| Service | `observe-alerting/.../AlertQueryService.java` | `alertTimeseries()` 补零逻辑按 `(bucketStart, severity)` 组合 |
| DTO | `observe-controlplane/.../TimeseriesPointDto.java` | 加 `severity: String` 字段 |
| Controller | `observe-controlplane/.../StatsController.java` | bucket 校验扩展为 1h/1d/5d/7d |

### 5d / 7d bucket 实现

1h / 1d 沿用现有 `EXTRACT(YEAR/MONTH/DAY/HOUR)` 方式，用现有 `TimeseriesBucket(int year, int month, int day, int hour, ...)` 投影。

5d / 7d 用 epoch-based 取整：

```sql
FLOOR(EXTRACT(EPOCH FROM a.startsAt) / :stepSeconds) * :stepSeconds
```

`stepSeconds` = 432000 (5d) / 604800 (7d)。GROUP BY epoch bucket + severity。

epoch-based 结果无法装入 `TimeseriesBucket(int year, int month, int day, int hour, ...)`，需新建 `TimeseriesBucketEpoch(long epochSeconds, String severity, long count)` 投影 record。service 层把 epoch 秒转回 Instant。

---

## P1: Execution Timeseries — 独立端点

### API

```
GET /api/v1/stats/executions/timeseries
```

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `namespace` | string | 是 | - | |
| `from` | datetime | 是 | - | |
| `to` | datetime | 是 | - | |
| `bucket` | string | 否 | `"1h"` | `"1h"` / `"1d"` / `"5d"` / `"7d"` |
| `pipeline_id` | int64 | 否 | - | |
| `trigger_type` | string | 否 | - | CRON/API/DRY_RUN/MANUAL_INJECT |

### 响应

```json
{
  "data": [
    { "bucketStart": "2026-07-20T08:00:00Z", "count": 88, "status": "SUCCESS" },
    { "bucketStart": "2026-07-20T08:00:00Z", "count": 2,  "status": "FAILED" },
    { "bucketStart": "2026-07-20T09:00:00Z", "count": 110, "status": "SUCCESS" }
  ]
}
```

同一 bucketStart 每种 status 一行。status 包含 SUCCESS / SHORT_CIRCUITED / FAILED。

### 改动点

| 层 | 文件 | 改动 |
|----|------|------|
| Domain | `observe-pipeline/.../` | 新建 `ExecutionTimeseriesBucket` 投影 record |
| Domain | `observe-pipeline/.../` | 新建 `ExecutionTimeseriesPoint` record |
| Repository | `observe-pipeline/.../ExecutionRepository.java` | 新增 `timeseriesByStatus()` JPQL |
| Service | `observe-pipeline/.../ExecutionQueryService.java` | 新增 `executionTimeseries()` 方法 |
| DTO | `observe-controlplane/.../` | 新建 `ExecutionTimeseriesPointDto` |
| Controller | `observe-controlplane/.../StatsController.java` | 新增 `@GetMapping("/executions/timeseries")` |

### JPQL 实现

1h bucket（与 alerts 对称）：

```sql
SELECT new ExecutionTimeseriesBucket(
  EXTRACT(YEAR FROM e.startedAt), EXTRACT(MONTH FROM e.startedAt),
  EXTRACT(DAY FROM e.startedAt), EXTRACT(HOUR FROM e.startedAt),
  e.status, COUNT(e))
FROM ExecutionPo e
WHERE e.namespace = :namespace AND e.startedAt >= :from AND e.startedAt < :to
  AND (:pipelineId IS NULL OR e.pipelineId = :pipelineId)
  AND (:triggerType IS NULL OR e.triggerType = :triggerType)
GROUP BY EXTRACT(YEAR FROM ...), ..., e.status
```

5d/7d 同 P0 的 epoch-based 方式。

---

## P2: Dashboard — 无需改动

`GET /api/v1/stats/dashboard` 已使用 `?namespace=` 参数，符合要求。

未来如需接入 sparkline 数据，可在 `DashboardStatsDto` 中新增 `alertsTimeseries` / `executionsTimeseries` 字段，或前端自行调用 P0/P1 的时序端点。

---

## 不改动的部分

- **现有 `GET /api/v1/stats/executions`**（聚合统计）保持不变，向后兼容
- **现有 `GET /api/v1/stats/alerts`** 保持不变
- **`DashboardStatsDto`** 保持纯标量，不混入时序数据
- **`ApiResponse<T>` 信封** 不变

## 一致性规则

- 两个时序端点使用相同的参数命名（`namespace`、`from`、`to`、`bucket`）
- 都返回扁平列表 `[{bucketStart, count, <dimension>}]`，dimension 字段名不同（alerts: `severity`，executions: `status`）
- 时间窗口 `[from, to)` 半开，与现有端点一致
- 缺桶补零（图表连续性）
