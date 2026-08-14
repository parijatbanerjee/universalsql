package com.ema.usql.coordinator.execution;

import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.ResultColumn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges two QueryResult objects (cache + live) for the same table into one.
 *
 * <p>Strategy: live row wins over cached row on same primary key.
 * Primary key is the first column (issue_key for jira_issues, pr_number for github_prs).
 *
 * <p>Freshness: max(cacheFreshness, liveFreshness).
 * Since live freshness is 0ms, the aggregate is cacheFreshness (the stalest source).
 */
@Service
public class ResultMerger {

    /**
     * Merge cache and live QueryResult objects using a Map-based deduplication strategy.
     * Live rows overwrite cache rows for the same primary key.
     *
     * @param cacheResult    result from the DuckDB knowledge cache
     * @param liveResult     result from the live connector
     * @param cacheFreshnessMs age of the cache data in milliseconds
     * @return merged QueryResult with live rows winning on collision
     */
    public QueryResult merge(QueryResult cacheResult, QueryResult liveResult, long cacheFreshnessMs) {
        // Use cache columns as schema baseline (live may have fewer or different column order)
        List<ResultColumn> columns = mergeColumns(cacheResult, liveResult);

        // Build column index maps for both results
        List<String> cacheColNames = columnNames(cacheResult.columns());
        List<String> liveColNames = columnNames(liveResult.columns());
        List<String> mergedColNames = columnNames(columns);

        // Step 1: Build map from pk → row using cache rows
        // Preserve insertion order so cache rows appear first (before live-only rows)
        Map<String, List<Object>> pkMap = new LinkedHashMap<>();

        for (List<Object> row : cacheResult.rows()) {
            String pk = extractPk(row, cacheColNames, mergedColNames);
            List<Object> paddedRow = padRow(row, cacheColNames, mergedColNames);
            pkMap.put(pk, paddedRow);
        }

        // Step 2: For each live row, overwrite cache entry with same pk
        for (List<Object> row : liveResult.rows()) {
            String pk = extractPk(row, liveColNames, mergedColNames);
            List<Object> paddedRow = padRow(row, liveColNames, mergedColNames);
            pkMap.put(pk, paddedRow);
        }

        // Step 3: Convert map back to list
        List<List<Object>> mergedRows = new ArrayList<>(pkMap.values());

        // Aggregate freshness: max(cacheFreshnessMs, 0) = cacheFreshnessMs
        long aggregateFreshnessMs = cacheFreshnessMs;

        return new QueryResult(columns, mergedRows,
                Map.of("freshness_ms", aggregateFreshnessMs));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Extract the primary key value from a row as a String.
     * The primary key is the first column of the merged schema.
     */
    private String extractPk(List<Object> row, List<String> rowColNames, List<String> mergedColNames) {
        if (mergedColNames.isEmpty()) {
            return String.valueOf(System.identityHashCode(row));
        }
        // The PK is the first column of the merged schema
        String pkCol = mergedColNames.get(0);
        int idx = rowColNames.indexOf(pkCol);
        if (idx >= 0 && idx < row.size()) {
            Object val = row.get(idx);
            return val == null ? "__null__" : val.toString();
        }
        // Fallback: use the first column of the row's own schema
        if (!row.isEmpty()) {
            Object val = row.get(0);
            return val == null ? "__null__" : val.toString();
        }
        return String.valueOf(System.identityHashCode(row));
    }

    /**
     * Reorder/pad a row to match the merged column order.
     * Columns not present in the source row are filled with null.
     */
    private List<Object> padRow(List<Object> row, List<String> rowColNames, List<String> mergedColNames) {
        if (rowColNames.equals(mergedColNames)) {
            return new ArrayList<>(row);
        }
        List<Object> padded = new ArrayList<>(mergedColNames.size());
        for (String col : mergedColNames) {
            int idx = rowColNames.indexOf(col);
            if (idx >= 0 && idx < row.size()) {
                padded.add(row.get(idx));
            } else {
                padded.add(null);
            }
        }
        return padded;
    }

    /**
     * Merge column definitions from cache and live results.
     * Cache columns take precedence; live-only columns are appended.
     */
    private List<ResultColumn> mergeColumns(QueryResult cacheResult, QueryResult liveResult) {
        if (cacheResult.columns().isEmpty()) {
            return liveResult.columns();
        }
        if (liveResult.columns().isEmpty()) {
            return cacheResult.columns();
        }

        List<ResultColumn> merged = new ArrayList<>(cacheResult.columns());
        List<String> cacheNames = columnNames(cacheResult.columns());

        for (ResultColumn col : liveResult.columns()) {
            if (!cacheNames.contains(col.name())) {
                merged.add(col);
            }
        }
        return merged;
    }

    private List<String> columnNames(List<ResultColumn> columns) {
        return columns.stream().map(ResultColumn::name).toList();
    }
}
