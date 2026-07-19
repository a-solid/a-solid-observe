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

    @Query("select a.id from AlertPo a where a.status = 'ACTIVE' and a.endsAt < :now order by a.id")
    List<Long> findExpiredActiveIds(@Param("now") Instant now, Pageable pageable);

    /**
     * 列表查询（ADR-0002 软隔离铁律）：namespace 下推 where，避免 findAll + 内存过滤导致的跨 namespace 截断。
     * 所有可选过滤用 {@code :x is null or ...} 表达；结果按 id 倒序，分页由 {@link Pageable} 承担。
     */
    @Query("select a from AlertPo a where a.namespace = :namespace "
            + "and (:status is null or a.status = :status) "
            + "and (:team is null or a.labelTeam = :team) "
            + "and (:pipelineId is null or a.pipelineId = :pipelineId) "
            + "and (:severity is null or a.severity = :severity) "
            + "and (:from is null or a.startsAt >= :from) "
            + "and (:to is null or a.startsAt < :to) "
            + "order by a.id desc")
    List<AlertPo> findByNamespaceFilters(
            @Param("namespace") String namespace,
            @Param("status") String status,
            @Param("team") String team,
            @Param("pipelineId") Long pipelineId,
            @Param("severity") String severity,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pageable") Pageable pageable);

    @Modifying
    @Query("update AlertPo a set a.lastSeenAt = :now, a.endsAt = :endsAt, "
            + "a.dedupCount = a.dedupCount + 1, a.updatedAt = :now where a.id = :id")
    int updateEmit(@Param("id") Long id, @Param("now") Instant now, @Param("endsAt") Instant endsAt);

    @Modifying
    @Query("update AlertPo a set a.status = 'EXPIRED', a.resolvedAt = a.endsAt, a.updatedAt = :now "
            + "where a.id in :ids and a.status = 'ACTIVE'")
    int expireBatch(@Param("ids") List<Long> ids, @Param("now") Instant now);

    /**
     * ADR-0005 §4（两维分离后）：ack/ignore 的处置更新——只改 {@code disposition} 列，不动 {@code status}
     * （维度正交：任何 status——ACTIVE 或 EXPIRED——都能被打 ack/ignore）。{@code (id, namespace)} 定位，
     * 影响 0 行表示行不存在，service 层抛 ResourceNotFoundException。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AlertPo a set a.disposition = :disposition, a.ackNote = :note, a.ackBy = :by, "
            + "a.ackAt = :at, a.updatedAt = :at where a.id = :id and a.namespace = :namespace")
    int applyDisposition(
            @Param("id") Long id,
            @Param("namespace") String namespace,
            @Param("disposition") String disposition,
            @Param("note") String note,
            @Param("by") String by,
            @Param("at") Instant at);

    // ---------- B6 聚合统计（namespace 下推 where，可选过滤 :x is null or ...） ----------
    // B9 / ADR-0004：原 a.team 一等列下线为 a.labelTeam 投影列；JPQL where 同步重指向 a.labelTeam。

    @Query("select new com.imsw.observe.alerting.application.DimensionCount(a.severity, count(a)) "
            + "from AlertPo a where a.namespace = :namespace and a.startsAt >= :from and a.startsAt < :to "
            + "and (:status is null or a.status = :status) "
            + "and (:severity is null or a.severity = :severity) "
            + "and (:team is null or a.labelTeam = :team) "
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
            + "and (:team is null or a.labelTeam = :team) "
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
