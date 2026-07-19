package com.imsw.observe.controlplane.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;

import com.imsw.observe.alerting.application.AlertQueryService;
import com.imsw.observe.alerting.application.AlertStats;
import com.imsw.observe.alerting.application.DimensionCount;
import com.imsw.observe.controlplane.interfaces.dto.DashboardStatsDto;
import com.imsw.observe.controlplane.interfaces.dto.DashboardStatsDto.DimensionCountDto;
import com.imsw.observe.controlplane.interfaces.dto.DashboardStatsDto.PipelineCountDto;
import com.imsw.observe.pipeline.application.ExecutionQueryService;
import com.imsw.observe.pipeline.application.ExecutionStats;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.domain.Pipeline;

/**
 * Dashboard 聚合服务（B9）：一次性返回 dashboard 页面所需的全部标量聚合。
 *
 * <p>组合 {@link AlertQueryService} + {@link ExecutionQueryService}（不直接打 repository），
 * 复用其 {@code alertStats} / {@code executionStats} / {@code topFingerprints} / {@code topTeams} /
 * {@code topPipelinesByExecution} 方法。pipelineId → name 由 {@link PipelineRegistry} 运行态查找
 * （未加载的 pipeline 用 {@code "<unloaded:id>"} 兜底——避免 dashboard 因 stale 配置抛错）。
 *
 * <p>区间归一化：{@code from}/{@code to} 为 null 时默认 = 今天 0:00 ~ 现在 UTC（覆盖 hero "today" 语义）。
 *
 * <p>Top-N 默认 limit = 5（dashboard 卡片尺寸约定）。
 */
@Service
public class DashboardStatsService {

    /** dashboard Top-N 卡片数量约定。 */
    static final int DEFAULT_TOP_LIMIT = 5;

    private final AlertQueryService alertQueryService;

    private final ExecutionQueryService executionQueryService;

    private final PipelineRegistry pipelineRegistry;

    public DashboardStatsService(
            final AlertQueryService alertQueryService,
            final ExecutionQueryService executionQueryService,
            final PipelineRegistry pipelineRegistry) {
        this.alertQueryService = alertQueryService;
        this.executionQueryService = executionQueryService;
        this.pipelineRegistry = pipelineRegistry;
    }

    public DashboardStatsDto aggregate(
            final String namespace, final Instant fromIn, final Instant toIn, final int limitIn) {
        int limit = limitIn <= 0 ? DEFAULT_TOP_LIMIT : limitIn;
        Instant to = toIn != null ? toIn : Instant.now();
        Instant from = fromIn != null ? fromIn : startOfTodayUtc();

        AlertStats alertStats = alertQueryService.alertStats(namespace, from, to, null, null, null, null);
        ExecutionStats execStats = executionQueryService.executionStats(namespace, from, to, null, null);
        List<DimensionCount> teamDist = alertQueryService.topTeams(namespace, from, to, limit);
        List<com.imsw.observe.pipeline.application.DimensionCount> topPipelinesRaw =
                executionQueryService.topPipelinesByExecution(namespace, from, to, limit);
        List<DimensionCount> topFingerprints = alertQueryService.topFingerprints(namespace, from, to, limit);

        return new DashboardStatsDto(
                namespace,
                from,
                to,
                alertStats.bySeverity(),
                alertStats.byStatus(),
                alertStats.total(),
                execStats.byStatus(),
                execStats.total(),
                execStats.failedCount(),
                execStats.successRate(),
                // hero 数字 = 区间 total（区间 = 今天时即"今天的 events/alerts"）
                execStats.total(),
                alertStats.total(),
                toDimensionDtos(teamDist),
                toPipelineDtos(topPipelinesRaw),
                toDimensionDtos(topFingerprints));
    }

    private List<DimensionCountDto> toDimensionDtos(final List<DimensionCount> rows) {
        return rows.stream()
                .map(dc -> new DimensionCountDto(dc.dimension(), dc.count()))
                .toList();
    }

    private List<PipelineCountDto> toPipelineDtos(
            final List<com.imsw.observe.pipeline.application.DimensionCount> rows) {
        return rows.stream()
                .map(dc -> {
                    Long pipelineId = parseLongOrNull(dc.dimension());
                    String name = lookupPipelineName(pipelineId);
                    return new PipelineCountDto(pipelineId, name, dc.count());
                })
                .toList();
    }

    private String lookupPipelineName(final Long pipelineId) {
        if (pipelineId == null) {
            return "<unknown>";
        }
        Pipeline pipeline = pipelineRegistry.snapshot().pipelineById(pipelineId);
        if (pipeline == null) {
            // 未加载 / 已删除 / 未热加载：用占位字符串避免 dashboard 因 stale 配置 500
            return "<unloaded:" + pipelineId + ">";
        }
        return pipeline.name();
    }

    private static Long parseLongOrNull(final String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant startOfTodayUtc() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
