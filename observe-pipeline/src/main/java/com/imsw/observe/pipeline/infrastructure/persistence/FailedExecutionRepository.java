package com.imsw.observe.pipeline.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FailedExecutionRepository extends JpaRepository<FailedExecutionPo, Long> {

    /**
     * 列表查询（ADR-0002 软隔离铁律）：namespace 下推 where，避免 findAll + 内存过滤的跨 namespace 截断。
     */
    @Query("select f from FailedExecutionPo f where f.namespace = :namespace "
            + "and (:pipelineId is null or f.pipelineId = :pipelineId) "
            + "and (:status is null or f.status = :status) "
            + "and (:errorType is null or f.errorType = :errorType) "
            + "and (:from is null or f.createdAt >= :from) "
            + "and (:to is null or f.createdAt < :to) "
            + "order by f.id desc")
    List<FailedExecutionPo> findByNamespaceFilters(
            @Param("namespace") String namespace,
            @Param("pipelineId") Long pipelineId,
            @Param("status") String status,
            @Param("errorType") String errorType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pageable") Pageable pageable);

    @Query("select count(f) from FailedExecutionPo f where f.namespace = :namespace "
            + "and f.createdAt >= :from and f.createdAt < :to "
            + "and (:pipelineId is null or f.pipelineId = :pipelineId) "
            + "and (:triggerType is null or f.triggerType = :triggerType)")
    long countInWindow(
            @Param("namespace") String namespace,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("pipelineId") Long pipelineId,
            @Param("triggerType") String triggerType);
}
