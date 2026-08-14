# Progress updates

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
