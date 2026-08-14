package com.ema.usql.authz.api;

/**
 * Resolves a plaintext OAuth token from a connection reference.
 * The implementation lives in authz.principals (the only package permitted to call crypto).
 * SourceGateway injects this interface — it never sees SecretKey or plaintext token directly.
 */
public interface TokenService {

    /**
     * Resolve the current OAuth access token for the given connection reference.
     * If the token is near expiry, exactly one refresh is performed (singleflight guarantee).
     *
     * <p>SECURITY: the returned string is the plaintext bearer token.
     * Callers must not log it, store it in any Fragment field, or attach it to span attributes.
     *
     * @param connectionRef opaque connection identifier
     * @return plaintext bearer token string
     * @throws com.ema.usql.shared.UsqlException with CONNECTION_REAUTH_REQUIRED if lookup fails
     */
    String resolveToken(String connectionRef);
}
