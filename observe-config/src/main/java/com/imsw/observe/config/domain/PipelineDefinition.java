package com.imsw.observe.config.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Pipeline 定义（B9 / ADR-0004）：team/application 一等字段移除，维度统一进 {@code labels}。
 */
public record PipelineDefinition(
        Long id,
        String namespace,
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
