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

### Task 14: OAuth token resolution
- `OAuthConnectionRecord` record DTO for `oauth_connection` table
- `OAuthConnectionStore` @Service — CRUD via JdbcTemplate, plaintext mode when wrappedDek empty
- `TokenService` interface in `authz.api` (cross-module boundary for SourceGateway to call)
- `OAuthTokenService` in `authz.principals` — only allowed non-crypto package to hold SecretKey
  - Singleflight via `ConcurrentHashMap<String, CompletableFuture<String>>` with `computeIfAbsent`
  - AES-GCM decrypt path when DEK is present; plaintext fallback for seed data
- V4 migration seeds 4 oauth_connection rows (alice/bob/carol + beta tenant)
- ArchUnit `tokenContainmentRule` updated: uses `haveSimpleName` (exact match) for OAuthToken/TokenValue

### Task 15: Source Gateway
- `SourceGatewayImpl` with hierarchical Resilience4j RateLimiter (global 100/s, tenant 20/s, user 5/s)
- Bulkhead per connector (10 concurrent calls max), non-blocking tryAcquire
- Circuit breaker per connector (50% failure rate / 10-call window / 30s open state)
- `SourceGatewayConfig` @Configuration bean wiring
- `getRateLimitStatus()` returns remaining/limit/resetsAt for tenant-level bucket

### Task 16: Live Query Engine
- `Fragment` record updated with `timeoutMs` field (backward-compatible 7-arg constructor)
- `LiveQueryService` interface in `livequery.api`
- `LiveQueryEngineImpl` executes LIVE fragments via SourceGateway with CompletableFuture timeout
  - freshness_ms = 0 for live results; SOURCE_TIMEOUT on deadline exceeded
- `LiveQueryConfig` @Configuration
- Orchestrator updated: virtual-thread parallel fragment execution, `partial=true` on timeout
- Orchestrator dispatches LIVE vs CACHE per fragment based on path

### Task 17: Path selector and freshness control
- `PathSelector` @Service: pure 6-branch decision function (aclAge → includeLatestData → watermark → budget → rows → LIVE)
- `AclFreshnessImpl`: age check from AuthzContext.aclSyncedAt()
- `RateLimitBudgetImpl`: backed by SourceGateway.getRateLimitStatus()
- `AclStore.getSnapshot()` returns `Instant.now()` (not EPOCH) when no ACL rows → avoids spurious LIVE override
- Orchestrator wires PathSelector via constructor injection: computes all 5 inputs per fragment
- 7 unit tests cover all 6 decision branches + boundary condition
- 3 integration tests: CACHE path, LIVE path, stale-ACL → LIVE override

### Task 18: Result Merger (hybrid cache+live)
- `ResultMerger` @Service: merges two QueryResult objects (cache + live) using Map-based dedup
  - Strategy: live row wins over cached row on same primary key (first column = PK)
  - Aggregate freshness_ms = max(cacheFreshnessMs, 0) = cacheFreshnessMs (cache is the stalest)
- `DuckDbSession`: in-memory DuckDB connection for join scratch space (separate from tenant file)
  - `registerTable(name, QueryResult)`: batch INSERT rows into in-memory table
  - `executeJoin(sql)` → QueryResult; `close()` releases connection
- `Fragment` record extended with `inListFilter: List<String>` (backward-compatible constructors)
- Orchestrator updated: hybrid mode when include_latest_data=true AND watermark exists
  - Executes CACHE and LIVE fragments in parallel; merges with ResultMerger
  - LIVE source reported as sources[0] so sources.get(0).path() = "LIVE" (backward compatible)
- 4 unit tests + 2 integration tests (WireMock Jira + pre-populated DuckDB)

### Task 19: Semi-join reduction
- `JoinStrategySelector` @Service: selects SEMI_JOIN_REDUCTION (sideA < 100 rows) or DUCKDB_HASH_JOIN
- Orchestrator updated for JOIN queries:
  1. Execute side A (jira_issues) first to get join keys
  2. Select strategy via JoinStrategySelector
  3. SEMI_JOIN_REDUCTION: pass IN-list keys via Fragment.inListFilter to side B
  4. DUCKDB_HASH_JOIN: load both sides into DuckDbSession, run JOIN SQL
- GithubConnector updated: reads `issue_keys=...` from SourceQuery.params(), appends as query param
- LiveQueryEngineImpl updated: encodes Fragment.inListFilter as `issue_keys=KEY1,KEY2,...` in params
- QueryMetadata.join_strategy populated with strategy name
- When include_latest_data=true on JOIN: side B always LIVE (ensures IN-list reaches connector)
- DuckDB hash join SQL uses original aliases (not table names) in ON clause
- 6 tests: 2 unit (JoinStrategySelector), 4 integration (join_strategy check, issue_keys filter, merged columns, large-sideA hash join)

## Pending Tasks
- Task 20+: Additional features
