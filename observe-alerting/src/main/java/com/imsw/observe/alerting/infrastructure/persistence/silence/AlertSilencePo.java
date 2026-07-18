package com.imsw.observe.alerting.infrastructure.persistence.silence;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.imsw.observe.kernel.util.MapStringObjectToJsonConverter;

@Entity
@Table(name = "alert_silences")
public class AlertSilencePo {

    @Id
    @Column(name = "id", nullable = false)
    public Long id;

    @Column(name = "namespace", nullable = false)
    public String namespace;

    @Column(name = "match_type", nullable = false)
    public String matchType;

    @Column(name = "match", length = 16384, nullable = false)
    @Convert(converter = MapStringObjectToJsonConverter.class)
    public Map<String, Object> match;

    @Column(name = "starts_at", nullable = false)
    public Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    public Instant endsAt;

    @Column(name = "note")
    public String note;

    @Column(name = "created_by", nullable = false)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
