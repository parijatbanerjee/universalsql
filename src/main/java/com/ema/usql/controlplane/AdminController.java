package com.ema.usql.controlplane;

import com.ema.usql.crypto.api.CryptoShredService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Admin control-plane controller for tenant lifecycle operations.
 *
 * <p>Authentication uses a simple API key header ({@code X-Admin-Key}) checked
 * against a configured value. This is appropriate for a prototype; production
 * would use full RBAC.
 *
 * <p>The admin key is set via {@code usql.admin.key} in application properties
 * (defaults to {@code test-admin-key} for development and tests).
 */
@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private final CryptoShredService cryptoShredService;
    private final JdbcTemplate jdbc;
    private final String adminKey;

    public AdminController(
            CryptoShredService cryptoShredService,
            JdbcTemplate jdbc,
            @Value("${usql.admin.key:test-admin-key}") String adminKey) {
        this.cryptoShredService = cryptoShredService;
        this.jdbc = jdbc;
        this.adminKey = adminKey;
    }

    /**
     * Crypto-shred and off-board a tenant.
     *
     * <ol>
     *   <li>Validate admin key header.</li>
     *   <li>Destroy the tenant's KEK (crypto-shred: DEKs become unreadable).</li>
     *   <li>Mark tenant inactive in Postgres.</li>
     *   <li>Cancel scheduled jobs for the tenant.</li>
     *   <li>Delete the tenant's DuckDB file (cleanup).</li>
     * </ol>
     *
     * @param tenantId  the tenant to shred
     * @param adminKeyHeader the value of the X-Admin-Key header
     * @return 200 on success, 403 if admin key is invalid
     */
    @DeleteMapping("/tenant/{tenantId}")
    public ResponseEntity<Map<String, String>> shredTenant(
            @PathVariable String tenantId,
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKeyHeader) {

        // 1. Validate admin key
        if (!adminKey.equals(adminKeyHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "FORBIDDEN", "message", "Invalid admin key"));
        }

        // 2. Crypto-shred: destroy the KEK so all DEKs are permanently inaccessible
        cryptoShredService.shred(tenantId);

        // 3. Mark tenant inactive in Postgres
        jdbc.update("UPDATE tenant SET status = 'inactive' WHERE tenant_id = ?", tenantId);

        // 4. Cancel scheduled jobs for the tenant
        jdbc.update("UPDATE job_state SET status = 'CANCELLED' WHERE tenant_id = ?", tenantId);

        // 5. Delete the tenant's DuckDB file (cleanup — data already undecryptable from step 2)
        try {
            Path duckDbFile = Path.of("data/tenants/" + tenantId + "/knowledge.duckdb");
            Files.deleteIfExists(duckDbFile);
            // Also delete the WAL file if present
            Files.deleteIfExists(Path.of("data/tenants/" + tenantId + "/knowledge.duckdb.wal"));
        } catch (IOException e) {
            // Log but don't fail — crypto-shred is already complete
            // The file being gone is just cleanup
        }

        return ResponseEntity.ok(Map.of("message", "Tenant shredded"));
    }
}
