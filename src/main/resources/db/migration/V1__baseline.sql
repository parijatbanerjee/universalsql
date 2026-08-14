-- Baseline migration: schema creation for Universal SQL layer
-- See spec §4.1 for full schema definition

CREATE TABLE IF NOT EXISTS tenant (
    tenant_id     VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    deployment_mode VARCHAR(32) NOT NULL,
    residency_tag VARCHAR(64),
    kek_id        VARCHAR(128),
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tenant_config (
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenant(tenant_id),
    key       VARCHAR(128) NOT NULL,
    value     TEXT,
    PRIMARY KEY (tenant_id, key)
);

CREATE TABLE IF NOT EXISTS source_catalog (
    connector_id   VARCHAR(64) PRIMARY KEY,
    version        INTEGER     NOT NULL DEFAULT 1,
    table_name     VARCHAR(128) NOT NULL,
    column_json    JSONB,
    capability_json JSONB
);

CREATE TABLE IF NOT EXISTS adapter_registry (
    connector_id  VARCHAR(64) NOT NULL,
    version       INTEGER     NOT NULL,
    status        VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    promoted_at   TIMESTAMPTZ,
    PRIMARY KEY (connector_id, version)
);

CREATE TABLE IF NOT EXISTS principal_closure (
    tenant_id    VARCHAR(64) NOT NULL,
    user_id      VARCHAR(128) NOT NULL,
    principal_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, principal_id)
);

CREATE TABLE IF NOT EXISTS resource_acl (
    tenant_id     VARCHAR(64)  NOT NULL,
    source        VARCHAR(64)  NOT NULL,
    resource_id   VARCHAR(256) NOT NULL,
    principal_id  VARCHAR(128) NOT NULL,
    acl_version   BIGINT       NOT NULL DEFAULT 0,
    acl_synced_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source, resource_id, principal_id)
);

CREATE TABLE IF NOT EXISTS oauth_connection (
    tenant_id      VARCHAR(64)  NOT NULL,
    user_id        VARCHAR(128) NOT NULL,
    connector_id   VARCHAR(64)  NOT NULL,
    connection_ref VARCHAR(128) PRIMARY KEY,
    wrapped_token  BYTEA,
    wrapped_dek    BYTEA,
    expires_at     TIMESTAMPTZ,
    status         VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS policy (
    tenant_id  VARCHAR(64)  NOT NULL,
    table_name VARCHAR(128) NOT NULL,
    rls_expr   TEXT,
    cls_json   JSONB,
    version    INTEGER      NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, table_name)
);

CREATE TABLE IF NOT EXISTS audit_event (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ts           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    trace_id     VARCHAR(64),
    tenant_id    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(128),
    connector_id VARCHAR(64),
    action       VARCHAR(64)  NOT NULL,
    resource_ids TEXT[],
    decision     VARCHAR(32)  NOT NULL,
    reason       TEXT,
    sql_hash     VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS job_state (
    job_id       VARCHAR(128) PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    connector_id VARCHAR(64)  NOT NULL,
    kind         VARCHAR(64)  NOT NULL,
    watermark    TEXT,
    last_run_at  TIMESTAMPTZ,
    status       VARCHAR(32)  NOT NULL DEFAULT 'IDLE'
);

CREATE TABLE IF NOT EXISTS query_stats (
    tenant_id  VARCHAR(64)  NOT NULL,
    table_name VARCHAR(128) NOT NULL,
    p50_ms     BIGINT,
    p95_ms     BIGINT,
    est_rows   BIGINT,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, table_name)
);
