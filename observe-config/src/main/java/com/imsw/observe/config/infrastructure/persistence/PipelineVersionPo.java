package com.imsw.observe.config.infrastructure.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_versions")
@IdClass(PipelineVersionPk.class)
public class PipelineVersionPo {

    @Id
    @Column(name = "pipeline_id", nullable = false)
    public Long pipelineId;

    @Id
    @Column(name = "version", nullable = false)
    public Integer version;

    @Column(name = "definition_json", length = 65536, nullable = false)
    public String definitionJson;

    @Column(name = "definition_hash", length = 64, nullable = false)
    public String definitionHash;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "published_by")
    public String publishedBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "published_at")
    public Instant publishedAt;
}
