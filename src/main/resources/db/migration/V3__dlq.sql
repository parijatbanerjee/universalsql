-- V3: Dead Letter Queue for failed webhook events
CREATE TABLE IF NOT EXISTS dlq_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_id VARCHAR NOT NULL,
    connector_id VARCHAR NOT NULL,
    event_type VARCHAR NOT NULL,
    payload TEXT,
    error_message TEXT
);
