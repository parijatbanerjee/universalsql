# Progress updates

## Task 3 — Postgres schema and control plane
**Status:** Complete
**Commit:** `Task 3: Postgres schema and control plane`
**Date:** 2026-08-14

Files touched:
- `src/main/resources/db/migration/V2__seed.sql` — seeds acme tenant (kek_id=acme-kek-1), alice/bob principal closures (alice→PLAT+CORE, bob→CORE), jira/github source_catalog entries, demo RLS/CLS policy
- `src/main/java/com/ema/usql/controlplane/TenantConfig.java` — record with {tenantId, name, kekId, status, config: Map}
- `src/main/java/com/ema/usql/controlplane/TenantConfigService.java` — @Service reads tenant + tenant_config via JdbcTemplate; constructor injection; throws UsqlException(ENTITLEMENT_DENIED) for missing tenant
- `src/main/java/com/ema/usql/controlplane/SourceCatalogEntry.java` — record with {connectorId, tableName, columns, capabilities}
- `src/main/java/com/ema/usql/controlplane/SourceCatalogRegistry.java` — @Service reads source_catalog via JdbcTemplate; throws UsqlException(SOURCE_UNAVAILABLE) for missing connector
- `src/test/java/com/ema/usql/controlplane/Task3SchemaTest.java` — @Testcontainers PostgreSQL; 5 tests

Acceptance criteria:
- [PASS] All Flyway migrations (V1+V2) apply cleanly — verified via information_schema table count
- [PASS] TenantConfigService and SourceCatalogRegistry exist and are Spring beans
- [PASS] acme tenant exists with kek_id='acme-kek-1'
- [PASS] alice's principal_closure includes project:PLAT and project:CORE
- [PASS] bob's principal_closure includes only project:CORE

`./gradlew build` GREEN.

---

## Task 4 — Crypto module
**Status:** Complete
**Commit:** `Task 4: Crypto module`
**Date:** 2026-08-14

Files touched:
- `src/main/java/com/ema/usql/crypto/EnvelopeCipher.java` — package-private AES/GCM/NoPadding; EncryptionContext as AAD; wire format [12-byte IV][ciphertext+tag]
- `src/main/java/com/ema/usql/crypto/LocalKmsModule.java` — package-private KmsModule impl; KEKs stored in data/kms/{tenant}.key as Base64; auto-provisions KEK on first use; destroyKek deletes key file
- `src/main/java/com/ema/usql/crypto/CryptoConfig.java` — @Configuration; exposes KmsModule bean; creates data/kms/ directory on startup
- `src/test/java/com/ema/usql/crypto/Task4CryptoTest.java` — 4 unit tests; no Spring context (manual wiring + @TempDir)

Acceptance criteria:
- [PASS] Round-trip encrypt/decrypt passes (Test 1)
- [PASS] Mismatched EncryptionContext → AEADBadTagException / BadPaddingException (Test 2)
- [PASS] After destroyKek, previously wrapped DEKs fail to unwrap with UsqlException(ENTITLEMENT_DENIED) because the auto-provisioned replacement KEK produces a tag mismatch (Test 3)
- [PASS] Mismatched purpose in unwrapDek → UsqlException(ENTITLEMENT_DENIED) (Test 4)

Deviations:
- ArchUnit SecretKey containment rule (Task 22) already forbids non-crypto packages from referencing SecretKey; EnvelopeCipher and LocalKmsModule are package-private so the API surface is only KmsModule (which lives in crypto.api and is the permitted reference point).
- Test 3 relies on auto-provisioning: destroyKek deletes the key file, but loadKek on the next call generates a fresh (different) KEK. Decrypting an old wrapped DEK with the new KEK produces AEADBadTagException — caught and re-thrown as UsqlException(ENTITLEMENT_DENIED). This faithfully implements crypto shredding.

`./gradlew build` GREEN.

---

## Task 22 — ArchUnit conformance rules (moved after Task 4)
**Status:** Complete
**Commit:** `Task 22: ArchUnit conformance rules (moved after Task 4)`
**Date:** 2026-08-14

Files touched:
- `src/test/java/com/ema/usql/arch/ArchitectureConformanceTest.java` — @AnalyzeClasses; 5 @ArchTest rules

Rules implemented:
1. `controlplaneCannotAccessCryptoInternals` — controlplane.* cannot access crypto.* (non-api internals)
2. `noModuleAccessesTelemetryImpl` — no module outside telemetry.* may reference TelemetryImpl/SpanImpl/StructuredLoggerImpl
3. `sharedDoesNotImportModulePackages` — shared.* must not depend on any module package
4. `secretKeyContainmentRule` — javax.crypto.SecretKey only referenceable from crypto.*, knowledgecache.*, authz.principals.*
5. `tokenContainmentRule` — OAuthToken/TokenValue class names forbidden outside sourcegateway.* (placeholder; trivially passes now; will auto-enforce when Task 14 adds these classes)

Acceptance criteria:
- [PASS] All ArchUnit rules pass on current codebase
- [PASS] `./gradlew build` GREEN — no violations detected

`./gradlew build` GREEN.

---

## Task 1b — Cross-module interfaces and shared DTOs
**Status:** Complete  
**Commit:** `Task 1b: Cross-module interfaces and shared DTOs`  
**Date:** 2026-08-14

Created all shared DTOs in `com.ema.usql.shared`:
- `TenantContext`, `ErrorCode` (8 codes), `UsqlException`, `Fragment`, `PhysicalPlan`, `QueryResult`, `ResultColumn`, `JoinStrategy`, `QueryPath`

Created module API interfaces:
- `telemetry/api/`: `Telemetry`, `Span`, `StructuredLogger`
- `crypto/api/`: `KmsModule`, `WrappedDek`, `EncryptionContext`
- `connectors/api/`: `ConnectorSdk`, `SourceQuery`, `Credential`, `ConnectorRecord`, `CapabilityDescriptor`
- `authz/api/`: `AuthzService`, `AuthzContext`, `RlsPredicate`, `ClsMaskSet`
- `knowledgecache/api/`: `KnowledgeCacheService`, `Watermark`
- `sourcegateway/api/`: `SourceGateway`, `RateLimitStatus`
- `audit/api/`: `AuditService`, `AuditEvent`

Created planner types in `com.ema.usql.planner`:
- `FreshnessHint`, `RateLimitBudget`, `AclFreshness`

`./gradlew compileJava` passes with no errors.

---

## Task 1 — Project scaffold and compose stack
**Status:** Complete  
**Commit:** `Task 1: Project scaffold and compose stack`  
**Date:** 2026-08-14

Created:
- `build.gradle.kts` with all pinned dependencies (Spring Boot 3.3.5, DuckDB 1.1.3, OTel via BOM, Bucket4j, Resilience4j, Caffeine, Testcontainers, ArchUnit, NimbusJWT)
- `settings.gradle.kts`
- Gradle wrapper (8.8) — downloaded manually since `gradle` CLI is not available in the environment
- `docker-compose.yml` — postgres:16, jaeger 1.60, prometheus, wiremock-jira:8081, wiremock-github:8082
- `src/main/resources/application.yml` — datasource, flyway, actuator, OTel
- `src/main/resources/db/migration/V1__baseline.sql` — full schema per spec §4.1
- `src/main/java/com/ema/usql/api/SecurityConfig.java` — permits /actuator/health; provides fallback JwtDecoder for offline testing
- `src/main/resources/logback-spring.xml` — JSON encoder (logstash) for production; plain text for test profile
- `ApplicationStartupTest` — @SpringBootTest + @Testcontainers PostgreSQLContainer; verifies /actuator/health returns 200 and Flyway migrates

**Deviations:**
- OpenTelemetry versions are managed by the `opentelemetry-instrumentation-bom:2.8.0` BOM rather than pinned individually (1.40.0). Using explicit 1.40.0 caused `NoClassDefFoundError: InstrumentationUtil` because the instrumentation starter required 1.42.1 internally. BOM-managed versions satisfy all transitive requirements.
- `DOCKER_HOST=unix:///var/run/docker.sock` is set as a Gradle test environment variable to support Docker Desktop on macOS.

`./gradlew build` passes GREEN.

---

## Task 2 — Telemetry module
**Status:** Complete  
**Commit:** `Task 2: Telemetry module`  
**Date:** 2026-08-14

Implemented:
- `TelemetryImpl` — Micrometer MeterRegistry for counter/timer/gauge; OpenTelemetry SDK Tracer for span()
- `SpanImpl` — wraps OTel Span + Scope; AutoCloseable; exposes recordException and setAttribute
- `StructuredLoggerImpl` — SLF4J + logstash-logback StructuredArguments; injects trace_id, span_id, tenant_id, user_id from MDC into every log entry
- `TelemetryConfig` — @Configuration; exposes `Telemetry` bean via constructor injection

Tests (`TelemetryTest` — 9 tests, all green):
- `spanCanBeCreatedAndClosed` — verifies span is exported to InMemorySpanExporter after close
- `spanAttributesAreSet` — verifies attributes passed at span creation appear in SpanData
- `counterAppearsInMeterRegistry` — verifies counter is registered and count == 1
- `counterIncrementAccumulates` — verifies three increments sum to 3.0
- `timerAppearsInMeterRegistry` — verifies timer count and total duration
- `gaugeAppearsInMeterRegistry` — verifies live supplier is polled
- `structuredLoggerIsCreated` — exercises all log levels; verifies no exception
- `logLineContainsTraceIdFromMdc` — verifies MDC round-trip (encoder reads MDC automatically)
- `spanRecordsException` — verifies exception event appears in SpanData
- `spanSetAttributeAfterCreation` — verifies late setAttribute() works
- `syntheticSpanAppearsInJaeger` — @Disabled; documents manual Jaeger verification steps

`./gradlew build` passes GREEN.
