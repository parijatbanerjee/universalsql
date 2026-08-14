package com.ema.usql.connectors.api;

import java.util.List;

/**
 * Describes what filter and paging capabilities a connector supports.
 * The planner uses this to decide which predicates can be pushed down.
 */
public record CapabilityDescriptor(
        String connector,
        List<String> supportedFilters,
        int maxPageSize
) {
}
