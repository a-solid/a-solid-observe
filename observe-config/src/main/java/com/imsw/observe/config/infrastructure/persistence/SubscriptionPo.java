package com.imsw.observe.config.infrastructure.persistence;

import java.time.Instant;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "subscriptions")
public class SubscriptionPo {

    @Id
    @Column(name = "id", nullable = false)
    public Long id;

    @Column(name = "pipeline_id", nullable = false)
    public Long pipelineId;

    @Column(name = "pipeline_version", nullable = false)
    public Integer pipelineVersion;

    @Column(name = "mq")
    public String mq;

    @Column(name = "topic")
    public String topic;

    @Column(name = "db")
    public String db;

    @Column(name = "table_name")
    public String tableName;

    @Column(name = "op_types", length = 256)
    @Convert(converter = StringSetToCsvConverter.class)
    public Set<String> opTypes;

    @Column(name = "source_type")
    public String sourceType;

    @Column(name = "field_filter", length = 16384)
    public String fieldFilter;

    @Column(name = "action_type", nullable = false)
    public String actionType = "RUN";

    @Column(name = "schedule_delay_ms")
    public Long scheduleDelayMs;

    @Column(name = "schedule_correlation_key_path")
    public String scheduleCorrelationKeyPath;

    @Column(name = "name")
    public String name;

    @Column(name = "description")
    public String description;

    @Column(name = "status", nullable = false)
    public String status = "ACTIVE";

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
