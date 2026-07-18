package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;

import com.imsw.observe.config.domain.PipelineVersion;

public record VersionDto(
        String namespace,
        Long pipelineId,
        int version,
        String definitionHash,
        String status,
        String publishedBy,
        Instant createdAt,
        Instant publishedAt) {

    public static VersionDto from(final PipelineVersion v) {
        return new VersionDto(
                v.namespace(),
                v.pipelineId(),
                v.version(),
                v.definitionHash(),
                v.status() == null ? null : v.status().name(),
                v.publishedBy(),
                v.createdAt(),
                v.publishedAt());
    }
}
