package com.ema.usql.livequery;

import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.connectors.api.SourceQuery;
import com.ema.usql.livequery.api.LiveQueryService;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.Fragment;
import com.ema.usql.shared.QueryResult;
import com.ema.usql.shared.ResultColumn;
import com.ema.usql.shared.TenantContext;
import com.ema.usql.shared.UsqlException;
import com.ema.usql.sourcegateway.SourceGatewayImpl;
import com.ema.usql.sourcegateway.api.SourceGateway;
import com.ema.usql.telemetry.api.Telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes LIVE fragments directly against source connectors via the SourceGateway.
 *
 * <p>freshness_ms is always 0 for live results.
 * Each call is bounded by {@link Fragment#timeoutMs()}.
 */
public class LiveQueryEngineImpl implements LiveQueryService {

    private final SourceGateway sourceGateway;
    private final Telemetry telemetry;

    public LiveQueryEngineImpl(SourceGateway sourceGateway, Telemetry telemetry) {
        this.sourceGateway = sourceGateway;
        this.telemetry = telemetry;
    }

    @Override
    public QueryResult execute(Fragment fragment, TenantContext ctx) {
        String connectorId = fragment.connector();
        long timeoutMs = fragment.timeoutMs() > 0 ? fragment.timeoutMs() : 30_000L;

        try (var span = telemetry.span("live.query",
                Map.of("connector", connectorId, "tenant", ctx.tenantId()))) {

            // Build SourceQuery from the fragment; pass inListFilter as params if present
            List<Object> params = new ArrayList<>();
            if (fragment.inListFilter() != null && !fragment.inListFilter().isEmpty()) {
                // Encode as "issue_keys=KEY1,KEY2,..." param string for GithubConnector
                String joined = String.join(",", fragment.inListFilter());
                params.add("issue_keys=" + joined);
            }

            SourceQuery sourceQuery = new SourceQuery(
                    connectorId,
                    fragment.sql(),
                    params,
                    timeoutMs
            );

            // Execute with timeout using a virtual thread
            CompletableFuture<List<ConnectorRecord>> future = CompletableFuture.supplyAsync(
                    () -> {
                        if (sourceGateway instanceof SourceGatewayImpl impl) {
                            return impl.execute(fragment.connectionRef(), sourceQuery, ctx);
                        }
                        return sourceGateway.execute(fragment.connectionRef(), sourceQuery);
                    },
                    Executors.newVirtualThreadPerTaskExecutor()
            );

            List<ConnectorRecord> records;
            try {
                records = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new UsqlException(ErrorCode.SOURCE_TIMEOUT,
                        "Live query timed out after " + timeoutMs + "ms for connector: " + connectorId);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof UsqlException ue) throw ue;
                throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                        "Live query failed for connector: " + connectorId, cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                        "Live query interrupted for connector: " + connectorId, e);
            }

            // Map ConnectorRecord list → QueryResult
            return mapToQueryResult(records);
        }
    }

    // -------------------------------------------------------------------------
    // Internal mapping
    // -------------------------------------------------------------------------

    private QueryResult mapToQueryResult(List<ConnectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return new QueryResult(List.of(), List.of(), Map.of("freshness_ms", 0L));
        }

        // Derive columns from the first record's field keys
        List<String> columnNames = new ArrayList<>(records.get(0).fields().keySet());
        List<ResultColumn> columns = columnNames.stream()
                .map(name -> new ResultColumn(name, "STRING"))
                .toList();

        // Map records to rows
        List<List<Object>> rows = new ArrayList<>();
        for (ConnectorRecord record : records) {
            List<Object> row = new ArrayList<>();
            for (String col : columnNames) {
                row.add(record.fields().get(col));
            }
            rows.add(row);
        }

        // freshness_ms = 0 for live results (just fetched)
        return new QueryResult(columns, rows, Map.of("freshness_ms", 0L));
    }
}
