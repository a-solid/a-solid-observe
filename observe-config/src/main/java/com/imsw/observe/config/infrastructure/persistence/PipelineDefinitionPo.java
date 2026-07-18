package com.imsw.observe.config.infrastructure.persistence;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.imsw.observe.kernel.util.MapStringStringToJsonConverter;

@Entity
@Table(name = "pipelines")
public class PipelineDefinitionPo {

    @Id
    @Column(name = "id", nullable = false)
    public Long id;

    @Column(name = "team", nullable = false)
    public String team;

    @Column(name = "application", nullable = false)
    public String application;

    @Column(name = "labels", length = 16384)
    @Convert(converter = MapStringStringToJsonConverter.class)
    public Map<String, String> labels;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "description", length = 4000)
    public String description;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "current_version")
    public Integer currentVersion;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
