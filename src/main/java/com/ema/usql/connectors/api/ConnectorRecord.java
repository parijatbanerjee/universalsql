package com.ema.usql.connectors.api;

import java.util.Map;

/**
 * A single record returned from a connector fetch.
 */
public record ConnectorRecord(Map<String, Object> fields) {
}
