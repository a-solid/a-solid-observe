package com.imsw.observe.pipeline.infrastructure.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FailedExecutionRepository extends JpaRepository<FailedExecutionPo, Long> {

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
