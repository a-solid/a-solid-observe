package com.imsw.observe.pipeline.infrastructure.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "failed_executions")
public class FailedExecutionPo {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    public String id;

    @Column(name = "pipeline_id", length = 64, nullable = false)
    public String pipelineId;

    @Column(name = "pipeline_version", nullable = false)
    public Integer pipelineVersion;

    @Column(name = "execution_id")
    public String executionId;

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

    @Column(name = "node_name")
    public String nodeName;

    @Column(name = "error_type")
    public String errorType;

    @Column(name = "error_message", length = 4000)
    public String errorMessage;

    @Column(name = "stack_trace", length = 32_768)
    public String stackTrace;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "resolved_at")
    public Instant resolvedAt;
}
