package com.imsw.observe.controlplane.interfaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.controlplane.interfaces.dto.ExecutionDto;
import com.imsw.observe.controlplane.interfaces.dto.FailedExecutionDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.PageResponse;
import com.imsw.observe.controlplane.interfaces.web.Pages;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;
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
    public PageResponse<ExecutionDto> executions(
            @RequestParam final String namespace,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "page", required = false, defaultValue = "1") final int page,
            @RequestParam(name = "size", required = false) final Integer size,
            @RequestParam(name = "limit", required = false) final Integer limit) {
        return Pages.toResponse(
                service.findExecutions(namespace, pipelineId, Pages.pageable(page, size, limit)),
                ExecutionDto::from,
                page,
                size,
                limit);
    }

    @GetMapping("/executions/{id}")
    public ApiResponse<ExecutionDto> execution(@PathVariable final Long id, @RequestParam final String namespace) {
        return service.findExecution(namespace, id)
                .map(ExecutionDto::from)
                .map(ApiResponse::ok)
                .orElseThrow(() ->
                        new ResourceNotFoundException("execution " + id + " not found in namespace " + namespace));
    }

    @GetMapping("/failed-executions")
    public PageResponse<FailedExecutionDto> failedExecutions(
            @RequestParam final String namespace,
            @RequestParam(name = "pipeline_id", required = false) final Long pipelineId,
            @RequestParam(name = "page", required = false, defaultValue = "1") final int page,
            @RequestParam(name = "size", required = false) final Integer size,
            @RequestParam(name = "limit", required = false) final Integer limit) {
        return Pages.toResponse(
                service.findFailedExecutions(namespace, pipelineId, Pages.pageable(page, size, limit)),
                FailedExecutionDto::from,
                page,
                size,
                limit);
    }

    @GetMapping("/failed-executions/{id}")
    public ApiResponse<FailedExecutionDto> failedExecution(
            @PathVariable final Long id, @RequestParam final String namespace) {
        return service.findFailedExecution(namespace, id)
                .map(FailedExecutionDto::from)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "failed-execution " + id + " not found in namespace " + namespace));
    }
}
