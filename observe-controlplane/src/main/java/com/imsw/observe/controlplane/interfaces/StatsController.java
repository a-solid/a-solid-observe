package com.imsw.observe.controlplane.interfaces;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.alerting.application.AlertQueryService;
import com.imsw.observe.alerting.application.TimeseriesPoint;
import com.imsw.observe.controlplane.application.DashboardStatsService;
import com.imsw.observe.controlplane.interfaces.dto.AlertStatsDto;
import com.imsw.observe.controlplane.interfaces.dto.DashboardStatsDto;
import com.imsw.observe.controlplane.interfaces.dto.ExecutionStatsDto;
import com.imsw.observe.controlplane.interfaces.dto.ExecutionTimeseriesPointDto;
import com.imsw.observe.controlplane.interfaces.dto.TimeseriesPointDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.ErrorCode;
import com.imsw.observe.controlplane.interfaces.web.ErrorResponseException;
import com.imsw.observe.pipeline.application.ExecutionQueryService;
import com.imsw.observe.pipeline.application.ExecutionTimeseriesPoint;

/**
 * 看板统计接口（B6，ADR-0002 软隔离：{@code ?namespace=} 必填）。
 *
 * <p>聚合 / 时间序列 / 成功率——供前端看板图表。响应走 B5 {@link ApiResponse} 信封。
 * 时间窗口 {@code [from, to)} 半开，{@code from}/{@code to} 必填（除 {@code /dashboard} 外，可缺省归一化为"今天"）。
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final AlertQueryService alertQueryService;

    private final ExecutionQueryService executionQueryService;

    private final DashboardStatsService dashboardStatsService;

    public StatsController(
            final AlertQueryService alertQueryService,
            final ExecutionQueryService executionQueryService,
            final DashboardStatsService dashboardStatsService) {
        this.alertQueryService = alertQueryService;
        this.executionQueryService = executionQueryService;
        this.dashboardStatsService = dashboardStatsService;
    }

    @GetMapping("/alerts")
    public ApiResponse<AlertStatsDto> alertStats(
            @RequestParam final String namespace,
            @RequestParam final Instant from,
            @RequestParam final Instant to,
            @RequestParam(name = "status", required = false) final String status,
            @RequestParam(name = "severity", required = false) final String severity,
            @RequestParam(name = "team", required = false) final String team,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId) {
        return ApiResponse.ok(AlertStatsDto.from(
                alertQueryService.alertStats(namespace, from, to, status, severity, team, pipelineId)));
    }

    @GetMapping("/alerts/timeseries")
    public ApiResponse<List<TimeseriesPointDto>> alertTimeseries(
            @RequestParam final String namespace,
            @RequestParam final Instant from,
            @RequestParam final Instant to,
            @RequestParam(name = "bucket", required = false, defaultValue = "1h") final String bucket,
            @RequestParam(name = "severity", required = false) final String severity) {
        if (!"1h".equalsIgnoreCase(bucket)
                && !"1d".equalsIgnoreCase(bucket)
                && !"5d".equalsIgnoreCase(bucket)
                && !"7d".equalsIgnoreCase(bucket)) {
            throw new ErrorResponseException(
                    ErrorCode.BAD_REQUEST.httpStatus(), ErrorCode.BAD_REQUEST, "bucket must be one of: 1h, 1d, 5d, 7d");
        }
        List<TimeseriesPoint> points = alertQueryService.alertTimeseries(namespace, from, to, bucket, severity);
        return ApiResponse.ok(points.stream().map(TimeseriesPointDto::from).toList());
    }

    @GetMapping("/executions")
    public ApiResponse<ExecutionStatsDto> executionStats(
            @RequestParam final String namespace,
            @RequestParam final Instant from,
            @RequestParam final Instant to,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "trigger_type", required = false) final String triggerType) {
        return ApiResponse.ok(ExecutionStatsDto.from(
                executionQueryService.executionStats(namespace, from, to, pipelineId, triggerType)));
    }

    @GetMapping("/executions/timeseries")
    public ApiResponse<List<ExecutionTimeseriesPointDto>> executionTimeseries(
            @RequestParam final String namespace,
            @RequestParam final Instant from,
            @RequestParam final Instant to,
            @RequestParam(name = "bucket", required = false, defaultValue = "1h") final String bucket,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "trigger_type", required = false) final String triggerType) {
        if (!"1h".equalsIgnoreCase(bucket)
                && !"1d".equalsIgnoreCase(bucket)
                && !"5d".equalsIgnoreCase(bucket)
                && !"7d".equalsIgnoreCase(bucket)) {
            throw new ErrorResponseException(
                    ErrorCode.BAD_REQUEST.httpStatus(), ErrorCode.BAD_REQUEST, "bucket must be one of: 1h, 1d, 5d, 7d");
        }
        List<ExecutionTimeseriesPoint> points =
                executionQueryService.executionTimeseries(namespace, from, to, bucket, pipelineId, triggerType);
        return ApiResponse.ok(
                points.stream().map(ExecutionTimeseriesPointDto::from).toList());
    }

    /**
     * B9 dashboard 聚合：一次性返回 dashboard 所需全部标量聚合（避免前端发 6+ 个 round-trip）。
     *
     * <p>区间归一化：{@code from}/{@code to} 缺省时 = 今天 0:00 ~ 现在 UTC。Top-N 默认 5（可由 {@code limit} 覆盖）。
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardStatsDto> dashboard(
            @RequestParam final String namespace,
            @RequestParam(name = "from", required = false) final Instant from,
            @RequestParam(name = "to", required = false) final Instant to,
            @RequestParam(name = "limit", required = false, defaultValue = "5") final int limit) {
        if (limit < 1 || limit > 50) {
            throw new ErrorResponseException(
                    ErrorCode.BAD_REQUEST.httpStatus(), ErrorCode.BAD_REQUEST, "limit must be in [1, 50]");
        }
        return ApiResponse.ok(dashboardStatsService.aggregate(namespace, from, to, limit));
    }
}
