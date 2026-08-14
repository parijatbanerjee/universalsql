package com.ema.usql.authz.principals;

import java.time.Instant;

/**
 * Represents a row from the oauth_connection table.
 * wrappedToken and wrappedDek are opaque byte arrays — never exposed outside authz.principals.
 */
public record OAuthConnectionRecord(
        String tenantId,
        String userId,
        String connectorId,
        String connectionRef,
        byte[] wrappedToken,
        byte[] wrappedDek,
        Instant expiresAt,
        String status
) {
}
