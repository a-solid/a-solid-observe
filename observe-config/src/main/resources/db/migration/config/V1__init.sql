CREATE TABLE namespaces (
    id BIGINT PRIMARY KEY,
    name VARCHAR NOT NULL,
    display_name VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_namespaces_name ON namespaces(name);

CREATE TABLE pipelines (
    id BIGINT PRIMARY KEY,
    namespace VARCHAR NOT NULL,
    team VARCHAR NOT NULL,
    application VARCHAR NOT NULL,
    labels VARCHAR(16384),
    name VARCHAR NOT NULL,
    description VARCHAR,
    status VARCHAR NOT NULL,
    current_version INT,
    created_by VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_pipelines_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);

CREATE UNIQUE INDEX idx_pipelines_ns_name ON pipelines(namespace, name);
CREATE INDEX idx_pipelines_team_app ON pipelines(team, application);
CREATE INDEX idx_pipelines_status_updated ON pipelines(status, updated_at);

CREATE TABLE pipeline_versions (
    pipeline_id BIGINT NOT NULL,
    version INT NOT NULL,
    namespace VARCHAR NOT NULL,
    definition_json LONG VARCHAR NOT NULL,
    definition_hash VARCHAR(64) NOT NULL,
    status VARCHAR NOT NULL,
    published_by VARCHAR,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    PRIMARY KEY (pipeline_id, version),
    CONSTRAINT ck_pv_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);

CREATE INDEX idx_pv_status_published ON pipeline_versions(status, published_at DESC);

CREATE TABLE subscriptions (
    id BIGINT PRIMARY KEY,
    namespace VARCHAR NOT NULL,
    pipeline_id BIGINT NOT NULL,
    pipeline_version INT NOT NULL,

    mq VARCHAR,
    topic VARCHAR,
    db VARCHAR,
    table_name VARCHAR,
    op_types VARCHAR(256),
    source_type VARCHAR,

    field_filter LONG VARCHAR,

    action_type VARCHAR NOT NULL DEFAULT 'RUN',
    schedule_delay_ms BIGINT,
    schedule_correlation_key_path VARCHAR,

    name VARCHAR,
    description VARCHAR,
    status VARCHAR NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT ck_sub_action_type CHECK (action_type IN ('RUN','SCHEDULE','CANCEL')),
    CONSTRAINT ck_sub_schedule CHECK (
        (action_type = 'SCHEDULE' AND schedule_delay_ms IS NOT NULL
         AND schedule_correlation_key_path IS NOT NULL)
        OR (action_type = 'CANCEL' AND schedule_correlation_key_path IS NOT NULL)
        OR (action_type = 'RUN' AND schedule_delay_ms IS NULL
            AND schedule_correlation_key_path IS NULL)
    ),
    CONSTRAINT ck_sub_status CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE UNIQUE INDEX idx_sub_ns_name ON subscriptions(namespace, name);
CREATE INDEX idx_sub_source ON subscriptions(db, table_name, status);

