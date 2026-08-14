package com.ema.usql.connectors;

import com.ema.usql.connectors.api.CapabilityDescriptor;
import com.ema.usql.connectors.api.ConnectorRecord;
import com.ema.usql.connectors.api.ConnectorSdk;
import com.ema.usql.connectors.api.Credential;
import com.ema.usql.connectors.api.SourceQuery;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import com.ema.usql.telemetry.api.Telemetry;

import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Connector implementation for Jira Cloud.
 * Fetches issues via the Jira REST API v3 search endpoint.
 */
public class JiraConnector implements ConnectorSdk {

    private static final List<String> SUPPORTED_FILTERS = List.of("=", "IN", ">", "<");
    private static final int MAX_PAGE_SIZE = 50;

    private final String baseUrl;
    private final RestClient restClient;
    private final Telemetry telemetry;

    public JiraConnector(String baseUrl, RestClient.Builder restClientBuilder, Telemetry telemetry) {
        this.baseUrl = baseUrl;
        this.telemetry = telemetry;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ConnectorRecord> fetch(SourceQuery query, Credential credential) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/rest/api/3/search")
                            .queryParam("jql", query.sql())
                            .build())
                    .header("X-Mock-User", credential.connectionRef())
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, resp) -> {
                        String retryAfter = resp.getHeaders().getFirst("Retry-After");
                        throw new UsqlException(ErrorCode.RATE_LIMIT_EXHAUSTED,
                                "Jira rate limit exceeded; Retry-After: " + retryAfter);
                    })
                    .body(Map.class);

            if (response == null) {
                return List.of();
            }

            List<Map<String, Object>> issues = (List<Map<String, Object>>) response.get("issues");
            if (issues == null) {
                return List.of();
            }

            List<ConnectorRecord> records = new ArrayList<>();
            for (Map<String, Object> issue : issues) {
                records.add(mapIssue(issue));
            }
            return records;

        } catch (UsqlException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof SocketTimeoutException) {
                    throw new UsqlException(ErrorCode.SOURCE_TIMEOUT,
                            "Jira request timed out", cause);
                }
                cause = cause.getCause();
            }
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "Jira fetch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CapabilityDescriptor getCapabilities() {
        return new CapabilityDescriptor("jira", SUPPORTED_FILTERS, MAX_PAGE_SIZE);
    }

    @SuppressWarnings("unchecked")
    private ConnectorRecord mapIssue(Map<String, Object> issue) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("issue_key", issue.get("key"));

        Map<String, Object> issueFields = (Map<String, Object>) issue.get("fields");
        if (issueFields != null) {
            Map<String, Object> project = (Map<String, Object>) issueFields.get("project");
            fields.put("project_key", project != null ? project.get("key") : null);

            Map<String, Object> status = (Map<String, Object>) issueFields.get("status");
            fields.put("status", status != null ? status.get("name") : null);

            Map<String, Object> priority = (Map<String, Object>) issueFields.get("priority");
            fields.put("priority", priority != null ? priority.get("name") : null);

            Map<String, Object> assignee = (Map<String, Object>) issueFields.get("assignee");
            fields.put("assignee_id", assignee != null ? assignee.get("accountId") : null);

            fields.put("summary", issueFields.get("summary"));
            fields.put("created_at", issueFields.get("created"));
            fields.put("updated_at", issueFields.get("updated"));
        }

        return new ConnectorRecord(java.util.Collections.unmodifiableMap(fields));
    }
}
