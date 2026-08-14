package com.ema.usql.planner.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardcoded in-memory catalog for the two known source tables.
 * Uses logical column names (reporter_email, author_email) — not physical encrypted names.
 */
public class InMemorySourceCatalog implements SourceCatalog {

    private final Map<String, List<String>> catalog;

    public InMemorySourceCatalog() {
        Map<String, List<String>> map = new LinkedHashMap<>();

        map.put("jira_issues", List.of(
                "issue_key",
                "project_key",
                "status",
                "priority",
                "assignee_id",
                "reporter_email",
                "summary",
                "created_at",
                "updated_at"
        ));

        map.put("github_prs", List.of(
                "pr_number",
                "repo",
                "title",
                "state",
                "author_id",
                "author_email",
                "linked_issue_key",
                "created_at",
                "updated_at"
        ));

        catalog = Collections.unmodifiableMap(map);
    }

    @Override
    public boolean tableExists(String tableName) {
        return catalog.containsKey(tableName);
    }

    @Override
    public boolean columnExists(String tableName, String columnName) {
        List<String> columns = catalog.get(tableName);
        return columns != null && columns.contains(columnName);
    }

    @Override
    public List<String> getColumns(String tableName) {
        List<String> columns = catalog.get(tableName);
        return columns != null ? new ArrayList<>(columns) : List.of();
    }

    @Override
    public List<String> getTables() {
        return new ArrayList<>(catalog.keySet());
    }
}
