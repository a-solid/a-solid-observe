package com.imsw.observe.controlplane.interfaces;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.alerting.application.AlertDispositionService;
import com.imsw.observe.alerting.application.AlertQueryService;
import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.controlplane.interfaces.dto.AlertDto;
import com.imsw.observe.controlplane.interfaces.dto.EvidenceDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.PageResponse;
import com.imsw.observe.controlplane.interfaces.web.Pages;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;

/**
 * Alerts 查询 + 处置接口（ADR-0002 软隔离铁律；ADR-0005 ack/ignore + 1:N evidence）。
 *
 * <p>namespace 作必填查询参数 {@code ?namespace=}；单条按 {@code (namespace, id)} 软校验，不匹配返回 404。
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertQueryService alertQueryService;

    private final AlertDispositionService dispositionService;

    public AlertController(
            final AlertQueryService alertQueryService, final AlertDispositionService dispositionService) {
        this.alertQueryService = alertQueryService;
        this.dispositionService = dispositionService;
    }

    @GetMapping
    public PageResponse<AlertDto> listAlerts(
            @RequestParam final String namespace,
            @RequestParam(name = "status", required = false) final String status,
            @RequestParam(name = "team", required = false) final String team,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "severity", required = false) final String severity,
            @RequestParam(name = "from", required = false) final java.time.Instant from,
            @RequestParam(name = "to", required = false) final java.time.Instant to,
            @RequestParam(name = "page", required = false, defaultValue = "1") final int page,
            @RequestParam(name = "size", required = false) final Integer size,
            @RequestParam(name = "limit", required = false) final Integer limit) {
        return Pages.toResponse(
                alertQueryService.findAlerts(
                        namespace, status, team, pipelineId, severity, from, to, Pages.pageable(page, size, limit)),
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

    /** ADR-0005 §2：1:N 证据列表。 */
    @GetMapping("/{id}/evidence")
    public ApiResponse<List<EvidenceDto>> getEvidence(
            @PathVariable final Long id, @RequestParam final String namespace) {
        if (alertQueryService.findById(namespace, id).isEmpty()) {
            throw new ResourceNotFoundException("alert " + id + " not found in namespace " + namespace);
        }
        return ApiResponse.ok(alertQueryService.findEvidencesByAlertId(namespace, id).stream()
                .map(EvidenceDto::from)
                .toList());
    }

    // ---------- ADR-0005 §4 disposition ----------

    @PostMapping("/{id}/ack")
    public ApiResponse<AlertDto> acknowledge(
            @PathVariable final Long id,
            @RequestParam final String namespace,
            @Valid @RequestBody(required = false) final DispositionRequest req) {
        AlertEntity alert = dispositionService.acknowledge(namespace, id, noteOf(req), byOf(req));
        return ApiResponse.ok(AlertDto.from(alert));
    }

    @PostMapping("/{id}/ignore")
    public ApiResponse<AlertDto> ignore(
            @PathVariable final Long id,
            @RequestParam final String namespace,
            @Valid @RequestBody(required = false) final DispositionRequest req) {
        AlertEntity alert = dispositionService.ignore(namespace, id, noteOf(req), byOf(req));
        return ApiResponse.ok(AlertDto.from(alert));
    }

    private static String noteOf(final DispositionRequest req) {
        return req == null ? null : req.note();
    }

    private static String byOf(final DispositionRequest req) {
        return req == null ? null : req.by();
    }

    /** ack/ignore 请求体（均可选）。 */
    public record DispositionRequest(String note, @NotBlank String by) {}
}
