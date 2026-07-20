package com.imsw.observe.pipeline.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.imsw.observe.pipeline.domain.Execution;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionPo;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionRepository;

@Service
public class ExecutionQueryService {

    private static final int FETCH_SIZE = 1000;

    private static final long STEP_1H = 3_600L;
    private static final long STEP_1D = 86_400L;
    private static final long STEP_5D = 432_000L;
    private static final long STEP_7D = 604_800L;

    private final ExecutionRepository executionRepository;

    public ExecutionQueryService(final ExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    /**
     * 列表查询，软隔离铁律（ADR-0002）：namespace 必填，**namespace/过滤条件下推到 JPQL where**
     * （避免 findAll + 内存过滤在跨 namespace 超 {@code FETCH_SIZE} 行时静默截断），再对结果分页，
     * {@link PageImpl} 携带真实 total。
     *
     * <p>合表后统一查询：{@code status} 可 SUCCESS/SHORT_CIRCUITED/FAILED；{@code errorType} 仅对 FAILED 行有意义。
     */
    public Page<Execution> findExecutions(
            final String namespace,
            final Long pipelineId,
            final String status,
            final String errorType,
            final Instant from,
            final Instant to,
            final Pageable pageable) {
        String normStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        String normError = errorType == null || errorType.isBlank() ? null : errorType.toUpperCase();
        List<Execution> filtered = executionRepository
                .findByNamespaceFilters(
                        namespace, pipelineId, normStatus, normError, from, to, PageRequest.of(0, FETCH_SIZE))
                .stream()
                .map(ExecutionQueryService::toExecution)
                .toList();
        return paginate(filtered, pageable);
    }

    /** 单条按 (namespace, id) 软校验：namespace 不匹配返回 empty。 */
    public Optional<Execution> findExecution(final String namespace, final Long id) {
        return executionRepository
                .findById(id)
                .map(ExecutionQueryService::toExecution)
                .filter(e -> namespace.equals(e.namespace()));
    }

    // ---------- B6 聚合统计 ----------

    /**
     * 执行统计 + 成功率（合表后单表口径）：{@code byStatus}（SUCCESS/SHORT_CIRCUITED/FAILED）与
     * {@code total} 全来自 executions 单表（按 {@code started_at} 同窗口、全量行）。{@code failedCount}
     * 即 {@code byStatus["FAILED"]}。{@code successRate = (total - failedCount) / total}，分母 0 返回 1.0。
     * 单表全量行 → 无跨表采样偏差。
     */
    public ExecutionStats executionStats(
            final String namespace,
            final Instant from,
            final Instant to,
            final Long pipelineId,
            final String triggerType) {
        String normTrigger = triggerType == null || triggerType.isBlank() ? null : triggerType.toUpperCase();
        Map<String, Long> byStatus =
                toMap(executionRepository.countByStatus(namespace, from, to, pipelineId, normTrigger));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long failedCount = byStatus.getOrDefault("FAILED", 0L);
        double successRate = total == 0 ? 1.0 : (double) (total - failedCount) / total;
        return new ExecutionStats(namespace, from, to, byStatus, total, failedCount, successRate);
    }

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

        Map<String, Long> byKey;
        long stepSeconds;
        if (coarse) {
            stepSeconds = "5d".equalsIgnoreCase(bucket) ? STEP_5D : STEP_7D;
            byKey = loadCoarseBuckets(namespace, from, to, stepSeconds, pipelineId, normTrigger);
        } else {
            stepSeconds = daily ? STEP_1D : STEP_1H;
            byKey = loadFineBuckets(namespace, from, to, daily, pipelineId, normTrigger);
        }
        return zeroFill(from.getEpochSecond(), to.getEpochSecond(), stepSeconds, byKey);
    }

    private Map<String, Long> loadCoarseBuckets(
            final String namespace,
            final Instant from,
            final Instant to,
            final long stepSeconds,
            final Long pipelineId,
            final String triggerType) {
        List<Object[]> rows =
                executionRepository.timeseriesEpochByStatus(namespace, from, to, stepSeconds, pipelineId, triggerType);
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (Object[] row : rows) {
            long epochSec = ((Number) row[0]).longValue();
            String status = (String) row[1];
            long count = ((Number) row[2]).longValue();
            byKey.put(epochSec + "|" + status, count);
        }
        return byKey;
    }

    private Map<String, Long> loadFineBuckets(
            final String namespace,
            final Instant from,
            final Instant to,
            final boolean daily,
            final Long pipelineId,
            final String triggerType) {
        List<ExecutionTimeseriesBucket> rows = daily
                ? executionRepository.timeseriesDailyByStatus(namespace, from, to, pipelineId, triggerType)
                : executionRepository.timeseriesHourlyByStatus(namespace, from, to, pipelineId, triggerType);
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (ExecutionTimeseriesBucket b : rows) {
            long epochSec = java.time.ZonedDateTime.of(
                            b.year(), b.month(), b.day(), daily ? 0 : b.hour(), 0, 0, 0, java.time.ZoneOffset.UTC)
                    .toInstant()
                    .getEpochSecond();
            byKey.put(epochSec + "|" + b.status(), b.count());
        }
        return byKey;
    }

    private static List<ExecutionTimeseriesPoint> zeroFill(
            final long fromEpoch, final long toEpoch, final long stepSeconds, final Map<String, Long> byKey) {
        List<String> statuses = List.of("SUCCESS", "SHORT_CIRCUITED", "FAILED");
        List<ExecutionTimeseriesPoint> result = new ArrayList<>();
        long cursor = (fromEpoch / stepSeconds) * stepSeconds;
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

    private static Map<String, Long> toMap(final List<DimensionCount> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (DimensionCount dc : rows) {
            map.put(dc.dimension(), dc.count());
        }
        return map;
    }

    /**
     * B9 dashboard Top-N：按 pipelineId 聚合的执行计数（top limit 行）。dimension 字段为 pipelineId 的字符串形式，
     * 由 caller 自行 lookup 名称（{@code PipelineRegistry} 运行态查找）。
     */
    public List<DimensionCount> topPipelinesByExecution(
            final String namespace, final Instant from, final Instant to, final int limit) {
        int safeLimit = Math.max(1, Math.min(limit, FETCH_SIZE));
        return executionRepository.countByPipelineId(namespace, from, to, PageRequest.of(0, safeLimit));
    }

    private static <T> Page<T> paginate(final List<T> filtered, final Pageable pageable) {
        int from = (int) Math.min(pageable.getOffset(), filtered.size());
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(from, to), pageable, filtered.size());
    }

    private static Execution toExecution(final ExecutionPo po) {
        return new Execution(
                po.id,
                po.namespace,
                po.pipelineId,
                po.pipelineVersion == null ? 0 : po.pipelineVersion,
                po.triggerType,
                po.triggerEvent,
                po.subscriptionId,
                po.status,
                po.startedAt,
                po.endedAt,
                po.durationMs,
                po.traceId,
                po.createdAt,
                po.executionId,
                po.nodeName,
                po.errorType,
                po.errorMessage,
                po.stackTrace);
    }
}
