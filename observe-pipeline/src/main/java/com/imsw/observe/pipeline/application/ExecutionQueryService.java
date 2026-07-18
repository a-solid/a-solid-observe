package com.imsw.observe.pipeline.application;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.imsw.observe.pipeline.domain.Execution;
import com.imsw.observe.pipeline.domain.FailedExecution;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionPo;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionRepository;
import com.imsw.observe.pipeline.infrastructure.persistence.FailedExecutionPo;
import com.imsw.observe.pipeline.infrastructure.persistence.FailedExecutionRepository;

@Service
public class ExecutionQueryService {

    private static final int FETCH_SIZE = 1000;

    private final ExecutionRepository executionRepository;

    private final FailedExecutionRepository failedExecutionRepository;

    public ExecutionQueryService(
            final ExecutionRepository executionRepository, final FailedExecutionRepository failedExecutionRepository) {
        this.executionRepository = executionRepository;
        this.failedExecutionRepository = failedExecutionRepository;
    }

    /**
     * 列表查询，软隔离铁律（ADR-0002）：namespace 必填，行内存过滤（与既有 pipelineId 过滤同款；
     * execution 资源表不对外暴露 BIGINT 物理主键，namespace 仅作软过滤维度）。
     *
     * <p>分页：先取候选行并完成 namespace/pipelineId 内存过滤，再分页，{@link PageImpl} 携带真实 total。
     * B6 会为 stats 场景补 JPQL where 下推。
     */
    public Page<Execution> findExecutions(final String namespace, final Long pipelineId, final Pageable pageable) {
        List<Execution> filtered = executionRepository.findAll(PageRequest.of(0, FETCH_SIZE)).stream()
                .filter(e -> namespace.equals(e.namespace))
                .filter(e -> pipelineId == null || pipelineId.equals(e.pipelineId))
                .map(ExecutionQueryService::toExecution)
                .toList();
        return paginate(filtered, pageable);
    }

    /** 单条按 (namespace, id) 软校验：namespace 不匹配返回 empty。 */
    public Optional<Execution> findExecution(final String namespace, final Long id) {
        return executionRepository
                .findById(id)
                .map(ExecutionQueryService::toExecution)
                .filter(e -> namespace.equals(e.namespace()));
    }

    public Page<FailedExecution> findFailedExecutions(
            final String namespace, final Long pipelineId, final Pageable pageable) {
        List<FailedExecution> filtered = failedExecutionRepository.findAll(PageRequest.of(0, FETCH_SIZE)).stream()
                .filter(e -> namespace.equals(e.namespace))
                .filter(e -> pipelineId == null || pipelineId.equals(e.pipelineId))
                .map(ExecutionQueryService::toFailedExecution)
                .toList();
        return paginate(filtered, pageable);
    }

    public Optional<FailedExecution> findFailedExecution(final String namespace, final Long id) {
        return failedExecutionRepository
                .findById(id)
                .map(ExecutionQueryService::toFailedExecution)
                .filter(e -> namespace.equals(e.namespace()));
    }

    private static <T> Page<T> paginate(final List<T> filtered, final Pageable pageable) {
        int from = (int) Math.min(pageable.getOffset(), filtered.size());
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(from, to), pageable, filtered.size());
    }

    private static Execution toExecution(final ExecutionPo po) {
        return new Execution(
                po.id,
                po.namespace,
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
                po.namespace,
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
}
