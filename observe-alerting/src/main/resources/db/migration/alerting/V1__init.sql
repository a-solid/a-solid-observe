CREATE TABLE alerts (
    id VARCHAR(36) PRIMARY KEY,
    team VARCHAR NOT NULL,
    application VARCHAR NOT NULL,
    pipeline_labels LONG VARCHAR,
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version INT NOT NULL,
    execution_id VARCHAR NOT NULL,
    fingerprint VARCHAR(256) NOT NULL,
    severity VARCHAR NOT NULL,
    labels LONG VARCHAR NOT NULL,
    annotations LONG VARCHAR,

    starts_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,

    status VARCHAR NOT NULL,
    dedup_count INT NOT NULL DEFAULT 1,
    generator_url VARCHAR,
    trace_id VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT ck_alerts_status CHECK (status IN ('FIRING','RESOLVED')),
    CONSTRAINT ck_alerts_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))
);

CREATE INDEX idx_alerts_status_ends ON alerts(status, ends_at);
CREATE INDEX idx_alerts_team_time ON alerts(team, starts_at DESC);
CREATE INDEX idx_alerts_fingerprint ON alerts(fingerprint, status);
CREATE INDEX idx_alerts_status_time ON alerts(status, starts_at DESC);
CREATE INDEX idx_alerts_pipeline ON alerts(pipeline_id, starts_at DESC);
CREATE INDEX idx_alerts_sev_status ON alerts(severity, status, starts_at DESC);
CREATE INDEX idx_alerts_trace ON alerts(trace_id);
CREATE INDEX idx_alerts_exec ON alerts(execution_id);

CREATE TABLE alerts_evidence (
    alert_id VARCHAR(36) PRIMARY KEY,
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version INT NOT NULL,
    execution_id VARCHAR NOT NULL,
    node_name VARCHAR,

    outputs LONG VARCHAR,

    trace_id VARCHAR,
    span_id VARCHAR,
    captured_at TIMESTAMP NOT NULL,
    truncated BOOLEAN NOT NULL,
    size_bytes INT NOT NULL
);

CREATE INDEX idx_ev_pipeline ON alerts_evidence(pipeline_id, captured_at DESC);
CREATE INDEX idx_ev_exec ON alerts_evidence(execution_id);
