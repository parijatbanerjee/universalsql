plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.ema"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.8.0")
    }
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL driver
    runtimeOnly("org.postgresql:postgresql")

    // DuckDB
    implementation("org.duckdb:duckdb_jdbc:1.1.3")

    // Telemetry - Micrometer / Prometheus
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    // OpenTelemetry
    implementation("io.opentelemetry:opentelemetry-api:1.40.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.40.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.40.0")
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")

    // Logback JSON
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // JSqlParser
    implementation("com.github.jsqlparser:jsqlparser:4.9")

    // Rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Circuit breaker
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    // Caffeine cache
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // JWT
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    // ArchUnit
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "universalsql.jar"
}
