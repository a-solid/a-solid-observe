package com.imsw.observe.config.domain;

import java.time.Instant;

public record PipelineVersion(
        Long pipelineId,
        int version,
        String definitionJson,
        String definitionHash,
        Status status,
        String publishedBy,
        Instant createdAt,
        Instant publishedAt) {

    public enum Status {
        DRAFT,
        PUBLISHED,
        ARCHIVED
    }
}
