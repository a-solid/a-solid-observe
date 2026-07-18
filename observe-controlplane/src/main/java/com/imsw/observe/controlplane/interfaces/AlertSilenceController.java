package com.imsw.observe.controlplane.interfaces;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.alerting.application.AlertSilenceService;
import com.imsw.observe.alerting.domain.AlertSilenceEntity;
import com.imsw.observe.alerting.domain.AlertSilenceMatchType;
import com.imsw.observe.controlplane.interfaces.dto.SilenceDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;

/**
 * Silence CRUD 接口（ADR-0005 §3，ADR-0002 软隔离：{@code ?namespace=} 必填）。
 */
@RestController
@RequestMapping("/api/v1/alert-silences")
public class AlertSilenceController {

    private final AlertSilenceService service;

    public AlertSilenceController(final AlertSilenceService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<SilenceDto> create(
            @RequestParam final String namespace, @Valid @RequestBody final CreateSilenceRequest req) {
        AlertSilenceEntity draft = new AlertSilenceEntity(
                null,
                namespace,
                AlertSilenceMatchType.valueOf(req.matchType().toUpperCase()),
                req.match() == null ? Map.of() : req.match(),
                req.startsAt(),
                req.endsAt(),
                req.note(),
                req.createdBy(),
                null,
                null);
        return ApiResponse.ok(SilenceDto.from(service.create(draft)));
    }

    @GetMapping
    public ApiResponse<List<SilenceDto>> list(@RequestParam final String namespace) {
        return ApiResponse.ok(
                service.findAll(namespace).stream().map(SilenceDto::from).toList());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable final Long id, @RequestParam final String namespace) {
        service.delete(namespace, id);
        return ApiResponse.ok(null);
    }

    public record CreateSilenceRequest(
            @NotNull String matchType,
            Map<String, Object> match,
            java.time.Instant startsAt,
            java.time.Instant endsAt,
            String note,
            String createdBy) {}
}
