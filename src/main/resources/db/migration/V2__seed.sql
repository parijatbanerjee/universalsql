-- Seed data for demo tenant 'acme' and related records
-- See spec §4.1 for schema definition

-- Demo tenant
INSERT INTO tenant (tenant_id, name, deployment_mode, residency_tag, kek_id, status)
VALUES ('acme', 'Acme Corp', 'CLOUD', 'us-east-1', 'acme-kek-1', 'active')
ON CONFLICT (tenant_id) DO NOTHING;

-- Tenant config entries
INSERT INTO tenant_config (tenant_id, key, value) VALUES
    ('acme', 'max_query_rows', '10000'),
    ('acme', 'rate_limit_rpm', '120'),
    ('acme', 'allowed_connectors', 'jira,github')
ON CONFLICT (tenant_id, key) DO NOTHING;

-- Principal closures for alice: can see projects PLAT and CORE
-- user_id=alice, principal_id encodes the project membership
INSERT INTO principal_closure (tenant_id, user_id, principal_id) VALUES
    ('acme', 'alice', 'project:PLAT'),
    ('acme', 'alice', 'project:CORE')
ON CONFLICT (tenant_id, user_id, principal_id) DO NOTHING;

-- Principal closures for bob: can only see project CORE
INSERT INTO principal_closure (tenant_id, user_id, principal_id) VALUES
    ('acme', 'bob', 'project:CORE')
ON CONFLICT (tenant_id, user_id, principal_id) DO NOTHING;

-- Source catalog entries for jira and github connectors
INSERT INTO source_catalog (connector_id, version, table_name, column_json, capability_json) VALUES
    ('jira', 1, 'jira_issues',
     '["id","project_key","summary","status","reporter_email","assignee","created_at","updated_at"]',
     '{"filter":true,"sort":true,"pagination":true,"rls":true,"cls":true}')
ON CONFLICT (connector_id) DO NOTHING;

INSERT INTO source_catalog (connector_id, version, table_name, column_json, capability_json) VALUES
    ('github', 1, 'github_prs',
     '["id","repo","title","state","author","created_at","merged_at"]',
     '{"filter":true,"sort":true,"pagination":true,"rls":false,"cls":false}')
ON CONFLICT (connector_id) DO NOTHING;

-- Adapter registry: mark both connectors as active/promoted
INSERT INTO adapter_registry (connector_id, version, status, promoted_at) VALUES
    ('jira',   1, 'ACTIVE', now()),
    ('github', 1, 'ACTIVE', now())
ON CONFLICT (connector_id, version) DO NOTHING;

-- Demo policy: jira_issues with RLS and CLS
-- RLS: project_key must be in the user's allowed projects
-- CLS: reporter_email is masked for non-admin principals
INSERT INTO policy (tenant_id, table_name, rls_expr, cls_json, version) VALUES
    ('acme', 'jira_issues',
     'project_key IN (:user.allowed_projects)',
     '{"reporter_email":{"mask":"redact","except_principals":["role:admin"]}}',
     1)
ON CONFLICT (tenant_id, table_name) DO NOTHING;
