package com.ema.usql.connectors.api;

import java.util.List;

/**
 * A query to be executed against a specific connector.
 */
public record SourceQuery(
        String connector,
        String sql,
        List<Object> params,
        long timeoutMs
) {
}
