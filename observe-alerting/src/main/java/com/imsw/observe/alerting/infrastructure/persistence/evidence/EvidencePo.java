package com.imsw.observe.alerting.infrastructure.persistence.evidence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 证据 PO（ADR-0005 §2：1:N）。PK 为 snowflake {@code id}，{@code alert_id} 降为普通引用列。
 * 内容载荷 {@code trigger_event} = 触发事件 JSON 快照（与 executions.trigger_event 对齐）。
 */
@Entity
@Table(name = "alerts_evidence")
public class EvidencePo {

    @Id
    @Column(name = "id", nullable = false)
    public Long id;

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

    @Column(name = "trigger_event", length = 1_048_576)
    public String triggerEvent;

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

    // ADR-0005 §2：该 alert 内的证据序号
    @Column(name = "emit_seq", nullable = false)
    public Integer emitSeq;
}
