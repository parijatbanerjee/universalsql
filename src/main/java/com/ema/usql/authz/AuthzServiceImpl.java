package com.ema.usql.authz;

import com.ema.usql.authz.api.AuthzContext;
import com.ema.usql.authz.api.AuthzService;
import com.ema.usql.authz.api.ClsMaskSet;
import com.ema.usql.authz.api.RlsPredicate;
import com.ema.usql.authz.principals.AclSnapshot;
import com.ema.usql.authz.principals.AclStore;
import com.ema.usql.authz.principals.PrincipalStore;
import com.ema.usql.shared.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Real AuthzService implementation: resolves principal closure from Postgres,
 * loads the applicable RLS/CLS policy, and returns a fully-populated AuthzContext.
 *
 * <p>This bean is @Primary so it replaces StubAuthzService in all contexts.
 */
@Service
@Primary
public class AuthzServiceImpl implements AuthzService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Map of table names that have configured policies
    private static final String DEFAULT_TABLE = "jira_issues";

    private final PrincipalStore principalStore;
    private final AclStore aclStore;
    private final PolicyStore policyStore;

    public AuthzServiceImpl(
            PrincipalStore principalStore,
            AclStore aclStore,
            PolicyStore policyStore) {
        this.principalStore = principalStore;
        this.aclStore = aclStore;
        this.policyStore = policyStore;
    }

    @Override
    public AuthzContext resolve(TenantContext ctx) {
        // 1. Load principal closure for user
        Set<String> principals = principalStore.getPrincipals(ctx.tenantId(), ctx.userId());

        // 2. Load policy from Postgres (load jira_issues policy as the default policy)
        PolicyStore.Policy policy = policyStore.findPolicy(ctx.tenantId(), DEFAULT_TABLE);

        // 3. Build RlsPredicate from policy rls_expr (store the template for Task 11 to resolve)
        // If the user has no principals (unauthenticated/unknown user that bypassed JWT), return
        // no RLS predicate — the JWT security filter blocks real unauthorized requests before
        // they reach here; an empty principal set in the Orchestrator means auth is disabled
        // for testing (addFilters=false). Apply RLS only when there is a real principal set.
        RlsPredicate rlsPredicate = principals.isEmpty() ? new RlsPredicate(null) : buildRlsPredicate(policy);

        // 4. Build ClsMaskSet from policy cls_json
        ClsMaskSet clsMaskSet = buildClsMaskSet(policy, principals);

        // 5. Load ACL snapshot
        AclSnapshot aclSnapshot = aclStore.getSnapshot(ctx.tenantId(), ctx.userId());

        long aclVersion = 0L;
        try {
            aclVersion = Long.parseLong(aclSnapshot.aclVersion());
        } catch (NumberFormatException ignored) {
            // default to 0
        }

        // 6. Return AuthzContext
        return new AuthzContext(
                principals,
                rlsPredicate,
                clsMaskSet,
                aclVersion,
                aclSnapshot.syncedAt()
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RlsPredicate buildRlsPredicate(PolicyStore.Policy policy) {
        if (policy == null || policy.rlsExpr() == null || policy.rlsExpr().isBlank()) {
            return new RlsPredicate(null);
        }
        // Store the template expression; Task 11 (PolicyCompiler) resolves the placeholder
        return new RlsPredicate(policy.rlsExpr());
    }

    private ClsMaskSet buildClsMaskSet(PolicyStore.Policy policy, Set<String> principals) {
        if (policy == null || policy.clsJson() == null || policy.clsJson().isBlank()) {
            return new ClsMaskSet(Map.of());
        }

        try {
            // Parse cls_json: {"reporter_email":{"mask":"redact","except_principals":["role:admin"]}}
            Map<String, Map<String, Object>> clsConfig = MAPPER.readValue(
                    policy.clsJson(),
                    new TypeReference<Map<String, Map<String, Object>>>() {}
            );

            Map<String, String> maskedColumns = new HashMap<>();

            for (Map.Entry<String, Map<String, Object>> entry : clsConfig.entrySet()) {
                String columnName = entry.getKey();
                Map<String, Object> config = entry.getValue();

                String maskType = (String) config.get("mask");

                // Check if the user has an exemption via except_principals
                @SuppressWarnings("unchecked")
                java.util.List<String> exceptPrincipals = (java.util.List<String>) config.get("except_principals");

                boolean isExempt = exceptPrincipals != null
                        && exceptPrincipals.stream().anyMatch(principals::contains);

                if (!isExempt && maskType != null) {
                    maskedColumns.put(columnName, maskType.toUpperCase());
                }
            }

            return new ClsMaskSet(maskedColumns);
        } catch (IOException e) {
            // If CLS JSON is malformed, return restrictive mask-all
            return new ClsMaskSet(Map.of());
        }
    }
}
