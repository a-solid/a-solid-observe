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
     */
    @Query("select e from ExecutionPo e where e.namespace = :namespace "
            + "and (:pipelineId is null or e.pipelineId = :pipelineId) "
            + "and (:status is null or e.status = :status) "
            + "and (:from is null or e.startedAt >= :from) "
            + "and (:to is null or e.startedAt < :to) "
            + "order by e.id desc")
    List<ExecutionPo> findByNamespaceFilters(
            @Param("namespace") String namespace,
            @Param("pipelineId") Long pipelineId,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pageable") org.springframework.data.domain.Pageable pageable);

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
}
