package com.imsw.observe.controlplane.interfaces;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.config.application.PipelineCrudService;
import com.imsw.observe.config.application.VersionPublishService;
import com.imsw.observe.config.domain.PipelineDefinition;
import com.imsw.observe.controlplane.interfaces.dto.PipelineDto;
import com.imsw.observe.controlplane.interfaces.dto.VersionDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;
import com.imsw.observe.kernel.util.JsonUtil;
import com.imsw.observe.pipeline.domain.Pipeline;

/**
 * Pipeline 资源以业务键 {@code (namespace, name)} 寻址（ADR-0002 软隔离铁律）。
 *
 * <p>路径形态：{@code /api/v1/namespaces/{namespace}/pipelines/...}。BIGINT id 不对外暴露。
 */
@RestController
@RequestMapping("/api/v1")
public class PipelineController {

    private final PipelineCrudService crud;

    private final VersionPublishService versions;

    public PipelineController(final PipelineCrudService crud, final VersionPublishService versions) {
        this.crud = crud;
        this.versions = versions;
    }

    @PostMapping("/namespaces/{namespace}/pipelines")
    public ApiResponse<PipelineDto> create(
            @PathVariable final String namespace, @Valid @RequestBody final CreatePipelineRequest req) {
        PipelineDefinition def = crud.create(namespace, req.name(), req.labels(), req.description(), req.createdBy());
        return ApiResponse.ok(PipelineDto.from(def));
    }

    @GetMapping("/namespaces/{namespace}/pipelines")
    public ApiResponse<List<PipelineDto>> list(@PathVariable final String namespace) {
        return ApiResponse.ok(
                crud.findAll(namespace).stream().map(PipelineDto::from).toList());
    }

    @GetMapping("/namespaces/{namespace}/pipelines/{name}")
    public ApiResponse<PipelineDto> get(@PathVariable final String namespace, @PathVariable final String name) {
        PipelineDefinition def = crud.find(namespace, name);
        if (def == null) {
            throw new ResourceNotFoundException("pipeline " + namespace + "/" + name + " not found");
        }
        return ApiResponse.ok(PipelineDto.from(def));
    }

    @PutMapping("/namespaces/{namespace}/pipelines/{name}")
    public ApiResponse<PipelineDto> update(
            @PathVariable final String namespace,
            @PathVariable final String name,
            @Valid @RequestBody final CreatePipelineRequest req) {
        return ApiResponse.ok(PipelineDto.from(crud.update(namespace, name, req.labels(), req.description())));
    }

    @PostMapping("/namespaces/{namespace}/pipelines/{name}/archive")
    public ApiResponse<Void> archive(@PathVariable final String namespace, @PathVariable final String name) {
        crud.archive(namespace, name);
        return ApiResponse.ok(null);
    }

    @PostMapping("/namespaces/{namespace}/pipelines/{name}/versions")
    public ApiResponse<VersionDto> saveVersion(
            @PathVariable final String namespace,
            @PathVariable final String name,
            @Valid @RequestBody final SaveVersionRequest req) {
        Pipeline pipeline = JsonUtil.fromJson(req.pipelineJson(), Pipeline.class);
        return ApiResponse.ok(VersionDto.from(versions.saveDraft(namespace, name, pipeline, req.publishedBy())));
    }

    @GetMapping("/namespaces/{namespace}/pipelines/{name}/versions")
    public ApiResponse<List<VersionDto>> versions(
            @PathVariable final String namespace, @PathVariable final String name) {
        return ApiResponse.ok(versions.versions(namespace, name).stream()
                .map(VersionDto::from)
                .toList());
    }

    @PostMapping("/namespaces/{namespace}/pipelines/{name}/versions/{v}/publish")
    public ApiResponse<VersionDto> publish(
            @PathVariable final String namespace,
            @PathVariable final String name,
            @PathVariable final int v,
            @RequestBody final PublishRequest req) {
        return ApiResponse.ok(VersionDto.from(versions.publish(namespace, name, v, req.publishedBy())));
    }

    @PostMapping("/namespaces/{namespace}/pipelines/{name}/versions/{v}/archive")
    public ApiResponse<VersionDto> archiveVersion(
            @PathVariable final String namespace, @PathVariable final String name, @PathVariable final int v) {
        return ApiResponse.ok(VersionDto.from(versions.archive(namespace, name, v)));
    }

    public record CreatePipelineRequest(
            Map<String, String> labels, @NotBlank String name, String description, String createdBy) {}

    public record SaveVersionRequest(@NotBlank String pipelineJson, String publishedBy) {}

    public record PublishRequest(String publishedBy) {}
}
