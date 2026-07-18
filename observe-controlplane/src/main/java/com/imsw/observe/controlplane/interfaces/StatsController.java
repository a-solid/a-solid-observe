package com.imsw.observe.controlplane.interfaces;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.alerting.application.AlertQueryService;
import com.imsw.observe.alerting.application.TimeseriesPoint;
import com.imsw.observe.controlplane.interfaces.dto.AlertStatsDto;
import com.imsw.observe.controlplane.interfaces.dto.ExecutionStatsDto;
import com.imsw.observe.controlplane.interfaces.dto.TimeseriesPointDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.ErrorCode;
import com.imsw.observe.controlplane.interfaces.web.ErrorResponseException;
import com.imsw.observe.pipeline.application.ExecutionQueryService;

/**
 * 看板统计接口（B6，ADR-0002 软隔离：{@code ?namespace=} 必填）。
 *
 * <p>聚合 / 时间序列 / 成功率——供前端看板图表。响应走 B5 {@link ApiResponse} 信封。
 * 时间窗口 {@code [from, to)} 半开，{@code from}/{@code to} 必填。
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final AlertQueryService alertQueryService;

    private final ExecutionQueryService executionQueryService;

    public StatsController(
            final AlertQueryService alertQueryService, final ExecutionQueryService executionQueryService) {
        this.alertQueryService = alertQueryService;
        this.executionQueryService = executionQueryService;
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
        if (!"1h".equalsIgnoreCase(bucket) && !"1d".equalsIgnoreCase(bucket)) {
            throw new ErrorResponseException(
                    ErrorCode.BAD_REQUEST.httpStatus(), ErrorCode.BAD_REQUEST, "bucket must be one of: 1h, 1d");
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
}
