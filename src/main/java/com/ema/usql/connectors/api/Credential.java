package com.ema.usql.connectors.api;

/**
 * An opaque reference to an OAuth connection record.
 * Deliberately contains NO token field — the token is resolved only inside sourcegateway.
 */
public record Credential(String connectionRef) {
}
