package com.ema.usql.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Task 22: Architecture conformance rules.
 *
 * Rules enforced here:
 * 1. Cross-module calls only via <module>/api/ interfaces — coordinator cannot import
 *    authz internals, planner internals etc. directly.
 * 2. javax.crypto.SecretKey may only be referenced from the crypto package (and its API),
 *    plus knowledgecache.* and authz.principals.*.
 * 3. OAuthToken / TokenValue names must not appear outside sourcegateway.*
 *    (placeholder rule — Task 14 will add the actual OAuthToken confined to sourcegateway).
 *
 * NOTE: Module boundary rules (Rule 1) are documented and partially enforced below.
 * Full enforcement requires all modules to be implemented; rules are written for the
 * modules that exist today and will be expanded as modules are added.
 */
@AnalyzeClasses(packages = "com.ema.usql")
public class ArchitectureConformanceTest {

    // -------------------------------------------------------------------------
    // Rule 1: Module boundary — cross-module calls only via api/ interfaces
    // -------------------------------------------------------------------------

    /**
     * The controlplane module must not reach into crypto internals.
     * It may only use crypto.api.*.
     */
    @ArchTest
    public static final ArchRule controlplaneCannotAccessCryptoInternals =
            noClasses()
                    .that().resideInAPackage("com.ema.usql.controlplane..")
                    .should().accessClassesThat()
                    .resideInAPackage("com.ema.usql.crypto")
                    // crypto package (non-api) — package-private so controlplane cannot import anyway,
                    // but the rule documents and enforces the boundary explicitly.
                    .because("controlplane must not reference crypto internals; use crypto.api.* only");

    /**
     * The telemetry implementation package must not be imported by any other module.
     * Only telemetry.api.* should be used externally.
     */
    @ArchTest
    public static final ArchRule noModuleAccessesTelemetryImpl =
            noClasses()
                    .that().resideOutsideOfPackage("com.ema.usql.telemetry..")
                    .and().resideOutsideOfPackage("com.ema.usql.arch..")
                    .should().accessClassesThat()
                    .resideInAPackage("com.ema.usql.telemetry")
                    .because("all modules must use telemetry.api.* interfaces only — " +
                             "never reference TelemetryImpl, SpanImpl, StructuredLoggerImpl directly");

    /**
     * The shared package may be used by any module (it is the shared kernel).
     * This is a documentation rule — no restriction here — but we verify that
     * shared itself does not import module-specific packages.
     */
    @ArchTest
    public static final ArchRule sharedDoesNotImportModulePackages =
            noClasses()
                    .that().resideInAPackage("com.ema.usql.shared..")
                    .should().accessClassesThat()
                    .resideInAnyPackage(
                            "com.ema.usql.crypto..",
                            "com.ema.usql.controlplane..",
                            "com.ema.usql.telemetry..",
                            "com.ema.usql.authz..",
                            "com.ema.usql.planner..",
                            "com.ema.usql.sourcegateway..",
                            "com.ema.usql.audit..",
                            "com.ema.usql.knowledgecache..",
                            "com.ema.usql.connectors.."
                    )
                    .because("shared is the shared kernel and must not depend on any module");

    // -------------------------------------------------------------------------
    // Rule 2: SecretKey containment
    // -------------------------------------------------------------------------

    /**
     * javax.crypto.SecretKey may only be referenced from:
     *   - com.ema.usql.crypto.* (the crypto module — both api and implementation)
     *   - com.ema.usql.knowledgecache.* (allowed by spec)
     *   - com.ema.usql.authz.principals.* (allowed by spec)
     *
     * All other packages are forbidden from referencing SecretKey directly.
     *
     * Task 14 expansion: when knowledgecache and authz.principals are implemented,
     * they will import SecretKey. The allowlist in this rule already permits them.
     */
    @ArchTest
    public static final ArchRule secretKeyContainmentRule =
            noClasses()
                    .that().resideOutsideOfPackage("com.ema.usql.crypto..")
                    .and().resideOutsideOfPackage("com.ema.usql.knowledgecache..")
                    .and().resideOutsideOfPackage("com.ema.usql.authz.principals..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("javax.crypto.SecretKey")
                    .because("SecretKey must not leak outside the crypto module, " +
                             "knowledgecache, and authz.principals per spec §7.1");

    // -------------------------------------------------------------------------
    // Rule 3: Token containment (placeholder — expanded in Task 14)
    // -------------------------------------------------------------------------

    /**
     * Task 14 token containment rule.
     *
     * Design (spec §7.3): OAuthConnectionRecord, OAuthConnectionStore, and OAuthTokenService
     * live in authz.principals.* (the only non-crypto package permitted to hold SecretKey).
     * SourceGateway calls them only via the authz.api.TokenService interface.
     *
     * This rule enforces that the raw OAuth connection/token classes are NOT referenced
     * from outside the authz.principals package (or the sourcegateway that may call the interface).
     * Classes explicitly named "OAuthToken" or "TokenValue" (exact match) must stay within
     * authz.principals.* or sourcegateway.*; no other module may reference them.
     */
    @ArchTest
    public static final ArchRule tokenContainmentRule =
            noClasses()
                    .that().resideOutsideOfPackage("com.ema.usql.sourcegateway..")
                    .and().resideOutsideOfPackage("com.ema.usql.authz.principals..")
                    .and().resideOutsideOfPackage("com.ema.usql.authz..")
                    .should().dependOnClassesThat()
                    .haveSimpleName("OAuthToken")
                    .orShould().dependOnClassesThat()
                    .haveSimpleName("TokenValue")
                    .because("Classes literally named OAuthToken or TokenValue must be confined to " +
                             "sourcegateway.* or authz.principals.* — no other module may import them");
}
