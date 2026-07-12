package com.imsw.observe.config.domain;

import java.time.Instant;
import java.util.Map;

public record PipelineDefinition(
        String id,
        String team,
        String application,
        Map<String, String> labels,
        String name,
        String description,
        Status status,
        Integer currentVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        DRAFT,
        PUBLISHED,
        ARCHIVED
    }
}
