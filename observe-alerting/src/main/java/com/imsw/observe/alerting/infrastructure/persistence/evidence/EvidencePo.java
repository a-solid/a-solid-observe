package com.imsw.observe.alerting.infrastructure.persistence.evidence;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.imsw.observe.kernel.util.MapStringObjectToJsonConverter;

@Entity
@Table(name = "alerts_evidence")
public class EvidencePo {

    @Id
    @Column(name = "alert_id", nullable = false)
    public Long alertId;

    @Column(name = "namespace", nullable = false)
    public String namespace;

    @Column(name = "pipeline_id", nullable = false)
    public Long pipelineId;

    @Column(name = "pipeline_version", nullable = false)
    public Integer pipelineVersion;

    @Column(name = "execution_id", nullable = false)
    public Long executionId;

    @Column(name = "node_name")
    public String nodeName;

    @Column(name = "outputs", length = 262144)
    @Convert(converter = MapStringObjectToJsonConverter.class)
    public Map<String, Object> outputs;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "span_id")
    public String spanId;

    @Column(name = "captured_at", nullable = false)
    public Instant capturedAt;

    @Column(name = "truncated", nullable = false)
    public Boolean truncated;

    @Column(name = "size_bytes", nullable = false)
    public Integer sizeBytes;
}
