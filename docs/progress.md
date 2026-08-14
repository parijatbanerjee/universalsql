# Universal SQL Layer — Implementation Progress

## Completed Tasks

| Task | Description | Status | Commit |
|------|-------------|--------|--------|
| 1b | Cross-module interfaces/DTOs (ConnectorSdk, KnowledgeCacheService, Telemetry, etc.) | ✅ Done | cf669aa |
| 1 | Gradle scaffold, docker-compose.yml, Flyway baseline | ✅ Done | cf669aa |
| 2 | Telemetry module (TelemetryImpl, StructuredLoggerImpl, SpanImpl) | ✅ Done | cf669aa |
| 3 | PostgreSQL schema V1+V2 migrations, TenantConfigService, SourceCatalogRegistry, seed data | ✅ Done | f28c18e |
| 4 | Crypto module (LocalKmsModule, EnvelopeCipher, CryptoConfig) | ✅ Done | 236135b |
| 22 | ArchUnit conformance rules (module boundaries, SecretKey containment, token containment) | ✅ Done | 4792d34 |
| 5 | Connector SDK and mock sources (JiraConnector, GithubConnector, ConnectorRegistry, WireMock mappings) | ✅ Done | 1afd249 |
| 6 | Knowledge cache service (TenantDuckDbRegistry, WatermarkStore, KnowledgeCacheServiceImpl) | ✅ Done | 38f60a8 |
| 7 | Updates Manager (PeriodicUpdater, UpdatesHandler, JobStateStore, V3 DLQ migration) | ✅ Done | 5d4ecaa |

## Key Design Decisions

### Task 5: Connector SDK
- Uses Spring's `RestClient` (not `RestTemplate`) for HTTP calls
- `ON CONFLICT (source, table_name) DO UPDATE` in WatermarkStore upserts
- 429 responses throw `UsqlException(RATE_LIMIT_EXHAUSTED)` with Retry-After value preserved in message
- WireMock standalone 3.9.1 used for in-process mocking (no Docker required for connector tests)

### Task 6: Knowledge Cache
- DuckDB JDBC v1.1.3: `getBlob()` must be used instead of `getBytes()` for BLOB columns
- `VARCHAR[]` (acl_principals) columns cannot appear in `ON CONFLICT DO UPDATE SET` in DuckDB 1.1.x; omitted from update clause
- AES-GCM envelope encryption implemented inline in `KnowledgeCacheServiceImpl` (allowed module per spec §7.1)
- `LocalKmsModule` made public to enable cross-package test construction

### Task 7: Updates Manager
- Scheduling disabled in tests via `usql.scheduling.enabled=false` property on `@ConditionalOnProperty` in `UpdatesConfig`
- DLQ null-tenantId fallback ensures `NOT NULL` constraint on `dlq_event.tenant_id` is always satisfied
- `PeriodicUpdater` is manually called in tests; `@Scheduled` is not triggered

## Pending Tasks
- Task 8: Source Gateway (circuit breaker, rate limiting, live fetch)
- Task 9: Query Planner
- Task 10: SQL Coordinator
- Task 11: AuthZ module
- Task 12: Audit module
- Task 13: API layer (REST endpoints)
- Task 14: OAuth token management
- Task 15+: Additional features
