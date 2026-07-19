package com.imsw.observe.pipeline.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.imsw.observe.pipeline.application.DimensionCount;

public interface ExecutionRepository extends JpaRepository<ExecutionPo, Long> {

    /**
     * 列表查询（ADR-0002 软隔离铁律）：namespace 下推 where，避免 findAll + 内存过滤的跨 namespace 截断。
     * 合表后覆盖原 executions + failed_executions 两路查询：{@code status=FAILED} 即原失败列表，
     * {@code errorType} 过滤仅对 FAILED 行有意义（成功行 errorType 为 null，自动不命中）。
     */
    @Query("select e from ExecutionPo e where e.namespace = :namespace "
            + "and (:pipelineId is null or e.pipelineId = :pipelineId) "
            + "and (:status is null or e.status = :status) "
            + "and (:errorType is null or e.errorType = :errorType) "
            + "and (:from is null or e.startedAt >= :from) "
            + "and (:to is null or e.startedAt < :to) "
            + "order by e.id desc")
    List<ExecutionPo> findByNamespaceFilters(
            @Param("namespace") String namespace,
            @Param("pipelineId") Long pipelineId,
            @Param("status") String status,
            @Param("errorType") String errorType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pageable") org.springframework.data.domain.Pageable pageable);

    /**
     * 合表后单表按 status 聚合（SUCCESS / SHORT_CIRCUITED / FAILED）——successRate 由此准算，
     * 无跨表采样偏差。窗口统一按 {@code started_at}。
     */
    @Query("select new com.imsw.observe.pipeline.application.DimensionCount(e.status, count(e)) "
            + "from ExecutionPo e where e.namespace = :namespace "
            + "and e.startedAt >= :from and e.startedAt < :to "
            + "and (:pipelineId is null or e.pipelineId = :pipelineId) "
            + "and (:triggerType is null or e.triggerType = :triggerType) "
            + "group by e.status")
    List<DimensionCount> countByStatus(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pipelineId") Long pipelineId,
            @Param("triggerType") String triggerType);

    /**
     * B9 dashboard Top-N：按 pipelineId 聚合计数（含全部 status），count 降序取 limit 行（Pageable 承载）。
     * service 层把 pipelineId → name 一次 lookup（{@code PipelineRegistry} 运行态查找）。
     */
    @Query("select new com.imsw.observe.pipeline.application.DimensionCount("
            + "cast(e.pipelineId as string), count(e)) "
            + "from ExecutionPo e where e.namespace = :namespace "
            + "and e.startedAt >= :from and e.startedAt < :to "
            + "group by e.pipelineId order by count(e) desc, e.pipelineId asc")
    List<DimensionCount> countByPipelineId(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pageable") org.springframework.data.domain.Pageable pageable);
}
