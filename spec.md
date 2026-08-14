# Universal SQL Layer — Prototype Technical Specification

**Version:** 1.0
**Target runtime:** Single JVM, modular monolith (Java 21 + Spring Boot 3.x)
**Scope:** Demonstrate the materialized + live hybrid query path end-to-end, with real access control, envelope encryption, OAuth token resolution, RLS/CLS, rate limiting, and telemetry.

---

## 1. Purpose and Non-Goals

### 1.1 What this prototype proves

1. A SQL statement submitted to `POST /v1/query` is parsed, planned, and **decomposed into per-source fragments** that the cache layer and live layer can each independently execute.
2. Results from the **materialized (cache) path** and the **live path** are merged into a single consistent result set, with the live path engaged only when `include_latest_data=true`.
3. **Entitlements are enforced at plan time**, not post-filter: RLS predicates are injected into the plan and CLS masks rewrite the projection before any data is fetched.
4. **Envelope encryption is real**: materialized rows are stored ciphertext-at-rest, wrapped DEK per tenant, unwrapped through a KMS module that no upstream component can call directly.
5. **OAuth tokens are resolved at point-of-use** inside the Source Gateway, never carried in the plan.
6. **Rate limits are enforced centrally** at the Source Gateway with friendly, actionable error codes and `Retry-After`.
7. Every query emits **structured logs, Prometheus metrics, and an OpenTelemetry trace** showing connector time as a distinct span.

### 1.2 Explicit non-goals for the prototype

- No distributed deployment, no Kubernetes, no Terraform. Single process, `docker compose` for dependencies.
- No async job runner (the escalation tier is stubbed with a documented error code).
- No vector/hybrid document search — record-shaped sources only (Jira, GitHub).
- No real OAuth provider — the flow is real, the identity provider is mocked.
- No Iceberg/S3 lake tier. Materialized data lands in the single-deployment stack (§4).
- Semi-join reduction is implemented for the one demo join; full cost-based tier escalation is out of scope.

### 1.3 Architectural fidelity constraint

**Every module in §3 maps to exactly one named component in the architecture diagram.** Module boundaries are enforced by package structure and an interface-only contract between modules. No module may reach past its neighbour's interface. This is what makes the monolith decomposable later — extracting `livequery` or `sourcegateway` into its own service must require only changing the transport, not the contract.

---

## 2. Technology Choices

| Concern | Choice | Rationale |
|---|---|---|
| Language / framework | Java 21, Spring Boot 3.3 | Requested; virtual threads (Loom) make the fan-out-to-slow-sources pattern natural without reactive complexity |
| SQL parsing | **JSqlParser 4.9** | Pure-Java, AST-level, small. Apache Calcite is the production answer (real cost-based planning, pushdown rules) but its learning curve dominates a prototype. Documented as the Phase-2 upgrade path. |
| Join / merge engine | **DuckDB (embedded, `duckdb_jdbc`)** | In-process, zero-copy Arrow, sub-ms hash joins on 100K rows. Matches the diagram's DuckDB-inside-Query-Coordinator exactly. |
| Materialized store | **DuckDB persistent file, one per tenant** | Gives per-tenant physical isolation *and* per-tenant file encryption *and* crypto-shredding-by-file-delete in a single-deployment stack. |
| Metadata / control plane / ACL / audit | **PostgreSQL 16** | Single relational store for tenant configs, source catalog, principal closure, resource ACLs, audit log, job state. |
| Hot result cache | **Caffeine (in-process)** | Avoids a Redis dependency. Interface is `ResultCache` so a Valkey impl drops in later. |
| Metrics | Micrometer → Prometheus endpoint | |
| Tracing | OpenTelemetry Java SDK → OTLP → Jaeger | Required for the "connector time" trace screenshot |
| Logging | SLF4J + Logback, JSON encoder | Structured, trace-id correlated |
| Mock sources | **WireMock** (standalone, in `docker compose`) | Real HTTP, real latency injection, real 429s — not in-process fakes |
| Crypto | JDK `AES/GCM/NoPadding` + a `LocalKmsModule` | Real envelope encryption; KMS is an interface with a local file-backed impl |
| Load test | k6 | Assignment asks for k6/Gatling |

**Only justified Python deviation:** none. The full stack is Java. The k6 script is JavaScript (k6's native language), which is expected.

---

## 3. Module Map (architecture component → Java package)

Root package: `com.ema.usql`

| # | Module (package) | Diagram component | Responsibility |
|---|---|---|---|
| 1 | `api` | API Gateway | HTTP endpoints, request/response envelope, error rendering, OIDC token validation |
| 2 | `coordinator` | Query Coordinator → Orchestrator | Request lifecycle, plan invocation, fragment dispatch, timeout budget, partial-result assembly, replan |
| 3 | `coordinator.execution` | Execution engine + DuckDB + query cache | Runs fragments, hosts embedded DuckDB, merges result sets |
| 4 | `planner` | Query Optimiser → Query Planner and Optimiser | Parse → logical plan → policy injection → pushdown → physical plan with fragments |
| 5 | `planner.catalog` | Source catalog client | Table/column/capability descriptors per connector |
| 6 | `planner.stats` | Query statistics cache client | Cardinality estimates, source latency profiles |
| 7 | `authz` | AuthZ Service + Tenant-based RLS/CLS Enrichment | Principal resolution, ACL lookup, RLS predicate + CLS mask generation |
| 8 | `authz.principals` | Principal and OAuth store + access/principal updater | Principal closure, resource ACLs, OAuth connection records |
| 9 | `crypto` | KMS | KEK/DEK envelope encryption, key refs, crypto-shred |
| 10 | `knowledgecache` | Knowledge cache Service + Knowledge Database | Materialized store read/write, watermarks, encryption boundary |
| 11 | `livequery` | Live Query Engine | Executes a fragment against a live source via Source Gateway |
| 12 | `sourcegateway` | Source Gateway | Rate limiting, concurrency pools, circuit breaker, OAuth token resolution, retries |
| 13 | `connectors` | Adapter Repository (runtime side) | Connector SDK + `jira`, `github` adapters |
| 14 | `updates` | Updates Manager (Updates Handler + Periodic Updation + job state db) | Webhook ingest, scheduled reconciliation, watermark advance |
| 15 | `controlplane` | Control Plane | Tenant configs, source catalog registry, adapter registry, admin API |
| 16 | `audit` | Audit Service + Audit Events Log DB | Durable access trail incl. denials |
| 17 | `telemetry` | Metrics & Traces Query Layer (emitter side) | The metrics/logging library (§10) |

**Module contract rule:** each module exposes one `@Service` interface in `<module>/api/`. Cross-module calls go through that interface only. A checkstyle/ArchUnit rule enforces it (Task 22).

---

## 4. Single-Deployment Data Stack

Two stores. That is the whole stack.

```
PostgreSQL 16 ──── control plane, ACLs, principals, audit, job state, catalog
DuckDB (files)  ── per-tenant materialized "Knowledge Database" + join scratch
Caffeine        ── in-process query result cache (no external dep)
```

### 4.1 PostgreSQL schema

```sql
-- Control plane
tenant(tenant_id PK, name, deployment_mode, residency_tag, kek_id, status, created_at)
tenant_config(tenant_id FK, key, value)               -- dynamic config
source_catalog(connector_id PK, version, table_name, column_json, capability_json)
adapter_registry(connector_id, version, status, promoted_at)

-- Access management
principal_closure(tenant_id, user_id, principal_id)   -- transitive group expansion
resource_acl(tenant_id, source, resource_id, principal_id, acl_version, acl_synced_at)
oauth_connection(tenant_id, user_id, connector_id, connection_ref PK,
                 wrapped_token BYTEA, wrapped_dek BYTEA, expires_at, status)
policy(tenant_id, table_name, rls_expr, cls_json, version)

-- Ops
audit_event(id, ts, trace_id, tenant_id, user_id, connector_id, action,
            resource_ids, decision, reason, sql_hash)
job_state(job_id PK, tenant_id, connector_id, kind, watermark, last_run_at, status)
query_stats(tenant_id, table_name, p50_ms, p95_ms, est_rows, updated_at)
```

### 4.2 DuckDB — one file per tenant

Path: `data/tenants/{tenant_id}/knowledge.duckdb`

```sql
-- Materialized record tables (ciphertext columns for sensitive fields)
jira_issues(issue_key, project_key, status, priority, assignee_id,
            reporter_email_enc BLOB, summary, created_at, updated_at,
            acl_principals VARCHAR[], sourced_at, wrapped_dek BLOB)
github_prs(pr_number, repo, title, state, author_id, author_email_enc BLOB,
           linked_issue_key, created_at, updated_at,
           acl_principals VARCHAR[], sourced_at, wrapped_dek BLOB)
watermark(source, table_name, last_synced_at, last_cursor)
```

**Design notes.**
- `acl_principals` is an in-row array so the RLS filter is a `list_has_any()` predicate pushed into the DuckDB scan — enforcement is *inside* the scan, not a post-filter.
- Sensitive columns are stored encrypted per-row with the tenant DEK; non-sensitive columns are plaintext so they remain filterable/sortable. Documented trade-off: encrypting everything would make predicate pushdown impossible.
- `sourced_at` per row drives freshness evaluation.
- Per-tenant file = physical isolation + crypto-shred by KEK destroy (file becomes undecryptable) followed by file delete.

---

## 5. The Query Path (the core demo)

### 5.1 Request contract

```http
POST /v1/query
Authorization: Bearer <oidc-jwt>
Content-Type: application/json

{
  "sql": "SELECT i.issue_key, i.status, i.reporter_email, p.title
          FROM jira_issues i
          JOIN github_prs p ON p.linked_issue_key = i.issue_key
          WHERE i.project_key = 'PLAT' AND i.status = 'Open'
          ORDER BY i.updated_at DESC
          LIMIT 25",
  "include_latest_data": true,
  "max_staleness_ms": 60000,
  "timeout_ms": 1500
}
```

### 5.2 Response envelope

```json
{
  "columns": [{"name":"issue_key","type":"VARCHAR"}, ...],
  "rows": [[...], ...],
  "metadata": {
    "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
    "freshness_ms": 12400,
    "partial": false,
    "sources": [
      {"connector":"jira","path":"CACHE","rows":25,"freshness_ms":12400,"latency_ms":18},
      {"connector":"github","path":"LIVE","rows":31,"freshness_ms":0,"latency_ms":212}
    ],
    "rate_limit_status": {"jira":{"remaining":94,"limit":100},"github":{"remaining":40,"limit":60}},
    "policy_applied": {"rls":["project_key IN (...)"], "cls_masked":["reporter_email"]},
    "join_strategy": "SEMI_JOIN_REDUCTION"
  }
}
```

### 5.3 Execution sequence

```
1.  api            → validate OIDC JWT, extract {tenant_id, user_id}, resolve key_ref
2.  api            → coordinator.submit(QueryRequest, TenantContext)
3.  coordinator    → authz.resolve(tenant, user)
                     → principal closure (Postgres)
                     → policy lookup → RlsPredicate + ClsMaskSet
4.  coordinator    → planner.plan(sql, catalog, policy, stats, freshnessHint)
                     4a. JSqlParser → AST
                     4b. validate against source catalog (unknown table/col → 400)
                     4c. INJECT RLS predicate into WHERE
                     4d. REWRITE projection with CLS masks
                     4e. REJECT if a masked column appears in WHERE/ORDER BY  ← key check
                     4f. split into per-source Fragments; mark pushdown-able predicates
                     4g. choose path per fragment (§5.4) and join tier (§5.5)
5.  coordinator    → check result cache (key includes principal set + acl_version + mask set)
6.  coordinator    → dispatch fragments in parallel (virtual threads, per-fragment deadline)
       CACHE  → knowledgecache.execute(fragment)  → DuckDB scan (decrypt masked cols)
       LIVE   → livequery.execute(fragment)       → sourcegateway → connector → WireMock
7.  coordinator    → on fragment timeout: mark partial, continue with what returned
8.  coordinator    → REPLAN if semi-join: feed observed keys back into planner
9.  execution      → merge in DuckDB: register both result sets as Arrow tables, run join
10. execution      → dedupe: live row wins over cached row on same PK; recompute freshness_ms
11. coordinator    → audit.record(...) ; telemetry spans close
12. api            → render envelope
```

### 5.4 Path selection — the decision function

Implemented as `planner.PathSelector`, a pure function. **This is the single most important piece of logic in the prototype and must be independently unit-tested.**

```java
Path select(Fragment f, FreshnessHint hint, Watermark wm, RateLimitBudget budget, AclFreshness aclAge) {
    if (aclAge.olderThan(ACL_MAX_AGE))            return Path.LIVE;   // ACL staleness overrides everything
    if (!hint.includeLatestData())                return Path.CACHE;  // explicit user choice
    if (wm.ageMs() <= hint.maxStalenessMs())      return Path.CACHE;  // cache is fresh enough
    if (budget.exhaustedFor(f.connector()))       return Path.CACHE_DEGRADED; // + STALE_DATA warning
    if (f.estimatedRows() > LIVE_ROW_CEILING)     return Path.CACHE_DEGRADED;
    return Path.LIVE;
}
```

Note the ordering: **ACL freshness takes precedence over data freshness.** A stale permission set forces a live re-check even when the user asked for cached data — fail-closed on entitlements.

### 5.5 Join strategy (demo scope)

Only two tiers implemented, with the rest documented:

- **Tier 0 — Semi-join reduction** (implemented). Execute the selective side (Jira issues matching the RLS-filtered WHERE), extract `issue_key` values, push them as an `IN (...)` filter to the GitHub fragment. Chunk at 100 keys per request to respect URL/limit constraints. Emits `join_strategy: SEMI_JOIN_REDUCTION`.
- **Tier 1 — In-process DuckDB hash join** (implemented). Fallback when side A is not selective (> `SEMI_JOIN_KEY_CEILING` keys). Both sides registered as DuckDB tables, joined in-process.
- Tier 2 (short-lived materialization) and Tier 3 (async) — **stubbed**: planner returns `QUERY_TOO_LARGE` with guidance to narrow the predicate. Documented as the production escalation path.

---

## 6. Access Control Implementation

### 6.1 RLS

Policy row for the demo tenant:

```yaml
table: jira_issues
rls: "project_key IN (:user.allowed_projects)"
```

Compilation: `planner.PolicyCompiler` resolves `:user.allowed_projects` from the principal closure, produces an `Expression` AST node, and `AND`s it into the fragment's WHERE clause. For the cache path this becomes a DuckDB predicate; for the live path it becomes a connector-native filter (JQL `project in (...)`).

**Second enforcement layer (defense in depth):** the DuckDB scan additionally filters `list_has_any(acl_principals, :principal_set)`. Even if the policy predicate were wrong, resource-level ACLs still hold.

### 6.2 CLS

```yaml
cls:
  - column: reporter_email
    mask: PARTIAL          # j***@company.com
    unless_role: admin
```

Compilation: projection rewrite. `reporter_email` → `mask_partial(decrypt(reporter_email_enc))`. Three enforced invariants:

1. A masked column **may not appear** in WHERE, ORDER BY, GROUP BY, or a JOIN condition for a non-exempt user → `ENTITLEMENT_DENIED` with reason `MASKED_COLUMN_IN_PREDICATE`. (This is the binary-search reconstruction defense.)
2. Masking is applied **during projection**, so plaintext never enters the result buffer.
3. The result cache key includes the resolved mask set, so a masked and unmasked result can never collide.

### 6.3 Live path entitlements

The live path uses the **user's delegated OAuth token** resolved by connection_ref. The mock Jira/GitHub WireMock instances honour a `X-Mock-User` header and return only that user's visible resources — so the prototype demonstrates source-side enforcement rather than asserting it.

---

## 7. Encryption and Key Handling

### 7.1 Module boundary

```
api / coordinator / planner / livequery   →  see key_ref (a String) only
knowledgecache / authz.principals         →  call crypto.KmsModule
crypto.KmsModule                          →  holds KEKs; only component with key material
```

`KmsModule` interface:

```java
WrappedDek generateDek(String tenantId, EncryptionContext ctx);
SecretKey   unwrapDek(String tenantId, WrappedDek wrapped, EncryptionContext ctx);
void        destroyKek(String tenantId);   // crypto-shred
```

`LocalKmsModule` stores KEKs in a local keystore file (`data/kms/`). `EncryptionContext` carries `{tenant_id, purpose}` and is bound into the GCM AAD — unwrapping tenant A's DEK with tenant B's context fails cryptographically. Task 12 includes a test proving this.

### 7.2 Which paths encrypt what

| Path | At rest? | Key involvement |
|---|---|---|
| Live | No — memory only | KMS protects the **OAuth token**, not the payload; TLS in transit |
| Materialized | Yes | Per-tenant DEK, envelope-wrapped by tenant KEK |
| Result cache | Yes (in-memory but retained) | Values encrypted with tenant DEK; key includes tenant |
| DuckDB join scratch | Possibly (spill) | DuckDB `temp_directory` set to an encrypted-at-rest path; memory limit configured to fail rather than spill silently |
| Audit log | No (metadata only) | Never stores row values — only resource IDs and decisions |

**Invariant to enforce in code review and by test:** no component outside `crypto` and its two callers may obtain a `SecretKey`. Enforced by ArchUnit rule (Task 22).

### 7.3 OAuth token resolution

Tokens are resolved **inside `sourcegateway`**, at the moment the HTTP header is set:

```
livequery → sourcegateway.execute(connection_ref, sourceQuery)
              → authz.principals.resolveConnection(connection_ref)
                  → unwrap token via crypto
                  → refresh if expired (singleflight lock per connection_ref)
              → attach Authorization header
              → call connector
```

The plan carries `connection_ref` only. Task 14 includes a test asserting no token string appears in a serialized plan, log line, or trace attribute.

---

## 8. Source Gateway — rate limits and resilience

Single chokepoint for **all** outbound source traffic (live queries, periodic sync, principal refresh).

- **Hierarchical token buckets:** global-per-connector → per-tenant (weighted fair share) → per-user. Implemented with Bucket4j, in-memory for the prototype, interface allows a distributed backend later.
- **Concurrency pools** per connector, separate from rate limits — prevents head-of-line blocking when one source is slow.
- **Circuit breaker** (Resilience4j) per connector: opens on error-rate threshold, half-opens on a timer.
- **Backpressure signal** returned to the coordinator so the planner can degrade to `CACHE_DEGRADED` rather than fail (§5.4).
- **Retry-After** computed from bucket refill time and surfaced in the error response.

### 8.1 Error vocabulary

| Code | HTTP | Meaning | Extra |
|---|---|---|---|
| `RATE_LIMIT_EXHAUSTED` | 429 | Per-connector/tenant/user budget spent | `Retry-After`, `budget_scope` |
| `STALE_DATA` | 200 (warning in metadata) | Served from cache beyond requested staleness | `freshness_ms`, `requested_max_staleness_ms` |
| `ENTITLEMENT_DENIED` | 403 | RLS/CLS violation or unauthorized table | `reason` |
| `SOURCE_TIMEOUT` | 200 partial / 504 | Fragment exceeded its deadline | `partial: true`, per-source detail |
| `SOURCE_UNAVAILABLE` | 503 | Circuit breaker open | `Retry-After` |
| `CONNECTION_REAUTH_REQUIRED` | 401 | OAuth refresh failed / revoked | `connector_id`, reconnect URL |
| `QUERY_TOO_LARGE` | 400 | Exceeds prototype join tiers | guidance to narrow predicate |
| `UNSUPPORTED_SQL` | 400 | Outside the SELECT subset | offending clause |

---

## 9. Mock Sources

WireMock standalone, two instances in `docker compose`:

**Jira mock (`:8081`)** — `GET /rest/api/3/search?jql=...`
- Honours `project in (...)`, `status = ...`, `updated > ...`
- Honours `X-Mock-User` for ACL filtering
- Returns `X-RateLimit-Remaining`; returns 429 with `Retry-After` when a scenario is triggered
- Configurable latency injection (200ms default, 2500ms "slow" scenario for the timeout demo)

**GitHub mock (`:8082`)** — `GET /repos/{owner}/{repo}/pulls?...`
- Honours an `issue_key` IN-list filter (the semi-join target)
- Same rate-limit and latency behaviour
- PR titles embed Jira keys (`PLAT-123: fix ...`) so the join is realistic

Seed data: 500 Jira issues across 3 projects, 800 GitHub PRs, ~60% linked. Two users with deliberately different project visibility so RLS is observable.

---

## 10. Telemetry Library (`telemetry` module)

A thin facade so every module emits consistently without importing Micrometer/OTel directly.

```java
public interface Telemetry {
    Span span(String name, Map<String,String> attrs);      // auto-closes, records exceptions
    void counter(String name, Map<String,String> tags);
    void timer(String name, Duration d, Map<String,String> tags);
    void gauge(String name, Supplier<Number> v, Map<String,String> tags);
    StructuredLogger logger(Class<?> clazz);               // JSON, auto-injects trace_id + tenant_id
}
```

### 10.1 Required spans

```
query.total
├── authz.resolve
├── planner.plan
├── cache.lookup
├── fragment.jira      [attr: path=CACHE|LIVE]
│   └── connector.jira.http          ← the "connector time" span for the screenshot
├── fragment.github    [attr: path=LIVE]
│   └── connector.github.http
└── execution.merge    [attr: join_strategy=...]
```

### 10.2 Required metrics

| Metric | Type | Tags |
|---|---|---|
| `usql_query_duration_ms` | histogram | tenant, path, partial |
| `usql_connector_duration_ms` | histogram | connector, outcome |
| `usql_cache_hit_ratio` | counter pair | tenant, table |
| `usql_freshness_ms` | histogram | connector, table |
| `usql_rate_limit_rejections_total` | counter | connector, tenant, scope |
| `usql_entitlement_denials_total` | counter | tenant, reason |
| `usql_partial_results_total` | counter | connector |
| `usql_active_fragments` | gauge | connector |

### 10.3 Structured log contract

Every log line: `{ts, level, trace_id, span_id, tenant_id, user_id, module, event, ...fields}`.
**Hard rule:** no row values, no token material, no key material. A test (Task 20) scans emitted logs during the integration suite for the seeded secret strings.

---

## 11. Repository Layout

```
universal-sql/
├── docker-compose.yml            # postgres, jaeger, prometheus, wiremock-jira, wiremock-github
├── README.md                     # quickstart + trade-off rationale
├── build.gradle.kts
├── src/main/java/com/ema/usql/
│   ├── UsqlApplication.java
│   ├── api/            controller, dto, error rendering, oidc filter
│   ├── coordinator/    Orchestrator, FragmentDispatcher, PartialResultAssembler
│   │   └── execution/  ExecutionEngine, DuckDbSession, ResultMerger, ResultCache
│   ├── planner/        SqlParser, LogicalPlan, PolicyCompiler, PathSelector,
│   │                   JoinStrategySelector, PhysicalPlan, Fragment
│   │   ├── catalog/    SourceCatalog, CapabilityDescriptor
│   │   └── stats/      StatsClient
│   ├── authz/          AuthzService, RlsPredicate, ClsMaskSet
│   │   └── principals/ PrincipalStore, AclStore, OAuthConnectionStore, PrincipalUpdater
│   ├── crypto/         KmsModule, LocalKmsModule, EnvelopeCipher, EncryptionContext
│   ├── knowledgecache/ KnowledgeCacheService, TenantDuckDbRegistry, WatermarkStore
│   ├── livequery/      LiveQueryEngine
│   ├── sourcegateway/  SourceGateway, RateLimiter, ConcurrencyPool, CircuitBreakerRegistry,
│   │                   TokenResolver
│   ├── connectors/     ConnectorSdk (interface), JiraConnector, GithubConnector
│   ├── updates/        UpdatesHandler, PeriodicUpdater, JobStateStore
│   ├── controlplane/   TenantConfigService, SourceCatalogRegistry, AdminController
│   ├── audit/          AuditService, AuditEvent
│   └── telemetry/      Telemetry, TelemetryImpl, StructuredLogger
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/           # Flyway
│   └── policies/demo-tenant.yml
├── src/test/java/...
├── wiremock/                   # mappings + seed fixtures
├── k6/load-test.js
└── docs/
    ├── architecture-mapping.md   # module → diagram component table
    └── trade-offs.md
```

---

## 12. Task Breakdown for Execution

Tasks are ordered by dependency. Each is independently testable and sized for one Claude Code session. **Acceptance criteria are the definition of done — do not proceed to the next task until they pass.**

### Phase A — Foundation (Tasks 1–5)

**Task 1 — Project scaffold and compose stack**
Gradle multi-package Spring Boot 3.3 / Java 21 project. `docker-compose.yml` with Postgres 16, Jaeger all-in-one, Prometheus, two WireMock containers. Flyway wired. Health endpoint.
*Acceptance:* `docker compose up && ./gradlew bootRun` starts clean; `/actuator/health` returns UP; Flyway applies an empty baseline migration.

**Task 2 — Telemetry module**
Implement `Telemetry`, `TelemetryImpl` (Micrometer + OTel SDK), `StructuredLogger` with JSON Logback encoder auto-injecting `trace_id`, `tenant_id`. Prometheus scrape endpoint at `/actuator/prometheus`, OTLP export to Jaeger.
*Acceptance:* a synthetic span appears in Jaeger UI; a synthetic counter appears in `/actuator/prometheus`; a log line contains `trace_id` matching the Jaeger span.

**Task 3 — Postgres schema and control plane**
All Flyway migrations from §4.1. `TenantConfigService`, `SourceCatalogRegistry`. Seed one demo tenant (`acme`) with residency tag and KEK id, two users with different project visibility.
*Acceptance:* migrations apply; a repository test reads the seeded tenant and both users' principal closures.

**Task 4 — Crypto module**
`KmsModule` interface, `LocalKmsModule` (file-backed keystore), `EnvelopeCipher` (AES-GCM, `EncryptionContext` bound as AAD), `destroyKek`.
*Acceptance:* round-trip encrypt/decrypt passes; **unwrapping with a mismatched `EncryptionContext` throws**; after `destroyKek`, previously wrapped DEKs fail to unwrap.

**Task 5 — Connector SDK and mock sources**
`ConnectorSdk` interface (`CapabilityDescriptor`, `fetch(SourceQuery, Credential)`, error mapping). WireMock mappings for Jira and GitHub per §9, including rate-limit and slow-response scenarios. Seed fixtures generated by a script.
*Acceptance:* a bare integration test calls each mock through its connector and gets typed records back; the slow scenario returns after 2500ms; the 429 scenario returns `Retry-After`.

### Phase B — Materialized path (Tasks 6–9)

**Task 6 — Knowledge cache service**
`TenantDuckDbRegistry` (one file per tenant, lazily opened, connection-pooled), schema from §4.2, `WatermarkStore`. Encrypted-column read/write through `crypto`.
*Acceptance:* write 100 Jira issues with an encrypted `reporter_email_enc`; read them back decrypted; a raw DuckDB query shows the column as unreadable bytes.

**Task 7 — Updates Manager**
`PeriodicUpdater` (scheduled, cursor-based incremental pull through Source Gateway), `UpdatesHandler` (webhook endpoint), `JobStateStore`, watermark advance, DLQ table for failed events.
*Acceptance:* a scheduled run populates the tenant DuckDB from the mocks and advances the watermark; a posted webhook updates a single row and bumps `sourced_at`; a poisoned event lands in the DLQ rather than blocking the queue.

**Task 8 — SQL parser and logical plan**
JSqlParser integration. Support: `SELECT` projection, `WHERE` (=, IN, >, <, AND), `ORDER BY`, `LIMIT`, single `JOIN ... ON`. Validate against `SourceCatalog`; reject anything outside the subset with `UNSUPPORTED_SQL`.
*Acceptance:* unit tests over ~15 statements — valid ones produce the expected logical plan, invalid ones produce the right error code and offending clause.

**Task 9 — Cache-only query path end-to-end**
`Orchestrator` + `ExecutionEngine` + DuckDB execution of a single-source fragment. `POST /v1/query` with `include_latest_data=false` returns rows and a response envelope with `freshness_ms`.
*Acceptance:* curl returns 25 Jira rows from the materialized store; `freshness_ms` is non-zero and matches the watermark age; the Jaeger trace shows `query.total → cache.lookup → fragment.jira[path=CACHE]`.

### Phase C — Access control (Tasks 10–13)

**Task 10 — AuthZ service and principal resolution**
`AuthzService`, `PrincipalStore` (closure lookup), `AclStore`, `acl_synced_at` tracking. OIDC JWT validation in `api` (mock issuer, RS256, local JWKS).
*Acceptance:* two seeded users resolve to different principal sets; an expired/invalid JWT is rejected at the gateway.

**Task 11 — RLS injection**
`PolicyCompiler` reads `policies/demo-tenant.yml`, produces an `RlsPredicate`, injects it into the WHERE clause at plan time. Second layer: `list_has_any(acl_principals, :principals)` in the DuckDB scan.
*Acceptance:* user A sees only PLAT issues, user B only CORE — **same SQL, different rows**. A test asserts the injected predicate is present in the generated DuckDB SQL string (not applied afterwards). A test with a deliberately broken policy predicate shows the ACL layer still filters correctly.

**Task 12 — CLS masking**
`ClsMaskSet`, projection rewrite, `mask_partial` implementation. Enforce the three invariants in §6.2 — in particular reject masked columns in WHERE/ORDER BY/GROUP BY/JOIN conditions.
*Acceptance:* non-admin sees `j***@company.com`, admin sees the full address; `WHERE reporter_email = 'x@y.com'` returns `ENTITLEMENT_DENIED / MASKED_COLUMN_IN_PREDICATE`; a test confirms the plaintext never appears in the response buffer for a masked user.

**Task 13 — Audit service**
`AuditService` writing to `audit_event`, invoked for every query — **including denials**. Records resource IDs and decisions, never values.
*Acceptance:* a successful query and a denied query both produce rows with correct `decision`, `reason`, and matching `trace_id`; a test asserts no row value or email appears in any audit row.

### Phase D — Live path (Tasks 14–17)

**Task 14 — OAuth token resolution**
`OAuthConnectionStore` with wrapped tokens, `TokenResolver` inside `sourcegateway` with singleflight refresh per `connection_ref`, `CONNECTION_REAUTH_REQUIRED` on failure.
*Acceptance:* a live call succeeds with a valid connection; an expired token triggers exactly one refresh under 10 concurrent requests (singleflight proven by mock call count); **a test asserts no token string appears in any serialized plan, log line, or span attribute**.

**Task 15 — Source Gateway**
Hierarchical Bucket4j limiters (connector → tenant → user), per-connector concurrency pools, Resilience4j circuit breakers, `Retry-After` computation, backpressure signal to the coordinator.
*Acceptance:* exceeding the per-tenant budget returns `RATE_LIMIT_EXHAUSTED` with a correct `Retry-After`; one tenant saturating its budget does not affect another tenant's success rate (fairness test); a forced error rate opens the breaker and returns `SOURCE_UNAVAILABLE`.

**Task 16 — Live Query Engine**
Executes a fragment against a live source via Source Gateway, maps connector responses to the internal row format, applies per-fragment deadline.
*Acceptance:* a single-source live query returns rows with `freshness_ms: 0` and `path: LIVE`; the slow-source scenario hits the deadline and yields `partial: true` with the other fragment's rows still present.

**Task 17 — Path selector and freshness control**
Implement `PathSelector` per §5.4 exactly, including the ACL-freshness override. Wire `include_latest_data` and `max_staleness_ms`.
*Acceptance:* unit tests cover all six branches of the decision function. Integration: same SQL with `include_latest_data` false vs true takes CACHE vs LIVE, visible in response metadata and trace attributes. Forcing `acl_synced_at` old routes to LIVE even with `include_latest_data=false`.

### Phase E — Merge and joins (Tasks 18–19)

**Task 18 — Result merger**
Register cache and live result sets as DuckDB tables, union with live-wins deduplication on primary key, recompute aggregate `freshness_ms` as the max staleness across contributing sources.
*Acceptance:* a hybrid query where the live source has one updated row returns the live version, not the cached one; `freshness_ms` reflects the stalest contributing source.

**Task 19 — Semi-join reduction**
`JoinStrategySelector`: if side A's result is under `SEMI_JOIN_KEY_CEILING`, extract keys and push an IN-list to side B (chunked at 100); otherwise fall back to DuckDB hash join. Coordinator replans after observing side A's cardinality.
*Acceptance:* the demo cross-source query reports `join_strategy: SEMI_JOIN_REDUCTION`; a metric/assert proves the GitHub mock received a **filtered** request, not a full fetch; forcing a large side A falls back to hash join and reports it.

### Phase F — Hardening and deliverables (Tasks 20–24)

**Task 20 — Test suite**
Unit tests for planner, policy compiler, path selector, envelope cipher. Integration tests (Testcontainers) for: cache-only query, live-only query, hybrid merge, RLS separation, CLS masking, rate-limit rejection, partial results, crypto-shred. Plus the **secret-leak scan** over emitted logs.
*Acceptance:* `./gradlew test` green; ≥ 2 tests explicitly named in the README as the assignment's "1–2 tests".

**Task 21 — k6 load test**
Script ramping to ~500–1000 RPS for 60s against a mix of cache-only and hybrid queries.
*Acceptance:* run completes; P50/P95 recorded for cache-only queries; results table in README with a note on where the prototype's single-process limits bind.

**Task 22 — Architecture conformance rules**
ArchUnit tests: (a) cross-module calls only via `<module>/api/` interfaces; (b) no class outside `crypto` + its two callers references `SecretKey`; (c) no class outside `sourcegateway` references a token type.
*Acceptance:* rules pass; deliberately introducing a violation fails the build.

**Task 23 — Crypto-shred and off-boarding**
Admin endpoint `DELETE /admin/v1/tenant/{id}` → destroy KEK, mark tenant inactive, delete the tenant DuckDB file, cancel scheduled jobs.
*Acceptance:* post-shred, queries return tenant-inactive; a test proves previously materialized rows are undecryptable **before** file deletion (crypto-shred is effective on its own).

**Task 24 — README, docs, and screenshots**
Quickstart (`docker compose up`, seed, three curl examples showing cache-only / live / hybrid). `docs/architecture-mapping.md` with the module→diagram table. `docs/trade-offs.md` covering: JSqlParser vs Calcite, DuckDB-per-tenant vs shared table, no async tier, in-process modules vs services, encrypting only sensitive columns. Jaeger screenshot showing connector time, Prometheus/Grafana screenshot, with a short note on what each proves.
*Acceptance:* a reader who has never seen the repo can go from clone to a working hybrid query in under 10 minutes.

---

## 13. Demo Script (what to show)

1. `include_latest_data=false` → 25 rows, `freshness_ms: 12400`, all `path: CACHE`, ~40ms.
2. Same SQL, `include_latest_data=true` → same rows, `freshness_ms: 0`, `path: LIVE`, ~250ms. **Same SQL, different freshness/latency trade-off — the core thesis.**
3. Cross-source join → `join_strategy: SEMI_JOIN_REDUCTION`, WireMock request log proves the filtered second call.
4. Switch user → same SQL, different rows (RLS), `reporter_email` masked (CLS).
5. `WHERE reporter_email = ...` as non-admin → `ENTITLEMENT_DENIED / MASKED_COLUMN_IN_PREDICATE`.
6. Hammer the rate limit → `RATE_LIMIT_EXHAUSTED` with `Retry-After`, then the same query with `include_latest_data=true` degrading gracefully to `CACHE_DEGRADED` + `STALE_DATA`.
7. Enable the slow-source scenario → `partial: true` with the fast fragment's rows returned.
8. Jaeger trace showing `connector.jira.http` as a distinct span.
9. Crypto-shred the tenant → raw DuckDB file present but undecryptable.

---

## 14. Known Deviations from the Full Architecture

Stated plainly so the reviewer sees they are deliberate:

| Full architecture | Prototype | Why |
|---|---|---|
| Separate services | Modules in one JVM | Speed; contracts preserved so extraction is mechanical |
| OpenSearch/ClickHouse serving tier | DuckDB per tenant | Single-deployment constraint; same read/write contract |
| Iceberg on S3 lake tier | Absent | No replay/backfill demo in scope |
| Async job runner | Stubbed (`QUERY_TOO_LARGE`) | Documented as Tier 3 escalation |
| Distributed rate limiter | In-memory Bucket4j | Interface allows a Redis/Valkey backend |
| Real KMS | File-backed `LocalKmsModule` | Same interface, same envelope semantics |
| Real OIDC provider | Mock issuer with local JWKS | Validation logic is real |
| Vector/hybrid document search | Absent | Record-shaped sources only |
