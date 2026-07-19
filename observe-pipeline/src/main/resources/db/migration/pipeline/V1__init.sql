-- 执行记录（合表后单表，原 executions + failed_executions 合一）。
-- status 表结果：SUCCESS / SHORT_CIRCUITED / FAILED。失败专属列（execution_id/node_name/
-- error_type/error_message/stack_trace）仅 FAILED 行填，其余 null。砍原 failed_executions 的
-- triage（PENDING/RESOLVED/IGNORED）——从未实现。一 pipeline 一行（node 级详情走 evidence）。
CREATE TABLE executions (
    id BIGINT PRIMARY KEY,
    namespace VARCHAR NOT NULL,
    pipeline_id BIGINT NOT NULL,
    pipeline_version INT NOT NULL,
    trigger_type VARCHAR NOT NULL,
    trigger_event LONG VARCHAR,
    subscription_id BIGINT,

    status VARCHAR NOT NULL,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP NOT NULL,
    duration_ms BIGINT,
    trace_id VARCHAR,

    created_at TIMESTAMP NOT NULL,

    -- 失败专属（仅 status=FAILED 行填，合表自 failed_executions）
    execution_id BIGINT,
    node_name VARCHAR,
    error_type VARCHAR,
    error_message TEXT,
    stack_trace LONG VARCHAR,

    CONSTRAINT ck_exec_status CHECK (status IN ('SUCCESS','SHORT_CIRCUITED','FAILED')),
    CONSTRAINT ck_exec_error_type CHECK (error_type IN (
        'SCRIPT_COMPILATION','SCRIPT_SANDBOX','SCRIPT_TIMEOUT',
        'SCRIPT_EXECUTION','NODE_EXECUTION','PIPELINE_TIMEOUT',
        'GRACEFUL_SHUTDOWN_KILL','UNKNOWN'
    ))
);

CREATE INDEX idx_exec_ns_started ON executions(namespace, started_at DESC);
CREATE INDEX idx_exec_pipeline ON executions(pipeline_id, started_at DESC);
CREATE INDEX idx_exec_status ON executions(status, started_at DESC);
CREATE INDEX idx_exec_trace ON executions(trace_id);
CREATE INDEX idx_exec_sub ON executions(subscription_id, started_at DESC);
CREATE INDEX idx_exec_trigger ON executions(trigger_type, started_at DESC);
CREATE INDEX idx_exec_error_type ON executions(error_type, started_at DESC);
