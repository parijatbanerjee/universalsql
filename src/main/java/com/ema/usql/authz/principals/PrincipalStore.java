package com.ema.usql.authz.principals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads principal closures from the {@code principal_closure} Postgres table.
 * A principal closure represents the full set of access entitlements for a user,
 * including project memberships, roles, and group memberships.
 */
@Service
public class PrincipalStore {

    private final JdbcTemplate jdbcTemplate;

    public PrincipalStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns all principal_ids for the given user in the given tenant.
     */
    public Set<String> getPrincipals(String tenantId, String userId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT principal_id FROM principal_closure WHERE tenant_id = ? AND user_id = ?",
                String.class,
                tenantId, userId
        );
        return new HashSet<>(rows);
    }

    /**
     * Returns only the project names that the user has access to.
     * Extracts the project name from principals with format "project:PLAT" → "PLAT".
     */
    public List<String> getAllowedProjects(String tenantId, String userId) {
        Set<String> principals = getPrincipals(tenantId, userId);
        return principals.stream()
                .filter(p -> p.startsWith("project:"))
                .map(p -> p.substring("project:".length()))
                .sorted()
                .toList();
    }
}
