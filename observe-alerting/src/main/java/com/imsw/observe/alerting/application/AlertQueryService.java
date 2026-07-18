package com.imsw.observe.alerting.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.EvidenceEntity;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertMapper;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceMapper;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceRepository;

@Service
public class AlertQueryService {

    private static final int MAX_LIMIT = 1000;

    private final AlertRepository alertRepository;

    private final EvidenceRepository evidenceRepository;

    public AlertQueryService(final AlertRepository alertRepository, final EvidenceRepository evidenceRepository) {
        this.alertRepository = alertRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * 列表查询，软隔离铁律（ADR-0002）：namespace 必填，行内存过滤（与既有 team/pipelineId 过滤同款；
     * alert/evidence 资源表不对外暴露 BIGINT 物理主键，namespace 仅作软过滤维度）。
     *
     * <p>分页：先按 status 取候选行并完成 namespace/team/pipelineId 内存过滤，再对过滤结果分页，
     * {@link PageImpl} 携带真实 total。B6 会为 stats 场景补 JPQL where 下推；本方法保持现有过滤语义。
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
     * 告警时间序列：按桶（1h/1d）返回 {@code [{bucketStart, count}]}，缺桶补零（图表连续性）。
     *
     * @param bucket {@code "1h"} 或 {@code "1d"}，其它按 {@code 1h} 处理。
     */
    public List<TimeseriesPoint> alertTimeseries(
            final String namespace, final Instant from, final Instant to, final String bucket, final String severity) {
        String normSeverity = normalize(severity);
        boolean daily = "1d".equalsIgnoreCase(bucket);
        List<TimeseriesBucket> rows = daily
                ? alertRepository.timeseriesDaily(namespace, from, to, normSeverity)
                : alertRepository.timeseriesHourly(namespace, from, to, normSeverity);
        Map<Instant, Long> byStart = new LinkedHashMap<>();
        for (TimeseriesBucket b : rows) {
            byStart.put(bucketStart(b, daily), b.count());
        }
        // 按 from→to 步进补零
        List<TimeseriesPoint> result = new ArrayList<>();
        long stepSeconds = daily ? 86_400L : 3_600L;
        long fromEpoch = from.getEpochSecond();
        long toEpoch = to.getEpochSecond();
        // 对齐到桶边界
        long cursor = alignToBucket(fromEpoch, stepSeconds);
        while (cursor < toEpoch) {
            Instant start = Instant.ofEpochSecond(cursor);
            result.add(new TimeseriesPoint(start, byStart.getOrDefault(start, 0L)));
            cursor += stepSeconds;
        }
        return result;
    }

    private static long alignToBucket(final long epochSecond, final long stepSeconds) {
        return (epochSecond / stepSeconds) * stepSeconds;
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
        int fetchSize = MAX_LIMIT;
        List<AlertEntity> candidates;
        if (status != null && !status.isBlank()) {
            candidates =
                    alertRepository
                            .findByStatusOrderByIdDesc(status.toUpperCase(), PageRequest.of(0, fetchSize))
                            .stream()
                            .map(AlertMapper::toEntity)
                            .toList();
        } else {
            candidates = alertRepository.findAll(PageRequest.of(0, fetchSize)).stream()
                    .map(AlertMapper::toEntity)
                    .toList();
        }
        String normSeverity = normalize(severity);
        return candidates.stream()
                .filter(a -> namespace.equals(a.namespace()))
                .filter(a -> team == null || team.isBlank() || team.equals(a.team()))
                .filter(a -> pipelineId == null || pipelineId.equals(a.pipelineId()))
                .filter(a ->
                        normSeverity == null || normSeverity.equals(a.severity().name()))
                .filter(a -> from == null || !a.startsAt().isBefore(from))
                .filter(a -> to == null || a.startsAt().isBefore(to))
                .toList();
    }

    private static <T> Page<T> paginate(final List<T> filtered, final Pageable pageable) {
        int from = (int) Math.min(pageable.getOffset(), filtered.size());
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(from, to), pageable, filtered.size());
    }
}
