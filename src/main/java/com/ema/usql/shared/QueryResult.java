package com.ema.usql.shared;

import java.util.List;
import java.util.Map;

/**
 * The merged result returned to the API layer after all fragments complete.
 */
public record QueryResult(
        List<ResultColumn> columns,
        List<List<Object>> rows,
        Map<String, Object> metadata
) {
}
