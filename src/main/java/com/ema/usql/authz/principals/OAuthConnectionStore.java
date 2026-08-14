package com.ema.usql.authz.principals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Loads and updates OAuth connection records from the {@code oauth_connection} Postgres table.
 * This is the only class (besides OAuthTokenService) allowed to access the raw wrapped bytes.
 */
@Service
public class OAuthConnectionStore {

    private final JdbcTemplate jdbcTemplate;

    public OAuthConnectionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Look up an OAuth connection record by its primary key (connection_ref).
     */
    public Optional<OAuthConnectionRecord> findByRef(String connectionRef) {
        List<OAuthConnectionRecord> results = jdbcTemplate.query(
                """
                SELECT tenant_id, user_id, connector_id, connection_ref,
                       wrapped_token, wrapped_dek, expires_at, status
                FROM oauth_connection
                WHERE connection_ref = ?
                """,
                (rs, rowNum) -> mapRow(rs),
                connectionRef
        );
        return results.stream().findFirst();
    }

    /**
     * Update the wrapped token and DEK after a successful OAuth token refresh.
     */
    public void updateToken(String connectionRef, byte[] wrappedToken, byte[] wrappedDek, Instant expiresAt) {
        jdbcTemplate.update(
                """
                UPDATE oauth_connection
                SET wrapped_token = ?, wrapped_dek = ?, expires_at = ?
                WHERE connection_ref = ?
                """,
                wrappedToken,
                wrappedDek,
                Timestamp.from(expiresAt),
                connectionRef
        );
    }

    private OAuthConnectionRecord mapRow(ResultSet rs) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        String userId = rs.getString("user_id");
        String connectorId = rs.getString("connector_id");
        String connectionRef = rs.getString("connection_ref");
        byte[] wrappedToken = rs.getBytes("wrapped_token");
        byte[] wrappedDek = rs.getBytes("wrapped_dek");
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        String status = rs.getString("status");

        return new OAuthConnectionRecord(
                tenantId,
                userId,
                connectorId,
                connectionRef,
                wrappedToken != null ? wrappedToken : new byte[0],
                wrappedDek != null ? wrappedDek : new byte[0],
                expiresAt != null ? expiresAt.toInstant() : Instant.MAX,
                status
        );
    }
}
