package com.imsw.observe.pipeline.infrastructure.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "executions")
public class ExecutionPo {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    public String id;

    @Column(name = "pipeline_id", length = 64, nullable = false)
    public String pipelineId;

    @Column(name = "pipeline_version", nullable = false)
    public Integer pipelineVersion;

    @Column(name = "team", nullable = false)
    public String team;

    @Column(name = "application", nullable = false)
    public String application;

    @Column(name = "trigger_type", nullable = false)
    public String triggerType;

    @Column(name = "trigger_event", length = 1_048_576)
    public String triggerEvent;

    @Column(name = "subscription_id")
    public String subscriptionId;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    public Instant endedAt;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
