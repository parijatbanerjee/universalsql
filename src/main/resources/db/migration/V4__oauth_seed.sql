-- V4: Seed oauth_connection rows for prototype testing
-- wrapped_token stores the plaintext token as UTF-8 bytes (no real encryption for seed data)
-- wrapped_dek is empty (zero bytes) → OAuthTokenService treats this as "plaintext mode"
-- In production both would be envelope-encrypted under the tenant's DEK.

INSERT INTO tenant (tenant_id, name, deployment_mode, residency_tag, kek_id, status)
VALUES ('beta', 'Beta Corp', 'CLOUD', 'us-east-1', 'beta-kek-1', 'active')
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO tenant_config (tenant_id, key, value) VALUES
    ('beta', 'max_query_rows', '10000'),
    ('beta', 'rate_limit_rpm', '120'),
    ('beta', 'allowed_connectors', 'jira,github')
ON CONFLICT (tenant_id, key) DO NOTHING;

INSERT INTO principal_closure (tenant_id, user_id, principal_id) VALUES
    ('beta', 'carol', 'project:PLAT')
ON CONFLICT (tenant_id, user_id, principal_id) DO NOTHING;

INSERT INTO oauth_connection (tenant_id, user_id, connector_id, connection_ref, wrapped_token, wrapped_dek, expires_at, status)
VALUES
    ('acme', 'alice', 'jira',   'alice-jira-conn',   'alice'::bytea, ''::bytea, NOW() + INTERVAL '1 hour', 'active'),
    ('acme', 'alice', 'github', 'alice-github-conn',  'alice'::bytea, ''::bytea, NOW() + INTERVAL '1 hour', 'active'),
    ('acme', 'bob',   'jira',   'bob-jira-conn',      'bob'::bytea,   ''::bytea, NOW() + INTERVAL '1 hour', 'active'),
    ('beta', 'carol', 'jira',   'carol-jira-conn',    'carol'::bytea, ''::bytea, NOW() + INTERVAL '1 hour', 'active')
ON CONFLICT (connection_ref) DO NOTHING;
