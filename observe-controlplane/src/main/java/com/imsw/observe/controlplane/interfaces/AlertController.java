package com.imsw.observe.controlplane.interfaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.alerting.application.AlertQueryService;
import com.imsw.observe.controlplane.interfaces.dto.AlertDto;
import com.imsw.observe.controlplane.interfaces.dto.EvidenceDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.PageResponse;
import com.imsw.observe.controlplane.interfaces.web.Pages;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;

/**
 * Alerts 查询接口（ADR-0002 软隔离铁律）。
 *
 * <p>Alerts/Evidence 是运行时派生行，以 snowflake BIGINT id 为物理主键，namespace 仅作软隔离过滤——
 * 故 namespace 不进路径，而以必填查询参数 {@code ?namespace=} 形式出现（与既有的 {@code ?status=&team=&pipeline_id=}
 * 过滤参数一致）。单条按 {@code (namespace, id)} 软校验：namespace 不匹配视为 404，避免跨租户探测。
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertQueryService alertQueryService;

    public AlertController(final AlertQueryService alertQueryService) {
        this.alertQueryService = alertQueryService;
    }

    @GetMapping
    public PageResponse<AlertDto> listAlerts(
            @RequestParam final String namespace,
            @RequestParam(name = "status", required = false) final String status,
            @RequestParam(name = "team", required = false) final String team,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "page", required = false, defaultValue = "1") final int page,
            @RequestParam(name = "size", required = false) final Integer size,
            @RequestParam(name = "limit", required = false) final Integer limit) {
        return Pages.toResponse(
                alertQueryService.findAlerts(namespace, status, team, pipelineId, Pages.pageable(page, size, limit)),
                AlertDto::from,
                page,
                size,
                limit);
    }

    @GetMapping("/{id}")
    public ApiResponse<AlertDto> getAlert(@PathVariable final Long id, @RequestParam final String namespace) {
        return alertQueryService
                .findById(namespace, id)
                .map(AlertDto::from)
                .map(ApiResponse::ok)
                .orElseThrow(
                        () -> new ResourceNotFoundException("alert " + id + " not found in namespace " + namespace));
    }

    @GetMapping("/{id}/evidence")
    public ApiResponse<EvidenceDto> getEvidence(@PathVariable final Long id, @RequestParam final String namespace) {
        return alertQueryService
                .findEvidenceByAlertId(namespace, id)
                .map(EvidenceDto::from)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "evidence for alert " + id + " not found in namespace " + namespace));
    }
}
