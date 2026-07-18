package com.imsw.observe.pipeline.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Pipeline(
        Long id,
        int version,
        String team,
        String application,
        Map<String, String> labels,
        String name,
        Status status,
        List<NodeSpec> nodes,
        Instant createdAt,
        Instant publishedAt,
        double executionLogSampleRatio) {

    public enum Status {
        DRAFT,
        PUBLISHED,
        ARCHIVED
    }
}
