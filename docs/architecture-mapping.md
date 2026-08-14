# Architecture Mapping

This document maps each Java module/package to its corresponding component in the spec §3 system diagram.

## Module-to-Diagram Component Table

| # | Java Package | Spec Component | Responsibility |
|---|-------------|----------------|----------------|
| 1 | `com.ema.usql.api` | API Gateway / HTTP Layer | `QueryController`, `DevController`, `SecurityConfig`, `MockJwksConfig` — accepts HTTP, validates JWT, routes to Orchestrator |
| 2 | `com.ema.usql.coordinator` | Orchestrator / Query Coordinator | `Orchestrator` — parse → authz → RLS inject → CLS validate → execute → merge → audit → cache |
| 3 | `com.ema.usql.planner` | Query Planner | `SqlParser`, `LogicalPlan`, `PolicyCompiler`, `PathSelector`, `JoinStrategySelector`, `AclFreshnessImpl`, `RateLimitBudgetImpl` |
| 4 | `com.ema.usql.planner.catalog` | Source Catalog | `SourceCatalog`, `InMemorySourceCatalog` — table/column metadata, column sensitivity flags |
| 5 | `com.ema.usql.authz` | AuthZ / Principal Resolution | `AuthzServiceImpl`, `PolicyStore` — resolves principal closure, loads RLS/CLS policy from Postgres |
| 6 | `com.ema.usql.authz.api` | AuthZ API | `AuthzService`, `AuthzContext`, `RlsPredicate`, `ClsMaskSet`, `TokenService` — cross-module contracts |
| 7 | `com.ema.usql.authz.principals` | Principal Store / Token Service | `PrincipalStore`, `AclStore`, `OAuthConnectionStore`, `OAuthTokenService` — principal closure, ACL snapshot, singleflight token refresh |
| 8 | `com.ema.usql.knowledgecache` | Knowledge Cache (DuckDB) | `KnowledgeCacheServiceImpl`, `TenantDuckDbRegistry`, `WatermarkStore` — per-tenant DuckDB files, encrypted column storage, ACL second-enforcement |
| 9 | `com.ema.usql.knowledgecache.api` | Knowledge Cache API | `KnowledgeCacheService`, `Watermark` — cross-module interface |
| 10 | `com.ema.usql.livequery` | Live Query Engine | `LiveQueryEngineImpl` — executes LIVE fragments via SourceGateway with CompletableFuture timeout |
| 11 | `com.ema.usql.livequery.api` | Live Query API | `LiveQueryService` — cross-module interface |
| 12 | `com.ema.usql.sourcegateway` | Source Gateway | `SourceGatewayImpl` — hierarchical Resilience4j rate limiters, bulkheads, circuit breakers per connector |
| 13 | `com.ema.usql.sourcegateway.api` | Source Gateway API | `SourceGateway` — cross-module interface |
| 14 | `com.ema.usql.connectors` | Connector SDK / Source Adapters | `JiraConnector`, `GithubConnector`, `ConnectorRegistry` — REST clients against live APIs |
| 15 | `com.ema.usql.connectors.api` | Connector API | `ConnectorSdk`, `ConnectorRecord`, `SourceQuery` — cross-module contracts |
| 16 | `com.ema.usql.updates` | Updates Manager | `PeriodicUpdater`, `UpdatesHandler`, `JobStateStore` — scheduled background polling, DLQ, watermark advancement |
| 17 | `com.ema.usql.crypto` | Crypto Module | `LocalKmsModule`, `EnvelopeCipher`, `CryptoShredServiceImpl` — KEK/DEK envelope encryption, AES-256-GCM, crypto-shred |
| 18 | `com.ema.usql.crypto.api` | Crypto API | `KmsModule`, `CryptoShredService`, `WrappedDek`, `EncryptionContext` — cross-module contracts |
| 19 | `com.ema.usql.audit` | Audit Service | `AuditServiceImpl` — writes ALLOW/DENY events to `audit_event` table; never stores row data |
| 20 | `com.ema.usql.audit.api` | Audit API | `AuditService`, `AuditEvent` — cross-module interface |
| 21 | `com.ema.usql.telemetry` | Telemetry Implementation | `TelemetryImpl`, `SpanImpl`, `StructuredLoggerImpl` — OpenTelemetry spans, Prometheus counters, JSON logs |
| 22 | `com.ema.usql.telemetry.api` | Telemetry API | `Telemetry`, `Span`, `StructuredLogger` — facade used by all modules |
| 23 | `com.ema.usql.controlplane` | Control Plane | `TenantConfigService`, `SourceCatalogRegistry`, `AdminController` — tenant config, source catalog, admin off-boarding |
| 24 | `com.ema.usql.shared` | Shared Kernel | `Fragment`, `QueryResult`, `TenantContext`, `UsqlException`, `ErrorCode`, `QueryPath`, `JoinStrategy` — cross-module value types |
| 25 | `com.ema.usql.coordinator.execution` | Execution Engine | `ExecutionEngine` (cache fragment executor), `ResultMerger` (hybrid merge), `DuckDbSession` (in-memory join), `ResultCache` / `CaffeineResultCache` (5-min TTL result cache) |

## Request Flow

```
HTTP POST /v1/query
       |
       v
QueryController
  extract TenantContext from JWT
       |
       v
Orchestrator.execute()
  0. checkTenantActive (Postgres tenant table)
  1. SqlParser.parse()         — validate, extract tables/columns/joins
  2. AuthzService.resolve()    — principal closure, RLS template, CLS masks
  3. ResultCache.get()         — cache hit? return immediately
  4. PolicyCompiler.compile()  — fill :user.allowed_projects placeholder
  5. validate masked cols not in predicates
  6. PolicyCompiler.injectIntoSql() — AND the RLS predicate into WHERE
  7. PathSelector.select()     — CACHE | LIVE decision per fragment
  8. if JOIN: JoinStrategySelector → SEMI_JOIN_REDUCTION | DUCKDB_HASH_JOIN
  9. ExecutionEngine (CACHE) | LiveQueryService (LIVE) | hybrid parallel
  10. ResultMerger.merge()     — live row wins over cached row on PK
  11. AuditService.record()    — ALLOW/DENY
  12. ResultCache.put()        — store for future hits
       |
       v
QueryResponse (columns, rows, metadata{trace_id, freshness_ms, sources, policy, join_strategy})
```

## Data Flow for Encrypted Columns

```
Write path (UpdatesManager):
  ConnectorRecord.reporter_email
    → KmsModule.generateDek(tenantId)
    → EnvelopeCipher.encrypt(email, dek, ctx)
    → store ciphertext + wrapped_dek in DuckDB jira_issues row

Read path (ExecutionEngine / KnowledgeCacheServiceImpl):
  DuckDB jira_issues row
    → read wrapped_dek bytes
    → KmsModule.unwrapDek(tenantId, wrappedDek, ctx) → dek
    → EnvelopeCipher.decrypt(ciphertext, dek, ctx) → reporter_email
    → MaskApplier.apply(reporter_email, clsMaskSet) → masked value
    → return in QueryResult row

Crypto-shred:
  AdminController.shredTenant()
    → CryptoShredService.shred(tenantId) → KmsModule.destroyKek(tenantId)
    → KEK file deleted from data/kms/{tenantId}.key
    → All wrapped DEKs are permanently inaccessible
    → tenant.status = 'inactive' in Postgres
    → DuckDB file deleted (cleanup)
```
