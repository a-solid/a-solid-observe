package com.imsw.observe.pipeline.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.imsw.observe.pipeline.application.DimensionCount;

public interface ExecutionRepository extends JpaRepository<ExecutionPo, Long> {

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
