package com.ema.usql.api;

import com.ema.usql.shared.ResultColumn;

import java.util.List;

/**
 * Response returned to the API client after query execution.
 */
public record QueryResponse(
        List<ResultColumn> columns,
        List<List<Object>> rows,
        QueryMetadata metadata
) {
}
