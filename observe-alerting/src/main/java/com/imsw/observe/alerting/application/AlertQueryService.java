package com.imsw.observe.alerting.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.EvidenceEntity;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertMapper;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceMapper;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceRepository;

@Service
public class AlertQueryService {

    private static final int MAX_LIMIT = 1000;

    private static final long STEP_5D = 432_000L;
    private static final long STEP_7D = 604_800L;

    private final AlertRepository alertRepository;

    private final EvidenceRepository evidenceRepository;

    public AlertQueryService(final AlertRepository alertRepository, final EvidenceRepository evidenceRepository) {
        this.alertRepository = alertRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * 列表查询，软隔离铁律（ADR-0002）：namespace 必填，**namespace/过滤条件下推到 JPQL where**
     * （避免 findAll + 内存过滤在跨 namespace 超 {@code MAX_LIMIT} 行时静默截断），再对结果分页，
     * {@link PageImpl} 携带真实 total。
     *
     * <p>B9 / ADR-0004：原 {@code team} 一等列已下线为 {@code labelTeam} 投影列。为前端 API 稳定，
     * {@code team} 参数保留但语义改为「按 {@code label_team} 列过滤」（{@code labelTeam == team}）。
     */
    public Page<AlertEntity> findAlerts(
            final String namespace,
            final String status,
            final String team,
            final Long pipelineId,
            final Pageable pageable) {
        return findAlerts(namespace, status, team, pipelineId, null, null, null, pageable);
    }

    /** B6 扩展：列表查询补 from/to 时间范围（按 {@code starts_at}）与 severity 过滤。 */
    public Page<AlertEntity> findAlerts(
            final String namespace,
            final String status,
            final String team,
            final Long pipelineId,
            final String severity,
            final Instant from,
            final Instant to,
            final Pageable pageable) {
        List<AlertEntity> filtered = filteredAlerts(namespace, status, team, pipelineId, severity, from, to);
        return paginate(filtered, pageable);
    }

    /** 单条按 (namespace, id) 软校验：namespace 不匹配返回 empty，由控制层映射为 404。 */
    public Optional<AlertEntity> findById(final String namespace, final Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return alertRepository.findById(id).map(AlertMapper::toEntity).filter(a -> namespace.equals(a.namespace()));
    }

    /** 单条按 (namespace, alertId) 软校验：namespace 不匹配返回 empty。 */
    public Optional<EvidenceEntity> findEvidenceByAlertId(final String namespace, final Long alertId) {
        if (alertId == null) {
            return Optional.empty();
        }
        return evidenceRepository
                .findFirstByAlertIdOrderByCapturedAtAsc(alertId)
                .map(EvidenceMapper::toEntity)
                .filter(e -> namespace.equals(e.namespace()));
    }

    /** ADR-0005 §2：1:N 证据列表（按捕获时间升序），namespace 软校验。alert 不存在或无证据返回空列表。 */
    public List<EvidenceEntity> findEvidencesByAlertId(final String namespace, final Long alertId) {
        if (alertId == null) {
            return List.of();
        }
        return evidenceRepository.findAllByAlertIdOrderByCapturedAtAsc(alertId).stream()
                .map(EvidenceMapper::toEntity)
                .filter(e -> namespace.equals(e.namespace()))
                .toList();
    }

    // ---------- B6 聚合统计 ----------

    /** 告警统计：bySeverity / byStatus / total（namespace 下推 where）。 */
    public AlertStats alertStats(
            final String namespace,
            final Instant from,
            final Instant to,
            final String status,
            final String severity,
            final String team,
            final Long pipelineId) {
        String normStatus = normalize(status);
        String normSeverity = normalize(severity);
        Map<String, Long> bySeverity =
                toMap(alertRepository.countBySeverity(namespace, from, to, normStatus, normSeverity, team, pipelineId));
        Map<String, Long> byStatus =
                toMap(alertRepository.countByStatus(namespace, from, to, normSeverity, team, pipelineId));
        long total = bySeverity.values().stream().mapToLong(Long::longValue).sum();
        return new AlertStats(namespace, from, to, bySeverity, byStatus, total);
    }

    /**
     * 告警时间序列：按桶（1h/1d/5d/7d）返回 {@code [{bucketStart, count, severity}]}，缺桶补零（图表连续性）。
     */
    public List<TimeseriesPoint> alertTimeseries(
            final String namespace, final Instant from, final Instant to, final String bucket, final String severity) {
        String normSeverity = normalize(severity);
        if ("5d".equalsIgnoreCase(bucket) || "7d".equalsIgnoreCase(bucket)) {
            long stepSeconds = "5d".equalsIgnoreCase(bucket) ? STEP_5D : STEP_7D;
            List<TimeseriesBucketEpoch> rows =
                    alertRepository.timeseriesEpoch(namespace, from, to, stepSeconds, normSeverity);
            return fillEpochTimeseries(rows, from.getEpochSecond(), to.getEpochSecond(), stepSeconds, normSeverity);
        }
        return fineTimeseries(namespace, from, to, normSeverity, "1d".equalsIgnoreCase(bucket));
    }

    private List<TimeseriesPoint> fineTimeseries(
            final String namespace,
            final Instant from,
            final Instant to,
            final String normSeverity,
            final boolean daily) {
        List<TimeseriesBucket> rows = daily
                ? alertRepository.timeseriesDaily(namespace, from, to, normSeverity)
                : alertRepository.timeseriesHourly(namespace, from, to, normSeverity);
        // 从数据中收集已知的 severity 级别，用于补零
        Set<String> knownSeverities;
        if (normSeverity != null) {
            knownSeverities = Set.of(normSeverity);
        } else {
            knownSeverities = Set.of("CRITICAL", "WARNING", "INFO");
        }
        // 按 (桶起点 → 级别 → 计数) 分组
        Map<Instant, Map<String, Long>> byStart = new LinkedHashMap<>();
        for (TimeseriesBucket b : rows) {
            byStart.computeIfAbsent(bucketStart(b, daily), k -> new LinkedHashMap<>())
                    .merge(b.severity(), b.count(), Long::sum);
        }
        // 按 from→to 步进，每个桶对每种 severity 补零
        List<TimeseriesPoint> result = new ArrayList<>();
        long stepSeconds = daily ? 86_400L : 3_600L;
        long fromEpoch = from.getEpochSecond();
        long toEpoch = to.getEpochSecond();
        long cursor = alignToBucket(fromEpoch, stepSeconds);
        while (cursor < toEpoch) {
            Instant start = Instant.ofEpochSecond(cursor);
            Map<String, Long> severityCounts = byStart.get(start);
            for (String sev : knownSeverities) {
                long count = severityCounts != null ? severityCounts.getOrDefault(sev, 0L) : 0L;
                result.add(new TimeseriesPoint(start, count, sev));
            }
            cursor += stepSeconds;
        }
        return result;
    }

    private List<TimeseriesPoint> fillEpochTimeseries(
            final List<TimeseriesBucketEpoch> rows,
            final long fromEpoch,
            final long toEpoch,
            final long stepSeconds,
            final String normSeverity) {
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (TimeseriesBucketEpoch b : rows) {
            byKey.put(b.epochSeconds() + "|" + b.severity(), b.count());
        }
        Set<String> severities;
        if (normSeverity != null) {
            severities = Set.of(normSeverity);
        } else {
            severities = Set.of("CRITICAL", "WARNING", "INFO");
        }
        List<TimeseriesPoint> result = new ArrayList<>();
        long cursor = (fromEpoch / stepSeconds) * stepSeconds;
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

    private static long alignToBucket(final long epochSecond, final long stepSeconds) {
        return (epochSecond / stepSeconds) * stepSeconds;
    }

    /** B9 dashboard Top-N：按 fingerprint 聚合的告警计数（top limit 行）。 */
    public List<DimensionCount> topFingerprints(
            final String namespace, final Instant from, final Instant to, final int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return alertRepository.countByFingerprint(namespace, from, to, PageRequest.of(0, safeLimit));
    }

    /** B9 dashboard Top-N：按 label_team 投影列聚合的告警计数（top limit 行）。 */
    public List<DimensionCount> topTeams(
            final String namespace, final Instant from, final Instant to, final int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return alertRepository.countByTeamLabel(namespace, from, to, PageRequest.of(0, safeLimit));
    }

    private static Instant bucketStart(final TimeseriesBucket b, final boolean daily) {
        ZonedDateTime z = ZonedDateTime.of(b.year(), b.month(), b.day(), daily ? 0 : b.hour(), 0, 0, 0, ZoneOffset.UTC);
        return z.toInstant();
    }

    private static Map<String, Long> toMap(final List<DimensionCount> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (DimensionCount dc : rows) {
            map.put(dc.dimension(), dc.count());
        }
        return map;
    }

    private static String normalize(final String v) {
        return v == null || v.isBlank() ? null : v.toUpperCase();
    }

    private List<AlertEntity> filteredAlerts(
            final String namespace,
            final String status,
            final String team,
            final Long pipelineId,
            final String severity,
            final Instant from,
            final Instant to) {
        String normStatus = normalize(status);
        String normSeverity = normalize(severity);
        return alertRepository
                .findByNamespaceFilters(
                        namespace,
                        normStatus,
                        (team == null || team.isBlank()) ? null : team,
                        pipelineId,
                        normSeverity,
                        from,
                        to,
                        PageRequest.of(0, MAX_LIMIT))
                .stream()
                .map(AlertMapper::toEntity)
                .toList();
    }

    private static <T> Page<T> paginate(final List<T> filtered, final Pageable pageable) {
        int from = (int) Math.min(pageable.getOffset(), filtered.size());
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(from, to), pageable, filtered.size());
    }
}
