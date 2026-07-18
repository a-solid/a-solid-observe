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

/**
 * Executions/FailedExecutions 查询接口（ADR-0002 软隔离铁律）。
 *
 * <p>与 {@link AlertController} 同款：行以 snowflake BIGINT id 为物理主键，namespace 作必填查询参数
 * {@code ?namespace=}；单条按 {@code (namespace, id)} 软校验，不匹配返回 404。
 */
@RestController
@RequestMapping("/api/v1")
public class ExecutionController {

    private final ExecutionQueryService service;

    public ExecutionController(final ExecutionQueryService service) {
        this.service = service;
    }

    @GetMapping("/executions")
    public List<ExecutionDto> executions(
            @RequestParam final String namespace,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "limit", required = false, defaultValue = "100") final int limit) {
        return service.findExecutions(namespace, pipelineId, limit).stream()
                .map(ExecutionDto::from)
                .toList();
    }

    @GetMapping("/executions/{id}")
    public ResponseEntity<ExecutionDto> execution(@PathVariable final Long id, @RequestParam final String namespace) {
        return service.findExecution(namespace, id)
                .map(ExecutionDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/failed-executions")
    public List<FailedExecutionDto> failedExecutions(
            @RequestParam final String namespace,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "limit", required = false, defaultValue = "100") final int limit) {
        return service.findFailedExecutions(namespace, pipelineId, limit).stream()
                .map(FailedExecutionDto::from)
                .toList();
    }

    @GetMapping("/failed-executions/{id}")
    public ResponseEntity<FailedExecutionDto> failedExecution(
            @PathVariable final Long id, @RequestParam final String namespace) {
        return service.findFailedExecution(namespace, id)
                .map(FailedExecutionDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
