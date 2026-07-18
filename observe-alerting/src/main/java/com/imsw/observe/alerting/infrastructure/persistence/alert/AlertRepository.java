package com.imsw.observe.alerting.infrastructure.persistence.alert;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.imsw.observe.alerting.application.DimensionCount;
import com.imsw.observe.alerting.application.TimeseriesBucket;

public interface AlertRepository extends JpaRepository<AlertPo, Long> {

    Optional<AlertPo> findFirstByFingerprintAndStatusOrderByIdAsc(String fingerprint, String status);

    List<AlertPo> findByStatusOrderByIdDesc(String status, Pageable pageable);

    @Query("select a.id from AlertPo a where a.status = 'FIRING' and a.endsAt < :now order by a.id")
    List<Long> findExpiredFiringIds(@Param("now") Instant now, Pageable pageable);

    @Modifying
    @Query("update AlertPo a set a.lastSeenAt = :now, a.endsAt = :endsAt, "
            + "a.dedupCount = a.dedupCount + 1, a.updatedAt = :now where a.id = :id")
    int updateEmit(@Param("id") Long id, @Param("now") Instant now, @Param("endsAt") Instant endsAt);

    @Modifying
    @Query("update AlertPo a set a.status = 'RESOLVED', a.resolvedAt = a.endsAt, a.updatedAt = :now "
            + "where a.id in :ids and a.status = 'FIRING'")
    int resolveBatch(@Param("ids") List<Long> ids, @Param("now") Instant now);

    /**
     * ADR-0005 §4：ack/resolve/ignore 的 CAS 式处置更新。{@code fromStatus} 作为乐观锁条件——
     * 影响 0 行表示并发已转移，service 层抛 ConflictException。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AlertPo a set a.status = :toStatus, a.ackNote = :note, a.ackBy = :by, "
            + "a.ackAt = :at, a.resolvedAt = coalesce(:resolvedAt, a.resolvedAt), a.updatedAt = :at "
            + "where a.id = :id and a.namespace = :namespace and a.status = :fromStatus")
    int applyDisposition(
            @Param("id") Long id,
            @Param("namespace") String namespace,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("note") String note,
            @Param("by") String by,
            @Param("at") Instant at,
            @Param("resolvedAt") Instant resolvedAt);

    // ---------- B6 聚合统计（namespace 下推 where，可选过滤 :x is null or ...） ----------

    @Query("select new com.imsw.observe.alerting.application.DimensionCount(a.severity, count(a)) "
            + "from AlertPo a where a.namespace = :namespace and a.startsAt >= :from and a.startsAt < :to "
            + "and (:status is null or a.status = :status) "
            + "and (:severity is null or a.severity = :severity) "
            + "and (:team is null or a.team = :team) "
            + "and (:pipelineId is null or a.pipelineId = :pipelineId) "
            + "group by a.severity")
    List<DimensionCount> countBySeverity(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("status") String status,
            @Param("severity") String severity,
            @Param("team") String team,
            @Param("pipelineId") Long pipelineId);

    @Query("select new com.imsw.observe.alerting.application.DimensionCount(a.status, count(a)) "
            + "from AlertPo a where a.namespace = :namespace and a.startsAt >= :from and a.startsAt < :to "
            + "and (:severity is null or a.severity = :severity) "
            + "and (:team is null or a.team = :team) "
            + "and (:pipelineId is null or a.pipelineId = :pipelineId) "
            + "group by a.status")
    List<DimensionCount> countByStatus(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("severity") String severity,
            @Param("team") String team,
            @Param("pipelineId") Long pipelineId);

    @Query("select new com.imsw.observe.alerting.application.TimeseriesBucket("
            + "extract(year from a.startsAt), extract(month from a.startsAt), "
            + "extract(day from a.startsAt), extract(hour from a.startsAt), count(a)) "
            + "from AlertPo a where a.namespace = :namespace and a.startsAt >= :from and a.startsAt < :to "
            + "and (:severity is null or a.severity = :severity) "
            + "group by extract(year from a.startsAt), extract(month from a.startsAt), "
            + "extract(day from a.startsAt), extract(hour from a.startsAt) "
            + "order by extract(year from a.startsAt), extract(month from a.startsAt), "
            + "extract(day from a.startsAt), extract(hour from a.startsAt)")
    List<TimeseriesBucket> timeseriesHourly(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("severity") String severity);

    @Query("select new com.imsw.observe.alerting.application.TimeseriesBucket("
            + "extract(year from a.startsAt), extract(month from a.startsAt), "
            + "extract(day from a.startsAt), 0, count(a)) "
            + "from AlertPo a where a.namespace = :namespace and a.startsAt >= :from and a.startsAt < :to "
            + "and (:severity is null or a.severity = :severity) "
            + "group by extract(year from a.startsAt), extract(month from a.startsAt), "
            + "extract(day from a.startsAt) "
            + "order by extract(year from a.startsAt), extract(month from a.startsAt), "
            + "extract(day from a.startsAt)")
    List<TimeseriesBucket> timeseriesDaily(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("severity") String severity);
}
