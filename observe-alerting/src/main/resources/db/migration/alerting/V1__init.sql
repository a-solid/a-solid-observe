-- observe-alerting 完整初始化（B9 / ADR-0004 + ADR-0005 合并落地的单一定义）。
-- 生产参考（alerting 库无 Flyway，schema 由 ddl-auto 驱动；上线前真实库冒烟）。
-- H2 测试库由 @Entity 自动建表，本脚本不影响 H2。

CREATE TABLE alerts (
    id BIGINT PRIMARY KEY,
    namespace VARCHAR NOT NULL,
    pipeline_id BIGINT NOT NULL,
    pipeline_version INT NOT NULL,
    execution_id BIGINT NOT NULL,
    fingerprint VARCHAR(256) NOT NULL,
    severity VARCHAR NOT NULL,
    labels LONG VARCHAR NOT NULL,
    annotations LONG VARCHAR,

    -- ADR-0005 波次/处置
    starts_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,

    -- ADR-0005 两维分离：status=系统态（ACTIVE/EXPIRED），disposition=用户处置（NONE/ACKNOWLEDGED/IGNORED），
    -- 二者正交。EXPIRED=波次 TTL 到点（非业界"条件恢复"）；用户处置不再混入 status。
    status VARCHAR NOT NULL,
    disposition VARCHAR NOT NULL DEFAULT 'NONE',
    dedup_count INT NOT NULL DEFAULT 1,

    -- ADR-0005 disposition（ack/ignore，用户介入处置时落库）
    ack_note VARCHAR,
    ack_by VARCHAR,
    ack_at TIMESTAMP,

    -- ADR-0004 label 投影列（从 labels 中的 team/app/line 投影，应用层 emit 时同步填充，缺失为 null）
    label_team VARCHAR,
    label_app VARCHAR,
    label_line VARCHAR,

    generator_url VARCHAR,
    trace_id VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT ck_alerts_status CHECK (status IN ('ACTIVE','EXPIRED')),
    CONSTRAINT ck_alerts_disposition CHECK (disposition IN ('NONE','ACKNOWLEDGED','IGNORED')),
    CONSTRAINT ck_alerts_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))
);

CREATE INDEX idx_alerts_ns_status ON alerts(namespace, status, starts_at DESC);
CREATE INDEX idx_alerts_status_ends ON alerts(status, ends_at);
CREATE INDEX idx_alerts_label_team ON alerts(label_team);
CREATE INDEX idx_alerts_label_app ON alerts(label_app);
CREATE INDEX idx_alerts_fingerprint ON alerts(fingerprint, status);
CREATE INDEX idx_alerts_status_time ON alerts(status, starts_at DESC);
CREATE INDEX idx_alerts_pipeline ON alerts(pipeline_id, starts_at DESC);
CREATE INDEX idx_alerts_sev_status ON alerts(severity, status, starts_at DESC);
CREATE INDEX idx_alerts_trace ON alerts(trace_id);
CREATE INDEX idx_alerts_exec ON alerts(execution_id);

-- ADR-0005 §2：1:N evidence。PK 改为 snowflake id，alert_id 降为普通引用列；
-- emit_seq 为该 alert 内证据序号；(alert_id, captured_at) 支撑按告警回溯证据演变。
CREATE TABLE alerts_evidence (
    id BIGINT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    namespace VARCHAR NOT NULL,
    pipeline_id BIGINT NOT NULL,
    pipeline_version INT NOT NULL,
    execution_id BIGINT NOT NULL,
    node_name VARCHAR,

    outputs LONG VARCHAR,

    trace_id VARCHAR,
    span_id VARCHAR,
    captured_at TIMESTAMP NOT NULL,
    truncated BOOLEAN NOT NULL,
    size_bytes INT NOT NULL,
    emit_seq INT NOT NULL
);

CREATE INDEX idx_ev_alert_captured ON alerts_evidence(alert_id, captured_at DESC);
CREATE INDEX idx_ev_ns_captured ON alerts_evidence(namespace, captured_at DESC);
CREATE INDEX idx_ev_pipeline ON alerts_evidence(pipeline_id, captured_at DESC);
CREATE INDEX idx_ev_exec ON alerts_evidence(execution_id);

-- ADR-0005 §3：silence 规则表。按 fingerprint / label 维度 / namespace+pipeline 维度匹配，
-- 有效期 + 备注 + 操作人；AlertSink emit 前查询命中即静默拦截。
CREATE TABLE alert_silences (
    id BIGINT PRIMARY KEY,
    namespace VARCHAR NOT NULL,
    match_type VARCHAR NOT NULL,
    match LONG VARCHAR NOT NULL,

    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,

    note VARCHAR,
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT ck_silences_match_type CHECK (match_type IN ('FINGERPRINT','LABELS','PIPELINE'))
);
