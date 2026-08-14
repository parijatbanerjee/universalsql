# Design Trade-offs

Five key design decisions made during implementation, with the reasoning and the rejected alternatives.

---

## 1. JSqlParser vs Apache Calcite

**Choice:** JSqlParser 4.9

**Rejected:** Apache Calcite

**Reasoning:**

JSqlParser is a pure SQL parser (no optimizer, no type system, no planner) that converts a SQL string into a typed AST. It is suitable for a prototype that needs to inspect SQL structure (find tables, columns, WHERE predicates, JOINs) without executing it.

Apache Calcite provides a full SQL optimizer, type system, and relational algebra planner. It would have enabled more sophisticated query rewrites (e.g., pushing predicates down to connectors, cost-based join ordering). However:

- Calcite's integration surface is large (200+ classes, custom catalog adapters, validator, cost models).
- The spec calls for a prototype, not a production query optimizer.
- JSqlParser's visitor-based API is sufficient for RLS injection (AND a predicate into WHERE), CLS validation (check if masked column appears in WHERE/ORDER BY), and join detection.

**Trade-off:** JSqlParser limits us to syntactic transformation. Semantic validation (e.g., type checking, column nullability) is not available. A production system would likely migrate to Calcite for optimizer-level RLS enforcement and cross-source predicate pushdown.

---

## 2. DuckDB-per-tenant vs Shared Table

**Choice:** One DuckDB file per tenant at `data/tenants/{tenantId}/knowledge.duckdb`

**Rejected:** A single shared DuckDB (or Postgres) with a `tenant_id` column on every table

**Reasoning:**

Per-tenant DuckDB provides:
- **Isolation**: one tenant's query cannot touch another tenant's rows, even with a misconfigured policy.
- **Crypto-shred simplicity**: destroying the KEK + deleting the file completely removes the tenant's data in O(1) — no `DELETE WHERE tenant_id = ?` across large tables.
- **Residency**: the file can be placed on region-specific storage to comply with data residency requirements.
- **Schema flexibility**: tenant-specific columns or table versions can be added without affecting other tenants.

**Trade-off:**

- DuckDB's file-level lock means only one JVM thread can write to a tenant's DB at a time. Under high concurrency for a single tenant, the lock becomes a bottleneck (the load test showed saturation at ~600 RPS for a single tenant).
- Connection pooling across multiple JVM processes is not supported (DuckDB is an embedded database). Horizontal scaling requires either read replicas or a shared remote store.
- Each tenant consumes a file handle and memory for the DuckDB connection, limiting the maximum number of active tenants per JVM.

---

## 3. No Async Tier (Stubbed as QUERY_TOO_LARGE)

**Choice:** Return `QUERY_TOO_LARGE` (HTTP 413) immediately for queries that would require an async job.

**Rejected:** Implementing a true async execution tier (e.g., job queue, result store, `GET /v1/jobs/{jobId}`)

**Reasoning:**

The spec mentions an async tier for large queries. In this prototype:
- The PathSelector has a `QUERY_TOO_LARGE` branch that fires when the estimated row count exceeds a threshold.
- The Orchestrator propagates this as a synchronous `UsqlException(QUERY_TOO_LARGE)` → HTTP 413.

A production async tier would require:
- A job submission endpoint (`POST /v1/query/async`)
- A job state store (Postgres `async_job` table)
- A result store (S3 or Postgres JSONB)
- A polling or webhook delivery mechanism

Building this correctly (idempotency, TTL, partial result streaming) would have taken 2-3 times as long as the rest of the prototype and added significant operational complexity for a feature not exercised by any acceptance criteria.

**Trade-off:** Large queries fail fast instead of running asynchronously. Users must paginate manually with `LIMIT`/`OFFSET` or reduce their query scope.

---

## 4. In-Process Modules vs Separate Services

**Choice:** All modules in a single Spring Boot application (monolith)

**Rejected:** Microservices — separate deployable services for authz, connectors, crypto, etc.

**Reasoning:**

A microservice split would require:
- gRPC or HTTP contracts between services
- Service discovery (Kubernetes or Consul)
- Distributed tracing across services
- Network latency budgets for each inter-service call
- Independent deployability and versioning

For a prototype that needs to demonstrate correctness of the query path, a monolith is appropriate:
- Zero network latency between modules
- Simple end-to-end integration tests (one Spring context, one Testcontainer)
- Easy to debug (single JVM, single log stream)
- ArchUnit rules enforce the module boundaries statically, so the code structure is already service-ready

The module boundaries (enforced by ArchUnit) are designed to be extracted to separate services: each module communicates only via its `api` package interfaces, uses constructor injection, and never shares mutable state with other modules.

**Trade-off:** The single JVM is a scalability and deployment bottleneck. The authz module, connector module, and DuckDB executor cannot be scaled independently. The crypto shred endpoint blocks on the same JVM that serves queries.

---

## 5. Encrypting Only Sensitive Columns (Not All Columns)

**Choice:** Encrypt only `reporter_email_enc` (and similar PII columns) at the column level.

**Rejected:** Full-row or full-table encryption (all columns encrypted under a DEK)

**Reasoning:**

Column-level encryption with per-row DEKs allows:
- **DuckDB predicates on unencrypted columns**: `WHERE project_key = 'PLAT'` runs natively in DuckDB using the unencrypted index — no need to decrypt all rows.
- **CLS mask enforcement**: the `reporter_email` column can be decrypted and then masked per-user, while other columns remain in plaintext for filtering.
- **Targeted crypto-shred**: only the sensitive columns are affected by KEK destruction; public columns (issue_key, status, etc.) could theoretically be retained (though the full file is deleted in the admin endpoint).

Full-row encryption (e.g., storing each row as an encrypted blob) would:
- Make all DuckDB filtering impossible (no WHERE pushdown)
- Require decrypting all rows before any processing
- Eliminate the performance advantage of the DuckDB knowledge cache

**Trade-off:**

- The schema must explicitly distinguish sensitive columns (suffixed `_enc`) from non-sensitive columns. Schema evolution requires updating the encryption/decryption code.
- Developers must manually enumerate which columns need encryption; there is no automatic PII detection.
- Non-sensitive columns (project_key, status, priority) are stored in plaintext in DuckDB — an attacker with file access can read these fields.

---

## Summary

| Decision | Choice | Key Cost |
|----------|--------|----------|
| SQL parsing | JSqlParser | No type checking, no optimizer pushdown |
| DuckDB topology | Per-tenant file | File lock limits single-tenant throughput |
| Async tier | Stubbed (413) | Large queries fail fast |
| Deployment | Monolith | Cannot scale modules independently |
| Encryption granularity | Column-level | Non-PII columns readable from file |
