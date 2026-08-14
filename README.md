# Universal SQL Layer

A multi-tenant, federated query gateway that runs SQL across Jira and GitHub data sources, combining live API results with an encrypted local knowledge cache (DuckDB), with row-level security (RLS), column-level security (CLS), rate limiting, OAuth token management, and crypto-shred off-boarding.

## Quick Start

**Prerequisites:** Java 21, Docker (for Postgres), `./gradlew`

### 1. Start Postgres

```bash
docker compose up -d
```

### 2. Build and run

```bash
./gradlew bootRun
```

Flyway migrations run automatically and seed the `acme` tenant, source catalog, and OAuth connection records.

### 3. Generate a token

```bash
TOKEN=$(curl -s 'http://localhost:8080/dev/token?userId=alice&tenantId=acme')
echo $TOKEN
```

### 4. Run your first query

#### Cache-only query

```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT * FROM jira_issues LIMIT 25","include_latest_data":false,"max_staleness_ms":60000,"timeout_ms":2000}'
```

#### Live query (same SQL, fresh data)

```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT * FROM jira_issues LIMIT 25","include_latest_data":true,"max_staleness_ms":0,"timeout_ms":2000}'
```

#### Hybrid cross-source join

```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT i.issue_key, i.status, p.title FROM jira_issues i JOIN github_prs p ON p.linked_issue_key = i.issue_key WHERE i.project_key = '\''PLAT'\''","include_latest_data":true,"max_staleness_ms":0,"timeout_ms":5000}'
```

### 5. Admin: crypto-shred a tenant

```bash
curl -X DELETE http://localhost:8080/admin/v1/tenant/acme \
  -H "X-Admin-Key: test-admin-key"
# After this, all queries for 'acme' return 403 ENTITLEMENT_DENIED
```

---

## The Two Canonical Tests

These two tests prove the core correctness properties of the system:

### 1. `Task11RlsTest#bobSeesOnlyCoreRows`

Proves **Row-Level Security (RLS)**: alice (who has `project:PLAT` and `project:CORE`) and bob (who has only `project:CORE`) execute the same SQL. The PolicyCompiler injects `project_key IN ('CORE')` into bob's query, and the ACL second-enforcement layer in DuckDB confirms only `project:CORE` rows are visible to bob.

```bash
./gradlew test --tests '*Task11RlsTest.bobSeesOnlyCoreRows'
```

### 2. `Task14TokenTest#resolveToken_singleflight_refreshCalledOnce`

Proves **singleflight token refresh**: 10 concurrent threads simultaneously request a token that is about to expire. The `OAuthTokenService` uses `ConcurrentHashMap.computeIfAbsent` with a `CompletableFuture` to ensure exactly **1** `performRefresh` call is made, regardless of concurrency.

```bash
./gradlew test --tests '*Task14TokenTest.resolveToken_singleflight_refreshCalledOnce'
```

Run all 113 tests:

```bash
./gradlew test
```

---

## Load Test Results

Load test script: [`k6/load-test.js`](k6/load-test.js)

Ramps to 500 VUs over 10s, sustains for 40s, ramps down. Mix: 70% cache-only, 30% hybrid.

| Query type | P50   | P95   |
|------------|-------|-------|
| Cache-only | 18ms  | 45ms  |
| Hybrid     | 280ms | 650ms |

**Error rate:** < 1%

**Bottleneck:** Single-JVM DuckDB file lock saturates at approximately 600 RPS under sustained load. Horizontal scaling would require distributing the DuckDB files or moving to a shared-state store.

To run the load test:

```bash
# Generate a token
./k6/generate-token.sh alice acme

# Run k6 (requires k6 installed: https://k6.io/docs/get-started/installation/)
export AUTH_TOKEN="<token from above>"
k6 run k6/load-test.js
```

---

## Architecture

See [docs/architecture-mapping.md](docs/architecture-mapping.md) for a module-by-module mapping to the spec diagram.

## Trade-offs

See [docs/trade-offs.md](docs/trade-offs.md) for the five key design trade-offs made during implementation.

---

## Key Features

- **Multi-source SQL**: Query Jira issues and GitHub PRs with a single SQL statement; the planner handles cross-source JOINs.
- **Hybrid freshness**: Per-query `include_latest_data` and `max_staleness_ms` control whether results come from cache (DuckDB), live APIs, or a merged combination.
- **Row-Level Security**: RLS predicates are compiled from a principal set and injected into SQL at the planner layer; enforced again at the DuckDB ACL layer.
- **Column-Level Security**: Sensitive columns (e.g., `reporter_email`) are masked (PARTIAL or REDACT) based on the user's principal set.
- **Envelope encryption**: Every sensitive column is AES-GCM encrypted with a per-row DEK wrapped under a tenant KEK stored in `data/kms/`.
- **Crypto-shred off-boarding**: `DELETE /admin/v1/tenant/{id}` destroys the KEK, making all tenant data permanently inaccessible before deleting the DuckDB file.
- **Rate limiting**: Hierarchical Resilience4j buckets (global 100/s, tenant 20/s, user 5/s) with circuit breakers per connector.
- **Singleflight token refresh**: Concurrent requests for an expiring OAuth token trigger exactly one `performRefresh` call.
- **Result cache**: Caffeine-backed, 5-minute TTL, keyed on `SHA-256(tenantId|userId|principalSet|aclSyncedAt|maskSet|sql)`.
- **Audit trail**: Every query decision (ALLOW/DENY) is recorded in `audit_event` (Postgres) with trace ID, SQL hash, and reason — never raw data.

---

## API Reference

### `POST /v1/query`

**Request:**

```json
{
  "sql": "SELECT * FROM jira_issues LIMIT 25",
  "include_latest_data": false,
  "max_staleness_ms": 60000,
  "timeout_ms": 2000
}
```

**Response:**

```json
{
  "columns": [{"name": "issue_key", "type": "VARCHAR"}, ...],
  "rows": [["PLAT-1", "Open", ...], ...],
  "metadata": {
    "trace_id": "uuid",
    "freshness_ms": 45000,
    "partial": false,
    "sources": [{"connector": "jira", "path": "CACHE", "freshness_ms": 45000}],
    "policy": {"rls_applied": true, "cls_applied": true, "rls_expression": "project_key IN ('PLAT','CORE')"},
    "join_strategy": null
  }
}
```

**Error codes:**

| HTTP | ErrorCode | When |
|------|-----------|------|
| 400 | UNSUPPORTED_SQL | Unknown table, aggregate, subquery, or OR predicate |
| 403 | ENTITLEMENT_DENIED | Masked column in predicate; tenant inactive |
| 429 | RATE_LIMIT_EXHAUSTED | Rate limit bucket empty |
| 504 | SOURCE_TIMEOUT | Live connector exceeded timeout |
| 503 | SOURCE_UNAVAILABLE | Connector circuit open |

### `GET /dev/token` (dev only)

```bash
curl 'http://localhost:8080/dev/token?userId=alice&tenantId=acme'
```

Returns a signed JWT valid for 1 hour. Only active when `usql.auth.mock-enabled=true`.

### `DELETE /admin/v1/tenant/{tenantId}`

```bash
curl -X DELETE http://localhost:8080/admin/v1/tenant/acme \
  -H "X-Admin-Key: test-admin-key"
```

Destroys KEK, marks tenant inactive, cancels jobs, deletes DuckDB file.
