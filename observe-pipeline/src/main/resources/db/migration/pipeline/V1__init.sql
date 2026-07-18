CREATE TABLE executions (
    id BIGINT PRIMARY KEY,
    pipeline_id BIGINT NOT NULL,
    pipeline_version INT NOT NULL,
    team VARCHAR NOT NULL,
    application VARCHAR NOT NULL,
    trigger_type VARCHAR NOT NULL,
    trigger_event LONG VARCHAR,
    subscription_id BIGINT,

    status VARCHAR NOT NULL,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP NOT NULL,
    duration_ms BIGINT,
    trace_id VARCHAR,

    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_exec_status CHECK (status IN ('SUCCESS','SHORT_CIRCUITED'))
);

CREATE INDEX idx_exec_pipeline ON executions(pipeline_id, started_at DESC);
CREATE INDEX idx_exec_team ON executions(team, started_at DESC);
CREATE INDEX idx_exec_status ON executions(status, started_at DESC);
CREATE INDEX idx_exec_trace ON executions(trace_id);
CREATE INDEX idx_exec_sub ON executions(subscription_id, started_at DESC);
CREATE INDEX idx_exec_trigger ON executions(trigger_type, started_at DESC);

CREATE TABLE failed_executions (
    id BIGINT PRIMARY KEY,
    pipeline_id BIGINT NOT NULL,
    pipeline_version INT NOT NULL,
    execution_id BIGINT,
    team VARCHAR NOT NULL,
    application VARCHAR NOT NULL,
    trigger_type VARCHAR NOT NULL,
    trigger_event LONG VARCHAR,
    subscription_id BIGINT,

    node_name VARCHAR,
    error_type VARCHAR,
    error_message TEXT,
    stack_trace LONG VARCHAR,

    status VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,

    CONSTRAINT ck_fe_status CHECK (status IN ('PENDING','RESOLVED','IGNORED')),
    CONSTRAINT ck_fe_error_type CHECK (error_type IN (
        'SCRIPT_COMPILATION','SCRIPT_SANDBOX','SCRIPT_TIMEOUT',
        'SCRIPT_EXECUTION','NODE_EXECUTION','PIPELINE_TIMEOUT',
        'GRACEFUL_SHUTDOWN_KILL','UNKNOWN'
    )),
    CONSTRAINT ck_fe_resolved CHECK (
        (status = 'PENDING' AND resolved_at IS NULL) OR
        (status IN ('RESOLVED','IGNORED') AND resolved_at IS NOT NULL)
    )
);

CREATE INDEX idx_fe_status ON failed_executions(status, created_at DESC);
CREATE INDEX idx_fe_execution ON failed_executions(execution_id);
CREATE INDEX idx_fe_pipeline ON failed_executions(pipeline_id, created_at DESC);
CREATE INDEX idx_fe_team ON failed_executions(team, created_at DESC);
CREATE INDEX idx_fe_trigger ON failed_executions(trigger_type, created_at DESC);
CREATE INDEX idx_fe_error_type ON failed_executions(error_type, created_at DESC);
CREATE INDEX idx_fe_sub ON failed_executions(subscription_id, created_at DESC);
