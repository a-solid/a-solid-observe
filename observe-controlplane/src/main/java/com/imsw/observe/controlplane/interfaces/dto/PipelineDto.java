package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.config.domain.PipelineDefinition;

public record PipelineDto(
        Long id,
        String team,
        String application,
        Map<String, String> labels,
        String name,
        String description,
        String status,
        Integer currentVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static PipelineDto from(final PipelineDefinition d) {
        return new PipelineDto(
                d.id(),
                d.team(),
                d.application(),
                d.labels(),
                d.name(),
                d.description(),
                d.status() == null ? null : d.status().name(),
                d.currentVersion(),
                d.createdBy(),
                d.createdAt(),
                d.updatedAt());
    }
}
