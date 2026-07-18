package com.imsw.observe.pipeline.application;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.imsw.observe.pipeline.domain.Execution;
import com.imsw.observe.pipeline.domain.FailedExecution;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionPo;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionRepository;
import com.imsw.observe.pipeline.infrastructure.persistence.FailedExecutionPo;
import com.imsw.observe.pipeline.infrastructure.persistence.FailedExecutionRepository;

@Service
public class ExecutionQueryService {

    private static final int DEFAULT_LIMIT = 100;

    private static final int MAX_LIMIT = 1000;

    private final ExecutionRepository executionRepository;

    private final FailedExecutionRepository failedExecutionRepository;

    public ExecutionQueryService(
            final ExecutionRepository executionRepository, final FailedExecutionRepository failedExecutionRepository) {
        this.executionRepository = executionRepository;
        this.failedExecutionRepository = failedExecutionRepository;
    }

    public List<Execution> findExecutions(final Long pipelineId, final int limit) {
        int safeLimit = sanitize(limit);
        return executionRepository.findAll(PageRequest.of(0, safeLimit)).stream()
                .filter(e -> pipelineId == null || pipelineId.equals(e.pipelineId))
                .map(ExecutionQueryService::toExecution)
                .toList();
    }

    public Optional<Execution> findExecution(final Long id) {
        return executionRepository.findById(id).map(ExecutionQueryService::toExecution);
    }

    public List<FailedExecution> findFailedExecutions(final Long pipelineId, final int limit) {
        int safeLimit = sanitize(limit);
        return failedExecutionRepository.findAll(PageRequest.of(0, safeLimit)).stream()
                .filter(e -> pipelineId == null || pipelineId.equals(e.pipelineId))
                .map(ExecutionQueryService::toFailedExecution)
                .toList();
    }

    public Optional<FailedExecution> findFailedExecution(final Long id) {
        return failedExecutionRepository.findById(id).map(ExecutionQueryService::toFailedExecution);
    }

    private static Execution toExecution(final ExecutionPo po) {
        return new Execution(
                po.id,
                po.pipelineId,
                po.pipelineVersion == null ? 0 : po.pipelineVersion,
                po.team,
                po.application,
                po.triggerType,
                po.triggerEvent,
                po.subscriptionId,
                po.status,
                po.startedAt,
                po.endedAt,
                po.durationMs,
                po.traceId,
                po.createdAt);
    }

    private static FailedExecution toFailedExecution(final FailedExecutionPo po) {
        return new FailedExecution(
                po.id,
                po.pipelineId,
                po.pipelineVersion == null ? 0 : po.pipelineVersion,
                po.executionId,
                po.team,
                po.application,
                po.triggerType,
                po.triggerEvent,
                po.subscriptionId,
                po.nodeName,
                po.errorType,
                po.errorMessage,
                po.stackTrace,
                po.status,
                po.createdAt,
                po.resolvedAt);
    }

    private static int sanitize(final int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
