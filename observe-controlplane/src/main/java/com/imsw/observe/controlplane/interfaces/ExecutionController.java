package com.imsw.observe.controlplane.interfaces;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.controlplane.interfaces.dto.ExecutionDto;
import com.imsw.observe.controlplane.interfaces.dto.FailedExecutionDto;
import com.imsw.observe.pipeline.application.ExecutionQueryService;

@RestController
@RequestMapping("/api/v1")
public class ExecutionController {

    private final ExecutionQueryService service;

    public ExecutionController(final ExecutionQueryService service) {
        this.service = service;
    }

    @GetMapping("/executions")
    public List<ExecutionDto> executions(
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "limit", required = false, defaultValue = "100") final int limit) {
        return service.findExecutions(pipelineId, limit).stream()
                .map(ExecutionDto::from)
                .toList();
    }

    @GetMapping("/executions/{id}")
    public ResponseEntity<ExecutionDto> execution(@PathVariable final Long id) {
        return service.findExecution(id)
                .map(ExecutionDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/failed-executions")
    public List<FailedExecutionDto> failedExecutions(
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "limit", required = false, defaultValue = "100") final int limit) {
        return service.findFailedExecutions(pipelineId, limit).stream()
                .map(FailedExecutionDto::from)
                .toList();
    }

    @GetMapping("/failed-executions/{id}")
    public ResponseEntity<FailedExecutionDto> failedExecution(@PathVariable final Long id) {
        return service.findFailedExecution(id)
                .map(FailedExecutionDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
