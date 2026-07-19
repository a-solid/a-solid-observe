package com.imsw.observe.pipeline.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.imsw.observe.pipeline.domain.Execution;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionPo;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionRepository;

@Service
public class ExecutionQueryService {

    private static final int FETCH_SIZE = 1000;

    private final ExecutionRepository executionRepository;

    public ExecutionQueryService(final ExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    /**
     * 列表查询，软隔离铁律（ADR-0002）：namespace 必填，**namespace/过滤条件下推到 JPQL where**
     * （避免 findAll + 内存过滤在跨 namespace 超 {@code FETCH_SIZE} 行时静默截断），再对结果分页，
     * {@link PageImpl} 携带真实 total。
     *
     * <p>合表后统一查询：{@code status} 可 SUCCESS/SHORT_CIRCUITED/FAILED；{@code errorType} 仅对 FAILED 行有意义。
     */
    public Page<Execution> findExecutions(
            final String namespace,
            final Long pipelineId,
            final String status,
            final String errorType,
            final Instant from,
            final Instant to,
            final Pageable pageable) {
        String normStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        String normError = errorType == null || errorType.isBlank() ? null : errorType.toUpperCase();
        List<Execution> filtered = executionRepository
                .findByNamespaceFilters(
                        namespace, pipelineId, normStatus, normError, from, to, PageRequest.of(0, FETCH_SIZE))
                .stream()
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

    // ---------- B6 聚合统计 ----------

    /**
     * 执行统计 + 成功率（合表后单表口径）：{@code byStatus}（SUCCESS/SHORT_CIRCUITED/FAILED）与
     * {@code total} 全来自 executions 单表（按 {@code started_at} 同窗口、全量行）。{@code failedCount}
     * 即 {@code byStatus["FAILED"]}。{@code successRate = (total - failedCount) / total}，分母 0 返回 1.0。
     * 单表全量行 → 无跨表采样偏差。
     */
    public ExecutionStats executionStats(
            final String namespace,
            final Instant from,
            final Instant to,
            final Long pipelineId,
            final String triggerType) {
        String normTrigger = triggerType == null || triggerType.isBlank() ? null : triggerType.toUpperCase();
        Map<String, Long> byStatus =
                toMap(executionRepository.countByStatus(namespace, from, to, pipelineId, normTrigger));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long failedCount = byStatus.getOrDefault("FAILED", 0L);
        double successRate = total == 0 ? 1.0 : (double) (total - failedCount) / total;
        return new ExecutionStats(namespace, from, to, byStatus, total, failedCount, successRate);
    }

    private static Map<String, Long> toMap(final List<DimensionCount> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (DimensionCount dc : rows) {
            map.put(dc.dimension(), dc.count());
        }
        return map;
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
                po.triggerType,
                po.triggerEvent,
                po.subscriptionId,
                po.status,
                po.startedAt,
                po.endedAt,
                po.durationMs,
                po.traceId,
                po.createdAt,
                po.executionId,
                po.nodeName,
                po.errorType,
                po.errorMessage,
                po.stackTrace);
    }
}
