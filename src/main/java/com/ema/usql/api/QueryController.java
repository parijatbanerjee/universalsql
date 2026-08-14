package com.ema.usql.api;

import com.ema.usql.coordinator.Orchestrator;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.shared.UsqlException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for the /v1/query endpoint.
 * Accepts a {@link QueryRequest}, delegates to {@link Orchestrator}, and returns a {@link QueryResponse}.
 */
@RestController
@RequestMapping("/v1")
public class QueryController {

    private final Orchestrator orchestrator;

    public QueryController(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Execute a SQL query.
     * Extracts tenant context from the JWT (or falls back to defaults for testing).
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(
            @RequestBody QueryRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TenantContext ctx = extractTenantContext(jwt);
        QueryResponse response = orchestrator.execute(request, ctx);
        return ResponseEntity.ok(response);
    }

    /**
     * Map UsqlException to appropriate HTTP status codes.
     */
    @ExceptionHandler(UsqlException.class)
    public ResponseEntity<Map<String, String>> handleUsqlException(UsqlException ex) {
        HttpStatus status = mapErrorCodeToStatus(ex.getErrorCode());
        return ResponseEntity.status(status)
                .body(Map.of("error", ex.getErrorCode().name(), "message", ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private TenantContext extractTenantContext(Jwt jwt) {
        if (jwt == null) {
            // Fallback for testing without JWT
            return new TenantContext("acme", "anonymous", "acme-kek-1");
        }
        String userId = jwt.getSubject();
        String tenantId = jwt.getClaimAsString("tenant_id");
        if (tenantId == null) {
            tenantId = "acme";
        }
        return new TenantContext(tenantId, userId != null ? userId : "anonymous", tenantId + "-kek-1");
    }

    private HttpStatus mapErrorCodeToStatus(ErrorCode code) {
        return switch (code) {
            case UNSUPPORTED_SQL -> HttpStatus.BAD_REQUEST;
            case ENTITLEMENT_DENIED -> HttpStatus.FORBIDDEN;
            case RATE_LIMIT_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case STALE_DATA -> HttpStatus.CONFLICT;
            case SOURCE_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case SOURCE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONNECTION_REAUTH_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case QUERY_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
        };
    }
}
