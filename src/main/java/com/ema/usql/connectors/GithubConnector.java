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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connector implementation for GitHub.
 * Fetches pull requests via the GitHub REST API.
 */
public class GithubConnector implements ConnectorSdk {

    private static final List<String> SUPPORTED_FILTERS = List.of("=", "IN");
    private static final int MAX_PAGE_SIZE = 100;

    // Pattern to extract JIRA issue key from PR title (e.g. "PLAT-1: fix login")
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("^([A-Z]+-\\d+)");

    private final String baseUrl;
    private final RestClient restClient;
    private final Telemetry telemetry;

    public GithubConnector(String baseUrl, RestClient.Builder restClientBuilder, Telemetry telemetry) {
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
            // Check for issue_keys parameter (semi-join IN-list filter)
            String issueKeysParam = query.params() != null
                    ? query.params().stream()
                        .filter(p -> p instanceof String s && s.startsWith("issue_keys="))
                        .map(p -> ((String) p).substring("issue_keys=".length()))
                        .findFirst()
                        .orElse(null)
                    : null;

            final String issueKeys = issueKeysParam;

            List<Map<String, Object>> response = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/repos/acme/issues/pulls");
                        if (issueKeys != null && !issueKeys.isEmpty()) {
                            builder = builder.queryParam("issue_keys", issueKeys);
                        }
                        return builder.build();
                    })
                    .header("X-Mock-User", credential.connectionRef())
                    .retrieve()
                    .onStatus(status -> status.value() == 429, (req, resp) -> {
                        String retryAfter = resp.getHeaders().getFirst("Retry-After");
                        throw new UsqlException(ErrorCode.RATE_LIMIT_EXHAUSTED,
                                "GitHub rate limit exceeded; Retry-After: " + retryAfter);
                    })
                    .body(List.class);

            if (response == null) {
                return List.of();
            }

            List<ConnectorRecord> records = new ArrayList<>();
            for (Map<String, Object> pr : response) {
                records.add(mapPr(pr));
            }
            return records;

        } catch (UsqlException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof SocketTimeoutException) {
                    throw new UsqlException(ErrorCode.SOURCE_TIMEOUT,
                            "GitHub request timed out", cause);
                }
                cause = cause.getCause();
            }
            throw new UsqlException(ErrorCode.SOURCE_UNAVAILABLE,
                    "GitHub fetch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CapabilityDescriptor getCapabilities() {
        return new CapabilityDescriptor("github", SUPPORTED_FILTERS, MAX_PAGE_SIZE);
    }

    @SuppressWarnings("unchecked")
    private ConnectorRecord mapPr(Map<String, Object> pr) {
        Map<String, Object> fields = new HashMap<>();

        Object number = pr.get("number");
        fields.put("pr_number", number);
        fields.put("repo", "acme/issues");
        fields.put("title", pr.get("title"));
        fields.put("state", pr.get("state"));

        Map<String, Object> user = (Map<String, Object>) pr.get("user");
        fields.put("author_id", user != null ? user.get("login") : null);

        // Extract linked Jira issue key from title
        String title = (String) pr.get("title");
        String linkedIssueKey = null;
        if (title != null) {
            Matcher matcher = ISSUE_KEY_PATTERN.matcher(title);
            if (matcher.find()) {
                linkedIssueKey = matcher.group(1);
            }
        }
        fields.put("linked_issue_key", linkedIssueKey);
        fields.put("created_at", pr.get("created_at"));
        fields.put("updated_at", pr.get("updated_at"));

        return new ConnectorRecord(java.util.Collections.unmodifiableMap(fields));
    }
}
