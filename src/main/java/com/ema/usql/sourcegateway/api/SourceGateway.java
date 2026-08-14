package com.ema.usql.sourcegateway.api;

import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.connectors.api.SourceQuery;

import java.util.List;

/**
 * Source gateway: rate-limited, circuit-broken execution of live connector queries.
 * Only this module may resolve OAuth token values from connection_ref.
 */
public interface SourceGateway {

    /**
     * Execute a live source query using the given connection reference.
     * The connectionRef is opaque to all callers; only this module resolves it to a token.
     */
    List<ConnectorRecord> execute(String connectionRef, SourceQuery query);

    /**
     * Return the current rate limit status for a connector/tenant pair.
     */
    RateLimitStatus getRateLimitStatus(String connector, String tenantId);
}
