# Dashboard Timeseries API 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为前端 dashboard 提供带 severity/status 维度的时序 API：P0 改造 alerts/timeseries，P1 新增 executions/timeseries。

**Architecture:** 遵循现有四层（Controller → Service → Repository → JPQL），P0 修改 TimeseriesPoint/TimeseriesBucket 加 severity 字段 + 新增 TimeseriesBucketEpoch 支持 5d/7d bucket；P1 新建 ExecutionTimeseriesPoint/ExecutionTimeseriesBucket 对称实现。两个端点均返回扁平列表 `[{bucketStart, count, dimension}]`。

**Tech Stack:** Java 17, Spring Boot 3, JPA/Hibernate, PostgreSQL (prod) / H2 (test)

## Global Constraints

- 软隔离铁律：所有查询 `namespace` 下推 WHERE，不信任内存过滤
- 时间窗口 `[from, to)` 半开语义
- 响应走 `ApiResponse<T>` 信封
- bucket 支持：`1h` / `1d` / `5d` / `7d`
- 缺桶补零（图表连续性）
- 5d/7d epoch 查询仅支持 PostgreSQL；H2 集成测试覆盖 1h/1d，epoch 场景通过 controller mock 测试覆盖

---

### Task 1: Add severity field to TimeseriesBucket and TimeseriesPoint (P0 domain)

**Files:**
- Modify: `observe-alerting/src/main/java/com/imsw/observe/alerting/application/TimeseriesBucket.java`
- Modify: `observe-alerting/src/main/java/com/imsw/observe/alerting/application/TimeseriesPoint.java`

**Interfaces:**
- Produces: `TimeseriesBucket(int year, int month, int day, int hour, String severity, long count)`
- Produces: `TimeseriesPoint(Instant bucketStart, long count, String severity)`

- [ ] **Step 1: Add severity to TimeseriesBucket**

```java
package com.imsw.observe.alerting.application;

/**
 * 时间序列桶投影（B6）：按 {@code EXTRACT(YEAR/MONTH/DAY/HOUR FROM ts)} 聚合的原始结果。
 *
 * <p>{@code hour} 对 {@code 1d} 桶无意义（service 侧忽略）；{@code count} 为该桶命中行数。
 * {@code severity} 为告警严重级别维度（CRITICAL/WARNING/INFO），不传过滤时同一时间桶每种 severity 一行。
 * service 侧把 (year,month,day,hour) 重建为 {@code Instant} 桶起点，并对缺桶补零。
 */
public record TimeseriesBucket(int year, int month, int day, int hour, String severity, long count) {}
```

- [ ] **Step 2: Add severity to TimeseriesPoint**

```java
package com.imsw.observe.alerting.application;

import java.time.Instant;

/**
 * 时间序列点（B6）：桶起点 + 计数 + 严重级别，供前端折线/柱状图。
 *
 * @param bucketStart 桶起点（UTC，1h 桶为整点、1d 桶为当日 00:00）
 * @param count 该桶命中行数（缺桶已补零）
 * @param severity 告警严重级别（CRITICAL/WARNING/INFO）
 */
public record TimeseriesPoint(Instant bucketStart, long count, String severity) {}
```

- [ ] **Step 3: Commit**

```bash
git add observe-alerting/src/main/java/com/imsw/observe/alerting/application/TimeseriesBucket.java \
        observe-alerting/src/main/java/com/imsw/observe/alerting/application/TimeseriesPoint.java
git commit -m "feat(alerting): add severity field to TimeseriesBucket and TimeseriesPoint"
```

---

### Task 2: Create TimeseriesBucketEpoch projection record (P0 domain)

**Files:**
- Create: `observe-alerting/src/main/java/com/imsw/observe/alerting/application/TimeseriesBucketEpoch.java`

**Interfaces:**
- Produces: `TimeseriesBucketEpoch(long epochSeconds, String severity, long count)` — JPQL projection for 5d/7d epoch-based bucketing

- [ ] **Step 1: Create the record**

```java
package com.imsw.observe.alerting.application;

/**
 * Epoch-based 时间序列桶投影（B6 扩展）：用于 5d/7d 粗粒度桶，SQL 侧按
 * {@code FLOOR(EXTRACT(EPOCH FROM ts) / step) * step} 聚合。
 *
 * <p>1h/1d 桶继续用 {@link TimeseriesBucket}（EXTRACT YEAR/MONTH/DAY/HOUR），
 * 此 record 仅用于 epoch 取整不可除尽的 bucket 大小。
 *
 * @param epochSeconds 桶起点 epoch 秒
 * @param severity 告警严重级别
 * @param count 该桶命中行数
 */
public record TimeseriesBucketEpoch(long epochSeconds, String severity, long count) {}
```

- [ ] **Step 2: Commit**

```bash
git add observe-alerting/src/main/java/com/imsw/observe/alerting/application/TimeseriesBucketEpoch.java
git commit -m "feat(alerting): add TimeseriesBucketEpoch for 5d/7d epoch-based bucketing"
```

---

### Task 3: Update AlertRepository JPQL — add severity to hourly/daily, add epoch query (P0 repository)

**Files:**
- Modify: `observe-alerting/src/main/java/com/imsw/observe/alerting/infrastructure/persistence/alert/AlertRepository.java`

**Interfaces:**
- Consumes: `TimeseriesBucket(int, int, int, int, String, long)` from Task 1
- Consumes: `TimeseriesBucketEpoch(long, String, long)` from Task 2
- Produces: `timeseriesHourly(...)`, `timeseriesDaily(...)` now return severity-dimensioned results
- Produces: `timeseriesEpoch(...)` for 5d/7d

- [ ] **Step 1: Update timeseriesHourly — add severity to SELECT and GROUP BY**

```java
@Query("select new com.imsw.observe.alerting.application.TimeseriesBucket("
        + "extract(year from a.startsAt), extract(month from a.startsAt), "
        + "extract(day from a.startsAt), extract(hour from a.startsAt), a.severity, count(a)) "
        + "from AlertPo a where a.namespace = :namespace and a.startsAt >= :from and a.startsAt < :to "
        + "and (:severity is null or a.severity = :severity) "
        + "group by extract(year from a.startsAt), extract(month from a.startsAt), "
        + "extract(day from a.startsAt), extract(hour from a.startsAt), a.severity "
        + "order by extract(year from a.startsAt), extract(month from a.startsAt), "
        + "extract(day from a.startsAt), extract(hour from a.startsAt), a.severity")
List<TimeseriesBucket> timeseriesHourly(
        @Param("namespace") String namespace,
        @Param("from") Instant from,
        @Param("to") Instant to,
        @Param("severity") String severity);
```

- [ ] **Step 2: Update timeseriesDaily — add severity to SELECT and GROUP BY**

```java
@Query("select new com.imsw.observe.alerting.application.TimeseriesBucket("
        + "extract(year from a.startsAt), extract(month from a.startsAt), "
        + "extract(day from a.startsAt), 0, a.severity, count(a)) "
        + "from AlertPo a where a.namespace = :namespace and a.startsAt >= :from and a.startsAt < :to "
        + "and (:severity is null or a.severity = :severity) "
        + "group by extract(year from a.startsAt), extract(month from a.startsAt), "
        + "extract(day from a.startsAt), a.severity "
        + "order by extract(year from a.startsAt), extract(month from a.startsAt), "
        + "extract(day from a.startsAt), a.severity")
List<TimeseriesBucket> timeseriesDaily(
        @Param("namespace") String namespace,
        @Param("from") Instant from,
        @Param("to") Instant to,
        @Param("severity") String severity);
```

- [ ] **Step 3: Add timeseriesEpoch for 5d/7d support**

```java
/**
 * Epoch-based 时间序列聚合（5d/7d bucket）：SQL 侧按 epoch 秒取整 + severity 双维度 GROUP BY。
 * {@code stepSeconds} 由 service 层根据 bucket 参数计算（5d=432000, 7d=604800）。
 *
 * <p>注意：{@code EXTRACT(EPOCH FROM ...)} 是 PostgreSQL 语法，H2 不支持。
 */
@Query("select new com.imsw.observe.alerting.application.TimeseriesBucketEpoch("
        + "floor(extract(epoch from a.startsAt) / :stepSeconds) * :stepSeconds, a.severity, count(a)) "
        + "from AlertPo a where a.namespace = :namespace and a.startsAt >= :from and a.startsAt < :to "
        + "and (:severity is null or a.severity = :severity) "
        + "group by floor(extract(epoch from a.startsAt) / :stepSeconds) * :stepSeconds, a.severity "
        + "order by floor(extract(epoch from a.startsAt) / :stepSeconds) * :stepSeconds, a.severity")
List<TimeseriesBucketEpoch> timeseriesEpoch(
        @Param("namespace") String namespace,
        @Param("from") Instant from,
        @Param("to") Instant to,
        @Param("stepSeconds") long stepSeconds,
        @Param("severity") String severity);
```

- [ ] **Step 4: Commit**

```bash
git add observe-alerting/src/main/java/com/imsw/observe/alerting/infrastructure/persistence/alert/AlertRepository.java
git commit -m "feat(alerting): add severity dimension to timeseries JPQL + epoch query for 5d/7d"
```

---

### Task 4: Update AlertQueryService.alertTimeseries() — severity dimension + 5d/7d (P0 service)

**Files:**
- Modify: `observe-alerting/src/main/java/com/imsw/observe/alerting/application/AlertQueryService.java`

**Interfaces:**
- Consumes: `TimeseriesBucket(int, int, int, int, String, long)` from Task 1
- Consumes: `TimeseriesBucketEpoch(long, String, long)` from Task 2
- Consumes: `alertRepository.timeseriesHourly/Daily/Epoch(...)` from Task 3
- Produces: `alertTimeseries(...)` returns `List<TimeseriesPoint>` with severity

- [ ] **Step 1: Replace the alertTimeseries method**

Replace lines 121-150 with:

```java
    private static final long STEP_1H = 3_600L;
    private static final long STEP_1D = 86_400L;
    private static final long STEP_5D = 432_000L;
    private static final long STEP_7D = 604_800L;

    /**
     * 告警时间序列：按桶（1h/1d/5d/7d）返回 {@code [{bucketStart, count, severity}]}，缺桶补零。
     *
     * <p>1h/1d：EXTRACT YEAR/MONTH/DAY/HOUR 双维度 GROUP BY（severity + 时间桶）。
     * 5d/7d：epoch-based GROUP BY（仅 PostgreSQL；H2 回退到空结果或运行时异常见 repo 方法文档）。
     *
     * @param bucket {@code "1h"} / {@code "1d"} / {@code "5d"} / {@code "7d"}
     */
    public List<TimeseriesPoint> alertTimeseries(
            final String namespace, final Instant from, final Instant to, final String bucket, final String severity) {
        String normSeverity = normalize(severity);
        boolean daily = "1d".equalsIgnoreCase(bucket);
        boolean coarse = "5d".equalsIgnoreCase(bucket) || "7d".equalsIgnoreCase(bucket);

        long fromEpoch = from.getEpochSecond();
        long toEpoch = to.getEpochSecond();

        if (coarse) {
            long stepSeconds = "5d".equalsIgnoreCase(bucket) ? STEP_5D : STEP_7D;
            List<TimeseriesBucketEpoch> rows =
                    alertRepository.timeseriesEpoch(namespace, from, to, stepSeconds, normSeverity);
            return fillEpochTimeseries(rows, fromEpoch, toEpoch, stepSeconds);
        }

        long stepSeconds = daily ? STEP_1D : STEP_1H;
        List<TimeseriesBucket> rows = daily
                ? alertRepository.timeseriesDaily(namespace, from, to, normSeverity)
                : alertRepository.timeseriesHourly(namespace, from, to, normSeverity);

        // 按 (bucketStart, severity) → count 建索引
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (TimeseriesBucket b : rows) {
            Instant start = bucketStart(b, daily);
            String key = start.toString() + "|" + b.severity();
            byKey.put(key, b.count());
        }

        // severities 集合：传了过滤则只有一种；不传则补全 CRITICAL/WARNING/INFO
        List<String> severities;
        if (normSeverity != null) {
            severities = List.of(normSeverity);
        } else {
            severities = List.of("CRITICAL", "WARNING", "INFO");
        }

        // 按 from→to 步进补零，每个 bucket × 每种 severity 生成一个点
        List<TimeseriesPoint> result = new ArrayList<>();
        long cursor = alignToBucket(fromEpoch, stepSeconds);
        while (cursor < toEpoch) {
            Instant start = Instant.ofEpochSecond(cursor);
            for (String sev : severities) {
                String key = start.toString() + "|" + sev;
                result.add(new TimeseriesPoint(start, byKey.getOrDefault(key, 0L), sev));
            }
            cursor += stepSeconds;
        }
        return result;
    }

    /** Epoch-based 桶补零（5d/7d）。与 1h/1d 逻辑对称，但桶起点推算为 epoch 秒。 */
    private List<TimeseriesPoint> fillEpochTimeseries(
            final List<TimeseriesBucketEpoch> rows,
            final long fromEpoch,
            final long toEpoch,
            final long stepSeconds) {
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (TimeseriesBucketEpoch b : rows) {
            String key = b.epochSeconds() + "|" + b.severity();
            byKey.put(key, b.count());
        }

        List<String> severities = List.of("CRITICAL", "WARNING", "INFO");

        List<TimeseriesPoint> result = new ArrayList<>();
        long cursor = alignToBucket(fromEpoch, stepSeconds);
        while (cursor < toEpoch) {
            Instant start = Instant.ofEpochSecond(cursor);
            for (String sev : severities) {
                String key = cursor + "|" + sev;
                result.add(new TimeseriesPoint(start, byKey.getOrDefault(key, 0L), sev));
            }
            cursor += stepSeconds;
        }
        return result;
    }
```

- [ ] **Step 2: Verify the normalize helper and alignToBucket/bucketStart are unchanged** — they are, no action needed.

- [ ] **Step 3: Commit**

```bash
git add observe-alerting/src/main/java/com/imsw/observe/alerting/application/AlertQueryService.java
git commit -m "feat(alerting): update alertTimeseries for severity dimension and 5d/7d bucket"
```

---

### Task 5: Update TimeseriesPointDto + StatsController bucket validation (P0 controller/DTO)

**Files:**
- Modify: `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/dto/TimeseriesPointDto.java`
- Modify: `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/StatsController.java`

**Interfaces:**
- Consumes: `TimeseriesPoint(Instant, long, String)` from Task 1
- Produces: `TimeseriesPointDto(Instant bucketStart, long count, String severity)`

- [ ] **Step 1: Update TimeseriesPointDto**

```java
package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;

import com.imsw.observe.alerting.application.TimeseriesPoint;

/** 时间序列点响应（B6）。 */
public record TimeseriesPointDto(Instant bucketStart, long count, String severity) {

    public static TimeseriesPointDto from(final TimeseriesPoint p) {
        return new TimeseriesPointDto(p.bucketStart(), p.count(), p.severity());
    }
}
```

- [ ] **Step 2: Update StatsController bucket validation (line 68-71)**

Replace the bucket validation in `alertTimeseries()`:

```java
        if (!"1h".equalsIgnoreCase(bucket) && !"1d".equalsIgnoreCase(bucket)
                && !"5d".equalsIgnoreCase(bucket) && !"7d".equalsIgnoreCase(bucket)) {
            throw new ErrorResponseException(
                    ErrorCode.BAD_REQUEST.httpStatus(), ErrorCode.BAD_REQUEST,
                    "bucket must be one of: 1h, 1d, 5d, 7d");
        }
```

The rest of `alertTimeseries()` in StatsController stays the same (lines 61-74 in current file).

- [ ] **Step 3: Commit**

```bash
git add observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/dto/TimeseriesPointDto.java \
        observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/StatsController.java
git commit -m "feat(controlplane): add severity to TimeseriesPointDto, extend bucket validation to 5d/7d"
```

---

### Task 6: Update existing tests for P0 changes

**Files:**
- Modify: `observe-controlplane/src/test/java/com/imsw/observe/controlplane/interfaces/StatsControllerTest.java`
- Modify: `observe-alerting/src/test/java/com/imsw/observe/alerting/application/AlertStatsRepositoryTest.java`

**Interfaces:**
- Consumes: updated `TimeseriesPoint(Instant, long, String)` from Task 1

- [ ] **Step 1: Fix StatsControllerTest — alertTimeseriesDelegatesForValidBucket**

The `new TimeseriesPoint(from, 7L)` constructor call on line 77 needs the severity argument:

```java
    @Test
    void alertTimeseriesDelegatesForValidBucket() {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now();
        when(alertQueryService.alertTimeseries(eq("billing"), eq(from), eq(to), eq("1h"), eq(null)))
                .thenReturn(List.of(new TimeseriesPoint(from, 7L, "CRITICAL")));

        var resp = controller.alertTimeseries("billing", from, to, "1h", null);

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).count()).isEqualTo(7L);
        assertThat(resp.data().get(0).severity()).isEqualTo("CRITICAL");
    }
```

- [ ] **Step 2: Fix StatsControllerTest — alertTimeseriesRejectsInvalidBucket**

The `"5m"` test on line 67 should still fail; add tests for valid 5d/7d:

```java
    @Test
    void alertTimeseriesRejectsInvalidBucket() {
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        assertThatThrownBy(() -> controller.alertTimeseries("billing", from, to, "5m", null))
                .isInstanceOf(ErrorResponseException.class)
                .hasMessageContaining("bucket");
        assertThatThrownBy(() -> controller.alertTimeseries("billing", from, to, "3h", null))
                .isInstanceOf(ErrorResponseException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void alertTimeseriesAccepts5dAnd7dBucket() {
        Instant from = Instant.now().minusSeconds(86400 * 7);
        Instant to = Instant.now();
        when(alertQueryService.alertTimeseries(eq("billing"), eq(from), eq(to), eq("5d"), eq(null)))
                .thenReturn(List.of(new TimeseriesPoint(from, 3L, "WARNING")));

        var resp = controller.alertTimeseries("billing", from, to, "5d", null);
        assertThat(resp.data()).hasSize(1);
    }
```

- [ ] **Step 3: Fix AlertStatsRepositoryTest — timeseriesHourlyFillsZeroBuckets**

The assertion on line 117-118 checks `points.get(0).count()` which is now the sum of all severities in that bucket (3 rows: CRITICAL * 1 + WARNING * 1 + ...). But wait — with the new severity-dimensioned result, each bucket now has 3 points (CRITICAL/WARNING/INFO). The test data has 2 CRITICAL + 1 WARNING in the 10:00 bucket. So the 10:00 bucket will have:
- `(10:00, 2, "CRITICAL")`
- `(10:00, 1, "WARNING")`
- `(10:00, 0, "INFO")` ← zero-filled

The total points in a 3-hour window = 3 hours × 3 severities = 9 points.

```java
    @Test
    void timeseriesHourlyFillsZeroBuckets() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T13:00:00Z");

        List<TimeseriesPoint> points = alertQueryService.alertTimeseries("ns", from, to, "1h", null);

        // 3 hours × 3 severities = 9 points
        assertThat(points).hasSize(9);
        // First 3 points: 10:00 bucket {CRITICAL, WARNING, INFO}
        assertThat(points.get(0).bucketStart()).isEqualTo(Instant.parse("2026-07-19T10:00:00Z"));
        assertThat(points.get(0).severity()).isEqualTo("CRITICAL");
        assertThat(points.get(0).count()).isEqualTo(2L);
        assertThat(points.get(1).severity()).isEqualTo("WARNING");
        assertThat(points.get(1).count()).isEqualTo(1L);
        assertThat(points.get(2).severity()).isEqualTo("INFO");
        assertThat(points.get(2).count()).isZero();
        // 11:00 bucket: all zero (no data)
        assertThat(points.get(3).bucketStart()).isEqualTo(Instant.parse("2026-07-19T11:00:00Z"));
        assertThat(points.get(3).count()).isZero();
    }
```

- [ ] **Step 4: Fix AlertStatsRepositoryTest — timeseriesDailyBucketsByDay**

```java
    @Test
    void timeseriesDailyBucketsByDay() {
        Instant from = Instant.parse("2026-07-19T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T00:00:00Z");

        List<TimeseriesPoint> points = alertQueryService.alertTimeseries("ns", from, to, "1d", null);

        // 2 days × 3 severities = 6 points
        assertThat(points).hasSize(6);
        assertThat(points.get(0).bucketStart()).isEqualTo(Instant.parse("2026-07-19T00:00:00Z"));
        assertThat(points.get(0).severity()).isEqualTo("CRITICAL");
        assertThat(points.get(0).count()).isEqualTo(3L); // setUp: 3 CRITICAL (2 in-window + 1 09:00 which IS in day window)
        assertThat(points.get(1).severity()).isEqualTo("WARNING");
        assertThat(points.get(1).count()).isEqualTo(1L);
        assertThat(points.get(2).severity()).isEqualTo("INFO");
        assertThat(points.get(2).count()).isZero();
        // 07-20 is all zero
        assertThat(points.get(3).bucketStart()).isEqualTo(Instant.parse("2026-07-20T00:00:00Z"));
        assertThat(points.get(3).count()).isZero();
    }
```

Wait — need to recalculate. The setUp data has:
- `alert("ns", "team-a", CRITICAL, ACTIVE, base, 1L)` — base = 2026-07-19T10:00:00Z
- `alert("ns", "team-a", CRITICAL, ACTIVE, base.plusSeconds(900), 1L)` — 10:15
- `alert("ns", "team-b", WARNING, EXPIRED, base.plusSeconds(1800), 2L)` — 10:30
- Window-out: `alert("ns", "team-a", CRITICAL, ACTIVE, base.minusSeconds(3600), 1L)` — 09:00

For day window [07-19 00:00, 07-21 00:00):
- 07-19: all 4 records (09:00, 10:00, 10:15, 10:30) are >= 07-19T00:00 and < 07-21T00:00
  - CRITICAL: 3 (09:00, 10:00, 10:15)
  - WARNING: 1 (10:30)
  - INFO: 0

So the day test passes.

- [ ] **Step 5: Add a severity-filtered timeseries test**

```java
    @Test
    void timeseriesHourlyWithSeverityFilterReturnsSingleSeverity() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T13:00:00Z");

        List<TimeseriesPoint> points = alertQueryService.alertTimeseries("ns", from, to, "1h", "CRITICAL");

        // 3 hours, only CRITICAL severity
        assertThat(points).hasSize(3);
        assertThat(points.get(0).severity()).isEqualTo("CRITICAL");
        assertThat(points.get(0).count()).isEqualTo(2L); // two CRITICAL in setUp window
        assertThat(points.get(1).count()).isZero();
    }
```

- [ ] **Step 6: Run tests to verify P0 changes**

```bash
cd observe-alerting && mvn test -pl . -Dtest=AlertStatsRepositoryTest -DfailIfNoTests=false
cd ../observe-controlplane && mvn test -pl . -Dtest=StatsControllerTest -DfailIfNoTests=false
```

Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add observe-controlplane/src/test/java/com/imsw/observe/controlplane/interfaces/StatsControllerTest.java \
        observe-alerting/src/test/java/com/imsw/observe/alerting/application/AlertStatsRepositoryTest.java
git commit -m "test: update tests for severity-dimensioned timeseries + bucket 5d/7d"
```

---

### Task 7: Create ExecutionTimeseriesBucket and ExecutionTimeseriesPoint (P1 domain)

**Files:**
- Create: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/ExecutionTimeseriesBucket.java`
- Create: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/ExecutionTimeseriesPoint.java`

**Interfaces:**
- Produces: `ExecutionTimeseriesBucket(int year, int month, int day, int hour, String status, long count)` — JPQL projection
- Produces: `ExecutionTimeseriesPoint(Instant bucketStart, long count, String status)` — service return type

- [ ] **Step 1: Create ExecutionTimeseriesBucket**

```java
package com.imsw.observe.pipeline.application;

/**
 * 执行时间序列桶投影（B6 扩展）：按 {@code EXTRACT(YEAR/MONTH/DAY/HOUR FROM started_at)}
 * + status 双维度聚合的原始结果。
 *
 * <p>{@code hour} 对 {@code 1d} 桶无意义；5d/7d 使用 epoch-based 投影（另见 repo 方法）。
 *
 * @param status 执行状态（SUCCESS/SHORT_CIRCUITED/FAILED）
 */
public record ExecutionTimeseriesBucket(
        int year, int month, int day, int hour, String status, long count) {}
```

- [ ] **Step 2: Create ExecutionTimeseriesPoint**

```java
package com.imsw.observe.pipeline.application;

import java.time.Instant;

/**
 * 执行时间序列点（B6 扩展）：桶起点 + 计数 + 执行状态，供前端柱状图。
 *
 * @param bucketStart 桶起点（UTC）
 * @param count 该桶命中行数（缺桶已补零）
 * @param status 执行状态（SUCCESS/SHORT_CIRCUITED/FAILED）
 */
public record ExecutionTimeseriesPoint(Instant bucketStart, long count, String status) {}
```

- [ ] **Step 3: Commit**

```bash
git add observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/ExecutionTimeseriesBucket.java \
        observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/ExecutionTimeseriesPoint.java
git commit -m "feat(pipeline): add ExecutionTimeseriesBucket and ExecutionTimeseriesPoint records"
```

---

### Task 8: Add ExecutionRepository timeseriesByStatus JPQL queries (P1 repository)

**Files:**
- Modify: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/persistence/ExecutionRepository.java`

**Interfaces:**
- Consumes: `ExecutionTimeseriesBucket(int, int, int, int, String, long)` from Task 7
- Produces: `timeseriesHourlyByStatus(...)`, `timeseriesDailyByStatus(...)`, `timeseriesEpochByStatus(...)`

- [ ] **Step 1: Add three timeseries JPQL methods to ExecutionRepository**

Append after the existing `countByPipelineId` method:

```java
    // ---------- B6 扩展：执行时间序列 ----------

    /** 小时桶：按 started_at 的 hour + status 双维度 GROUP BY。 */
    @Query("select new com.imsw.observe.pipeline.application.ExecutionTimeseriesBucket("
            + "extract(year from e.startedAt), extract(month from e.startedAt), "
            + "extract(day from e.startedAt), extract(hour from e.startedAt), e.status, count(e)) "
            + "from ExecutionPo e where e.namespace = :namespace "
            + "and e.startedAt >= :from and e.startedAt < :to "
            + "and (:pipelineId is null or e.pipelineId = :pipelineId) "
            + "and (:triggerType is null or e.triggerType = :triggerType) "
            + "group by extract(year from e.startedAt), extract(month from e.startedAt), "
            + "extract(day from e.startedAt), extract(hour from e.startedAt), e.status "
            + "order by extract(year from e.startedAt), extract(month from e.startedAt), "
            + "extract(day from e.startedAt), extract(hour from e.startedAt), e.status")
    List<ExecutionTimeseriesBucket> timeseriesHourlyByStatus(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pipelineId") Long pipelineId,
            @Param("triggerType") String triggerType);

    /** 日桶：按 started_at 的 day + status 双维度 GROUP BY。 */
    @Query("select new com.imsw.observe.pipeline.application.ExecutionTimeseriesBucket("
            + "extract(year from e.startedAt), extract(month from e.startedAt), "
            + "extract(day from e.startedAt), 0, e.status, count(e)) "
            + "from ExecutionPo e where e.namespace = :namespace "
            + "and e.startedAt >= :from and e.startedAt < :to "
            + "and (:pipelineId is null or e.pipelineId = :pipelineId) "
            + "and (:triggerType is null or e.triggerType = :triggerType) "
            + "group by extract(year from e.startedAt), extract(month from e.startedAt), "
            + "extract(day from e.startedAt), e.status "
            + "order by extract(year from e.startedAt), extract(month from e.startedAt), "
            + "extract(day from e.startedAt), e.status")
    List<ExecutionTimeseriesBucket> timeseriesDailyByStatus(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pipelineId") Long pipelineId,
            @Param("triggerType") String triggerType);

    /**
     * Epoch-based 时间序列聚合（5d/7d bucket）：按 epoch 秒取整 + status 双维度 GROUP BY。
     *
     * <p>注意：{@code EXTRACT(EPOCH FROM ...)} 是 PostgreSQL 语法，H2 不支持。
     */
    @Query("select floor(extract(epoch from e.startedAt) / :stepSeconds) * :stepSeconds as epochSec, "
            + "e.status, count(e) "
            + "from ExecutionPo e where e.namespace = :namespace "
            + "and e.startedAt >= :from and e.startedAt < :to "
            + "and (:pipelineId is null or e.pipelineId = :pipelineId) "
            + "and (:triggerType is null or e.triggerType = :triggerType) "
            + "group by floor(extract(epoch from e.startedAt) / :stepSeconds) * :stepSeconds, e.status "
            + "order by epochSec, e.status")
    List<Object[]> timeseriesEpochByStatus(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("stepSeconds") long stepSeconds,
            @Param("pipelineId") Long pipelineId,
            @Param("triggerType") String triggerType);
```

Note: The epoch query returns `List<Object[]>` instead of a typed projection because the JPQL `new` constructor path doesn't support `floor(extract(epoch ...))` as a constructor argument cleanly. Service layer maps `Object[]` → `ExecutionTimeseriesPoint`.

- [ ] **Step 2: Add necessary imports**

Add `java.util.List` import to ExecutionRepository if not already present (it is — used by existing methods).

- [ ] **Step 3: Commit**

```bash
git add observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/persistence/ExecutionRepository.java
git commit -m "feat(pipeline): add timeseriesByStatus JPQL queries to ExecutionRepository"
```

---

### Task 9: Add ExecutionQueryService.executionTimeseries() (P1 service)

**Files:**
- Modify: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/ExecutionQueryService.java`

**Interfaces:**
- Consumes: `executionRepository.timeseriesHourlyByStatus/DailyByStatus/EpochByStatus(...)` from Task 8
- Produces: `executionTimeseries(...)` → `List<ExecutionTimeseriesPoint>`

- [ ] **Step 1: Add step constants and the executionTimeseries method**

Append after the existing `executionStats()` method (after line 85):

```java
    // ---------- B6 扩展：执行时间序列 ----------

    private static final long STEP_1H = 3_600L;
    private static final long STEP_1D = 86_400L;
    private static final long STEP_5D = 432_000L;
    private static final long STEP_7D = 604_800L;

    /**
     * 执行时间序列：按桶（1h/1d/5d/7d）返回 {@code [{bucketStart, count, status}]}，缺桶补零。
     */
    public List<ExecutionTimeseriesPoint> executionTimeseries(
            final String namespace,
            final Instant from,
            final Instant to,
            final String bucket,
            final Long pipelineId,
            final String triggerType) {
        String normTrigger = triggerType == null || triggerType.isBlank() ? null : triggerType.toUpperCase();
        boolean daily = "1d".equalsIgnoreCase(bucket);
        boolean coarse = "5d".equalsIgnoreCase(bucket) || "7d".equalsIgnoreCase(bucket);

        long fromEpoch = from.getEpochSecond();
        long toEpoch = to.getEpochSecond();
        List<String> statuses = List.of("SUCCESS", "SHORT_CIRCUITED", "FAILED");

        if (coarse) {
            long stepSeconds = "5d".equalsIgnoreCase(bucket) ? STEP_5D : STEP_7D;
            List<Object[]> rows =
                    executionRepository.timeseriesEpochByStatus(
                            namespace, from, to, stepSeconds, pipelineId, normTrigger);
            return fillEpochTimeseries(rows, fromEpoch, toEpoch, stepSeconds, statuses);
        }

        long stepSeconds = daily ? STEP_1D : STEP_1H;
        List<ExecutionTimeseriesBucket> rows = daily
                ? executionRepository.timeseriesDailyByStatus(namespace, from, to, pipelineId, normTrigger)
                : executionRepository.timeseriesHourlyByStatus(namespace, from, to, pipelineId, normTrigger);

        Map<String, Long> byKey = new LinkedHashMap<>();
        for (ExecutionTimeseriesBucket b : rows) {
            Instant start = bucketStart(b, daily);
            String key = start.toString() + "|" + b.status();
            byKey.put(key, b.count());
        }

        List<ExecutionTimeseriesPoint> result = new ArrayList<>();
        long cursor = alignToBucket(fromEpoch, stepSeconds);
        while (cursor < toEpoch) {
            Instant start = Instant.ofEpochSecond(cursor);
            for (String st : statuses) {
                String key = start.toString() + "|" + st;
                result.add(new ExecutionTimeseriesPoint(start, byKey.getOrDefault(key, 0L), st));
            }
            cursor += stepSeconds;
        }
        return result;
    }

    private List<ExecutionTimeseriesPoint> fillEpochTimeseries(
            final List<Object[]> rows,
            final long fromEpoch,
            final long toEpoch,
            final long stepSeconds,
            final List<String> statuses) {
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (Object[] row : rows) {
            long epochSec = ((Number) row[0]).longValue();
            String status = (String) row[1];
            long count = ((Number) row[2]).longValue();
            byKey.put(epochSec + "|" + status, count);
        }

        List<ExecutionTimeseriesPoint> result = new ArrayList<>();
        long cursor = alignToBucket(fromEpoch, stepSeconds);
        while (cursor < toEpoch) {
            Instant start = Instant.ofEpochSecond(cursor);
            for (String st : statuses) {
                String key = cursor + "|" + st;
                result.add(new ExecutionTimeseriesPoint(start, byKey.getOrDefault(key, 0L), st));
            }
            cursor += stepSeconds;
        }
        return result;
    }

    private static long alignToBucket(final long epochSecond, final long stepSeconds) {
        return (epochSecond / stepSeconds) * stepSeconds;
    }

    private static Instant bucketStart(final ExecutionTimeseriesBucket b, final boolean daily) {
        java.time.ZonedDateTime z = java.time.ZonedDateTime.of(
                b.year(), b.month(), b.day(), daily ? 0 : b.hour(), 0, 0, 0, java.time.ZoneOffset.UTC);
        return z.toInstant();
    }
```

- [ ] **Step 2: Add required imports to ExecutionQueryService**

Add these imports at the top:
```java
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

(Some may already be present — check and add only missing ones.)

- [ ] **Step 3: Commit**

```bash
git add observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/ExecutionQueryService.java
git commit -m "feat(pipeline): add executionTimeseries method to ExecutionQueryService"
```

---

### Task 10: Create ExecutionTimeseriesPointDto + StatsController new endpoint (P1 controller/DTO)

**Files:**
- Create: `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/dto/ExecutionTimeseriesPointDto.java`
- Modify: `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/StatsController.java`

**Interfaces:**
- Consumes: `ExecutionTimeseriesPoint(Instant, long, String)` from Task 7
- Produces: `ExecutionTimeseriesPointDto(Instant bucketStart, long count, String status)`
- Produces: `GET /api/v1/stats/executions/timeseries`

- [ ] **Step 1: Create ExecutionTimeseriesPointDto**

```java
package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;

import com.imsw.observe.pipeline.application.ExecutionTimeseriesPoint;

/** 执行时间序列点响应（B6 扩展）。 */
public record ExecutionTimeseriesPointDto(Instant bucketStart, long count, String status) {

    public static ExecutionTimeseriesPointDto from(final ExecutionTimeseriesPoint p) {
        return new ExecutionTimeseriesPointDto(p.bucketStart(), p.count(), p.status());
    }
}
```

- [ ] **Step 2: Add new endpoint to StatsController**

Add after the existing `executionStats()` method (after line 85), before `dashboard()`:

```java
    @GetMapping("/executions/timeseries")
    public ApiResponse<List<ExecutionTimeseriesPointDto>> executionTimeseries(
            @RequestParam final String namespace,
            @RequestParam final Instant from,
            @RequestParam final Instant to,
            @RequestParam(name = "bucket", required = false, defaultValue = "1h") final String bucket,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "trigger_type", required = false) final String triggerType) {
        if (!"1h".equalsIgnoreCase(bucket) && !"1d".equalsIgnoreCase(bucket)
                && !"5d".equalsIgnoreCase(bucket) && !"7d".equalsIgnoreCase(bucket)) {
            throw new ErrorResponseException(
                    ErrorCode.BAD_REQUEST.httpStatus(), ErrorCode.BAD_REQUEST,
                    "bucket must be one of: 1h, 1d, 5d, 7d");
        }
        List<ExecutionTimeseriesPoint> points = executionQueryService.executionTimeseries(
                namespace, from, to, bucket, pipelineId, triggerType);
        return ApiResponse.ok(points.stream().map(ExecutionTimeseriesPointDto::from).toList());
    }
```

- [ ] **Step 3: Add import for ExecutionTimeseriesPointDto in StatsController**

Add:
```java
import com.imsw.observe.controlplane.interfaces.dto.ExecutionTimeseriesPointDto;
import com.imsw.observe.pipeline.application.ExecutionTimeseriesPoint;
```

- [ ] **Step 4: Commit**

```bash
git add observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/dto/ExecutionTimeseriesPointDto.java \
        observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/StatsController.java
git commit -m "feat(controlplane): add /executions/timeseries endpoint with ExecutionTimeseriesPointDto"
```

---

### Task 11: Add tests for P1 execution timeseries

**Files:**
- Modify: `observe-controlplane/src/test/java/com/imsw/observe/controlplane/interfaces/StatsControllerTest.java`

**Interfaces:**
- Consumes: `ExecutionTimeseriesPoint(Instant, long, String)` from Task 7

- [ ] **Step 1: Add controller tests for the new endpoint**

Add to `StatsControllerTest.java`:

```java
    @Test
    void executionTimeseriesRejectsInvalidBucket() {
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        assertThatThrownBy(() ->
                controller.executionTimeseries("billing", from, to, "5m", null, null))
                .isInstanceOf(ErrorResponseException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void executionTimeseriesDelegatesForValidBucket() {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now();
        when(executionQueryService.executionTimeseries(
                        eq("billing"), eq(from), eq(to), eq("1h"), eq(null), eq(null)))
                .thenReturn(List.of(
                        new ExecutionTimeseriesPoint(from, 88L, "SUCCESS"),
                        new ExecutionTimeseriesPoint(from, 2L, "FAILED")));

        var resp = controller.executionTimeseries("billing", from, to, "1h", null, null);

        assertThat(resp.data()).hasSize(2);
        assertThat(resp.data().get(0).status()).isEqualTo("SUCCESS");
        assertThat(resp.data().get(0).count()).isEqualTo(88L);
        assertThat(resp.data().get(1).status()).isEqualTo("FAILED");
        assertThat(resp.data().get(1).count()).isEqualTo(2L);
    }

    @Test
    void executionTimeseriesPassesFilters() {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now();
        when(executionQueryService.executionTimeseries(
                        eq("billing"), eq(from), eq(to), eq("1d"), eq(101L), eq("CRON")))
                .thenReturn(List.of());

        var resp = controller.executionTimeseries("billing", from, to, "1d", 101L, "CRON");

        assertThat(resp.data()).isEmpty();
        verify(executionQueryService).executionTimeseries(
                eq("billing"), eq(from), eq(to), eq("1d"), eq(101L), eq("CRON"));
    }
```

- [ ] **Step 2: Add required imports to StatsControllerTest**

```java
import com.imsw.observe.pipeline.application.ExecutionTimeseriesPoint;
import com.imsw.observe.controlplane.interfaces.dto.ExecutionTimeseriesPointDto;
```

- [ ] **Step 3: Run all tests**

```bash
mvn test -pl observe-controlplane -Dtest=StatsControllerTest -DfailIfNoTests=false
mvn test -pl observe-alerting -Dtest=AlertStatsRepositoryTest -DfailIfNoTests=false
```

Expected: All tests PASS. The `timeseriesEpoch` method won't be hit by existing tests (H2 doesn't support `EXTRACT(EPOCH)`), but the controller mock tests cover the 5d/7d validation path.

- [ ] **Step 4: Commit**

```bash
git add observe-controlplane/src/test/java/com/imsw/observe/controlplane/interfaces/StatsControllerTest.java
git commit -m "test(controlplane): add tests for /executions/timeseries endpoint"
```

---

## Task Order Dependencies

```
Task 1 (domain records) ──► Task 3 (repo JPQL)
     │                         │
     └── Task 2 (epoch record) ┘
                                    │
                                    ▼
                              Task 4 (service)
                                    │
                                    ▼
                              Task 5 (controller/DTO)
                                    │
                                    ▼
                              Task 6 (update tests)  ──► P0 DONE

Task 7 (domain records) ──► Task 8 (repo JPQL)
                                    │
                                    ▼
                              Task 9 (service)
                                    │
                                    ▼
                              Task 10 (controller/DTO)
                                    │
                                    ▼
                              Task 11 (tests) ──► P1 DONE
```

P0 (Tasks 1-6) and P1 (Tasks 7-11) are independent and can run in parallel.
