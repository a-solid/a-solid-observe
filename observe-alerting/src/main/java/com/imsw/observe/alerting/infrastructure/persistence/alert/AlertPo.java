package com.imsw.observe.alerting.infrastructure.persistence.alert;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.imsw.observe.kernel.util.MapStringStringToJsonConverter;

@Entity
@Table(name = "alerts")
public class AlertPo {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    public String id;

    @Column(name = "team", nullable = false)
    public String team;

    @Column(name = "application", nullable = false)
    public String application;

    @Column(name = "pipeline_labels", length = 16384)
    @Convert(converter = MapStringStringToJsonConverter.class)
    public Map<String, String> pipelineLabels;

    @Column(name = "pipeline_id", length = 64, nullable = false)
    public String pipelineId;

    @Column(name = "pipeline_version", nullable = false)
    public Integer pipelineVersion;

    @Column(name = "execution_id", nullable = false)
    public String executionId;

    @Column(name = "fingerprint", length = 256, nullable = false)
    public String fingerprint;

    @Column(name = "severity", nullable = false)
    public String severity;

    @Column(name = "labels", length = 16384, nullable = false)
    @Convert(converter = MapStringStringToJsonConverter.class)
    public Map<String, String> labels;

    @Column(name = "annotations", length = 16384)
    @Convert(converter = MapStringStringToJsonConverter.class)
    public Map<String, String> annotations;

    @Column(name = "starts_at", nullable = false)
    public Instant startsAt;

    @Column(name = "last_seen_at", nullable = false)
    public Instant lastSeenAt;

    @Column(name = "ends_at", nullable = false)
    public Instant endsAt;

    @Column(name = "resolved_at")
    public Instant resolvedAt;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "dedup_count", nullable = false)
    public Integer dedupCount = 1;

    @Column(name = "generator_url")
    public String generatorUrl;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
