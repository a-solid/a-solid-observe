package com.imsw.observe.controlplane.interfaces;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.alerting.application.AlertQueryService;
import com.imsw.observe.controlplane.interfaces.dto.AlertDto;
import com.imsw.observe.controlplane.interfaces.dto.EvidenceDto;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertQueryService alertQueryService;

    public AlertController(final AlertQueryService alertQueryService) {
        this.alertQueryService = alertQueryService;
    }

    @GetMapping
    public List<AlertDto> listAlerts(
            @RequestParam(name = "status", required = false) final String status,
            @RequestParam(name = "team", required = false) final String team,
            @RequestParam(name = "pipeline_id", required = false) final String pipelineId,
            @RequestParam(name = "limit", required = false, defaultValue = "100") final int limit) {
        return alertQueryService.findAlerts(status, team, pipelineId, limit).stream()
                .map(AlertDto::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlertDto> getAlert(@PathVariable final String id) {
        return alertQueryService
                .findById(id)
                .map(AlertDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/evidence")
    public ResponseEntity<EvidenceDto> getEvidence(@PathVariable final String id) {
        return alertQueryService
                .findEvidenceByAlertId(id)
                .map(EvidenceDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
