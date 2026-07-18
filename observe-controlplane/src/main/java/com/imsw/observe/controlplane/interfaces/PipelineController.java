package com.imsw.observe.controlplane.interfaces;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
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
import com.imsw.observe.kernel.util.JsonUtil;
import com.imsw.observe.pipeline.domain.Pipeline;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineCrudService crud;

    private final VersionPublishService versions;

    public PipelineController(final PipelineCrudService crud, final VersionPublishService versions) {
        this.crud = crud;
        this.versions = versions;
    }

    @PostMapping
    public PipelineDto create(@RequestBody final CreatePipelineRequest req) {
        PipelineDefinition def = crud.create(
                req.team(), req.application(), req.labels(), req.name(), req.description(), req.createdBy());
        return PipelineDto.from(def);
    }

    @GetMapping
    public List<PipelineDto> list() {
        return crud.findAll().stream().map(PipelineDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PipelineDto> get(@PathVariable final Long id) {
        PipelineDefinition def = crud.find(id);
        return def == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(PipelineDto.from(def));
    }

    @PutMapping("/{id}")
    public PipelineDto update(@PathVariable final Long id, @RequestBody final CreatePipelineRequest req) {
        return PipelineDto.from(
                crud.update(id, req.team(), req.application(), req.labels(), req.name(), req.description()));
    }

    @PostMapping("/{id}/archive")
    public void archive(@PathVariable final Long id) {
        crud.archive(id);
    }

    @PostMapping("/{id}/versions")
    public VersionDto saveVersion(@PathVariable final Long id, @RequestBody final SaveVersionRequest req) {
        Pipeline pipeline = JsonUtil.fromJson(req.pipelineJson(), Pipeline.class);
        return VersionDto.from(versions.saveDraft(pipeline, req.publishedBy()));
    }

    @GetMapping("/{id}/versions")
    public List<VersionDto> versions(@PathVariable final Long id) {
        return versions.versions(id).stream().map(VersionDto::from).toList();
    }

    @PostMapping("/{id}/versions/{v}/publish")
    public VersionDto publish(
            @PathVariable final Long id, @PathVariable final int v, @RequestBody final PublishRequest req) {
        return VersionDto.from(versions.publish(id, v, req.publishedBy()));
    }

    @PostMapping("/{id}/versions/{v}/archive")
    public VersionDto archiveVersion(@PathVariable final Long id, @PathVariable final int v) {
        return VersionDto.from(versions.archive(id, v));
    }

    public record CreatePipelineRequest(
            String team,
            String application,
            Map<String, String> labels,
            String name,
            String description,
            String createdBy) {}

    public record SaveVersionRequest(String pipelineJson, String publishedBy) {}

    public record PublishRequest(String publishedBy) {}
}
