# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Full specification: `docs/spec.md`. Progress log: `docs/progress.md`.
Read the spec section for the current task before implementing it. Do not work
from memory of the spec — it will degrade as context compacts.

## What this project is

A prototype universal SQL layer over enterprise SaaS apps (Jira, GitHub mocked).
It demonstrates a **hybrid query path**: a single SQL statement is planned,
decomposed into per-source fragments, executed against a materialized cache
and/or live sources depending on freshness requirements, then merged.

The prototype is a modular monolith whose modules map 1:1 to components in a
distributed production architecture. **Preserving that mapping is the point.**
Code that works but collapses module boundaries has failed.

## Request flow

```
HTTP → api (OIDC) → coordinator → authz (principal + RLS/CLS) → planner (parse → inject → physical plan)
                 ↓                                                         ↓
         coordinator.execution ──── CACHE path ──→ knowledgecache (DuckDB per tenant)
                 │                                                         │
                 └──────────────── LIVE path ───→ livequery → sourcegateway → connectors → sources
                 ↓
         result merge (DuckDB hash join, live-wins deduplication)
                 ↓
         audit → response
```

Path selection: ACL staleness is checked before data staleness and overrides it.

## Module map (`com.ema.usql.<module>`)

| Module | Subpackage | Key responsibility |
|---|---|---|
| `api` | `api/` | HTTP endpoints, OIDC RS256 validation, request envelope |
| `coordinator` | `coordinator/` | Request lifecycle, timeout budget, fragment dispatch |
| `coordinator.execution` | `coordinator/execution/` | Parallel fragment execution, embedded DuckDB, result merge |
| `planner` | `planner/` | SQL parse (JSqlParser) → logical → RLS/CLS inject → physical plan |
| `planner.catalog` | `planner/catalog/` | Table/column/capability descriptors per connector |
| `planner.stats` | `planner/stats/` | Cardinality estimates, source latency profiles |
| `authz` | `authz/` | Principal resolution, ACL lookup, RLS predicate + CLS mask generation |
| `authz.principals` | `authz/principals/` | Principal closure, resource ACLs, OAuth connection records |
| `crypto` | `crypto/` | AES-GCM envelope encryption; only module holding `SecretKey` |
| `knowledgecache` | `knowledgecache/` | Materialized store (DuckDB file per tenant), watermarks |
| `livequery` | `livequery/` | Executes live fragments via Source Gateway |
| `sourcegateway` | `sourcegateway/` | Rate limiting (Bucket4j), circuit breaking (Resilience4j), OAuth token resolution |
| `connectors` | `connectors/` | `jira` and `github` HTTP adapters; connector interface |
| `updates` | `updates/` | Webhook ingest, scheduled reconciliation, watermark advance |
| `controlplane` | `controlplane/` | Tenant config, adapter registry, admin API |
| `audit` | `audit/` | Durable access trail (denials included); never stores row values |
| `telemetry` | `telemetry/` | Micrometer + OpenTelemetry facade; all metrics/traces go through here |

Public interface per module lives in `<module>/api/`; implementations sit in the module root or subpackages.

## Hard invariants

Never violate these, even if a task appears to require it. If a task seems to,
stop and record the conflict in `docs/progress.md` instead.

1. **Module boundaries.** Cross-module calls go only through `<module>/api/`
   interfaces. Never import or reference another module's internal classes.
2. **Key material containment.** No class outside `crypto` and its two declared
   callers (`knowledgecache`, `authz.principals`) may reference `SecretKey` or
   raw key bytes. Everything else passes `key_ref` strings.
3. **Token containment.** No class outside `sourcegateway` may reference an
   OAuth token value. Plans, logs, spans, and DTOs carry `connection_ref` only.
4. **No secrets in telemetry.** Never log or attach to a span: row values,
   tokens, key material, or full SQL with literals. Log `sql_hash` instead.
5. **Enforcement at plan time.** RLS predicates are injected into the query plan
   and CLS masks rewrite the projection *before* data is fetched. Never
   post-filter results for entitlement. Post-filtering is a security bug here,
   not a style preference.
6. **Package structure is fixed.** The layout in spec §3 and §11 does not
   change. Do not rename, merge, relocate, or "simplify" packages.

## Task completion rules

- A task is complete only when every acceptance criterion passes as an
  **automated test**. Manual verification does not count.
- Never modify a previous task's test to make the current task pass. If an
  earlier test breaks, you introduced a regression — fix the code.
- Never mark a task complete with a red build or failing suite.
- Prefer small working increments. Do not refactor completed work unless a
  later task explicitly requires it.
- Record every deviation from the spec in `docs/progress.md` with the reason.

## Tech stack (pinned — do not substitute)

| Concern | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 3.3 |
| Build | Gradle Kotlin DSL |
| SQL parsing | JSqlParser 4.9 (NOT Calcite — documented Phase-2 upgrade) |
| Join / merge engine | DuckDB embedded via `duckdb_jdbc` |
| Materialized store | DuckDB file per tenant at `data/tenants/{tenant_id}/knowledge.duckdb` |
| Metadata / ACL / audit / job state | PostgreSQL 16 + Flyway |
| Result cache | Caffeine, in-process (behind a `ResultCache` interface) |
| Rate limiting | Bucket4j |
| Circuit breaking | Resilience4j |
| Metrics | Micrometer → Prometheus |
| Tracing | OpenTelemetry SDK → OTLP → Jaeger |
| Logging | SLF4J + Logback, JSON encoder |
| Mock sources | WireMock standalone (docker compose) |
| Integration tests | Testcontainers |
| Load test | k6 |

No Redis. No OpenSearch. No Kafka. No S3/Iceberg. Two data stores only:
Postgres and per-tenant DuckDB files.

## Conventions

- Root package `com.ema.usql`. One module = one subpackage (spec §3).
- Constructor injection only. No field `@Autowired`.
- Errors use the `ErrorCode` enum from spec §8.1. Never invent new codes;
  never throw raw `RuntimeException` across a module boundary.
- All telemetry goes through the `Telemetry` facade. Do not import Micrometer
  or OpenTelemetry directly outside the `telemetry` module.
- Use virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) for
  fragment fan-out. Do not add a reactive stack.
- Every DB access is tenant-scoped. There is no query without a `tenant_id`.
- Flyway migrations are append-only. Never edit an applied migration.

## Things that are easy to get wrong here

- **Freshness ordering.** In `PathSelector`, ACL staleness is checked *before*
  data staleness and overrides it. Stale permissions force a live re-check even
  when the caller asked for cached data. Fail closed on entitlements.
- **Masked columns in predicates.** A masked column appearing in WHERE,
  ORDER BY, GROUP BY, or a JOIN condition must be rejected with
  `ENTITLEMENT_DENIED / MASKED_COLUMN_IN_PREDICATE`. Allowing it lets a user
  reconstruct the value by binary search.
- **Cache keys.** Result cache keys must include tenant, resolved principal
  set, `acl_version`, and the applied mask set. Omitting any of these leaks
  data across users.
- **Selective column encryption.** Only sensitive columns are encrypted at
  rest; the rest stay plaintext so predicates can still push down. Encrypting
  everything breaks the latency target.
- **Live path has no DEK.** Live data never rests, so it is not encrypted at
  rest. Keys protect the OAuth *credential* on that path, not the payload. This
  is correct, not a gap.
- **Singleflight on token refresh.** Concurrent requests for an expiring token
  must trigger exactly one refresh. A race can permanently invalidate the
  refresh token with some providers.

## Commands

```bash
docker compose up -d          # postgres, jaeger, prometheus, wiremock x2
./gradlew build               # compile + test
./gradlew bootRun             # start the app
./gradlew test --tests '*PathSelectorTest'
k6 run k6/load-test.js
```

Assume `docker compose up -d` has been run. If a service is unreachable, note
it in `docs/progress.md` and use Testcontainers for that task's tests.
