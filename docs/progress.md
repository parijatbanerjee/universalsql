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
| 8 | SQL parser and logical plan (SqlParser, LogicalPlan, SourceCatalog, InMemorySourceCatalog) | ✅ Done | TBD |
| 9 | Cache-only query path end-to-end (Orchestrator, ExecutionEngine, QueryController, API DTOs) | ✅ Done | TBD |

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

### Task 8: SQL Parser
- Uses JSqlParser 4.9 with `ExpressionVisitorAdapter` (not deprecated `ExpressionDeParser.visit(Expression)`)
- Logical column names in catalog: `reporter_email` / `author_email` (physical schema uses `_enc` suffix)
- Validates: SELECT-only, no OR, no subqueries, no aggregates, at most one JOIN, all tables/columns in catalog
- `InMemorySourceCatalog` hardcodes the two known tables; interface allows future override

### Task 9: Cache-only Query Path
- `Orchestrator` implements the full span hierarchy: `query.total` → `cache.lookup` → `fragment.{connector}[path=CACHE]`
- `StubAuthzService` (main source) registered with `@ConditionalOnMissingBean` — Task 10 can override it
- `QueryController` uses `@AutoConfigureMockMvc(addFilters=false)` in tests (no JWT setup needed)
- `freshness_ms` comes from `WatermarkStore.ageMs()` — non-zero once a watermark is written

### Task 10: AuthZ service and principal resolution
- `AuthzServiceImpl` (@Primary) replaces StubAuthzService; resolves principals from `principal_closure` table
- `PrincipalStore`, `AclStore`, `PolicyStore` added in `authz.principals` and `authz` packages
- `MockJwksConfig` generates ephemeral RSA key pair; `SecurityConfig` uses it for NimbusJwtDecoder
- Empty principalSet (unauthenticated/no-auth test scenarios) returns null RLS predicate (pass-through)
- Expired JWT → 401; valid JWT for alice → 200

### Task 11: RLS injection
- `PolicyCompiler.compile()` resolves `:user.allowed_projects` placeholder from principal set
- `PolicyCompiler.injectIntoSql()` uses JSqlParser to AND RLS predicate into WHERE clause
- ACL second-enforcement layer in `KnowledgeCacheServiceImpl.buildAclFilteredSql()` uses DuckDB `list_intersect`
- Fixed SQL injection bug: LIMIT/ORDER BY were incorrectly wrapped inside parentheses when injecting ACL filter

### Task 12: CLS masking
- `MaskApplier` applies PARTIAL (first char + *** + @domain) and REDACT (***) masks
- `ClsMaskSet` populated from `cls_json` in policy table; exempts principals with `role:admin`
- `SqlParser.validateMaskedColumnsNotInPredicates()` throws ENTITLEMENT_DENIED if masked column in WHERE/ORDER BY
- `KnowledgeCacheServiceImpl.execute()` applies mask after decrypting reporter_email_enc
- Added `reporter_email_enc` and `wrapped_dek` to `InMemorySourceCatalog` for physical column query support

### Task 13: Audit service
- `AuditServiceImpl` inserts rows into `audit_event` table; never stores email/row data/tokens
- Orchestrator records ALLOW/DENY events with trace_id, sql_hash (SHA-256 of tenantId:sql)
- DENY events capture the error code reason (e.g. MASKED_COLUMN_IN_PREDICATE)
- `QueryMetadata.trace_id` included in response JSON for correlation

## Pending Tasks
- Task 14+: Additional features
