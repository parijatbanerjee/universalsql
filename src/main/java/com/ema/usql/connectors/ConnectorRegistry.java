package com.ema.usql.connectors;

import com.ema.usql.connectors.api.ConnectorSdk;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Registry that maps connector names to their ConnectorSdk implementations.
 * Spring injects all ConnectorSdk beans by name via Map injection.
 */
@Service
public class ConnectorRegistry {

    private final Map<String, ConnectorSdk> connectors;

    public ConnectorRegistry(Map<String, ConnectorSdk> connectors) {
        this.connectors = Map.copyOf(connectors);
    }

    /**
     * Retrieve the ConnectorSdk for the given connector name.
     *
     * @throws UsqlException with SOURCE_UNAVAILABLE if no connector is registered for the given id
     */
    public ConnectorSdk getConnector(String connectorId) {
        ConnectorSdk sdk = connectors.get(connectorId);
        if (sdk == null) {
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "No connector registered for id: " + connectorId);
        }
        return sdk;
    }

    /**
     * Return the names of all registered connectors.
     */
    public java.util.Set<String> connectorIds() {
        return connectors.keySet();
    }
}
