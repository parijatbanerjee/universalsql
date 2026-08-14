package com.ema.usql.connectors.api;

import java.util.List;

/**
 * Interface that every source connector must implement.
 * Connectors fetch data from a single SaaS source given a query and credentials.
 */
public interface ConnectorSdk {

    /**
     * Execute a source query and return the raw records.
     */
    List<ConnectorRecord> fetch(SourceQuery query, Credential credential);

    /**
     * Return the capability descriptor for this connector.
     */
    CapabilityDescriptor getCapabilities();
}
