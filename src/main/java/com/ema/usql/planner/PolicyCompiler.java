package com.ema.usql.planner;

import com.ema.usql.authz.api.AuthzContext;
import com.ema.usql.authz.api.RlsPredicate;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compiles RLS policy templates into concrete SQL predicates and injects them into queries.
 *
 * <p>The policy template uses placeholders like {@code :user.allowed_projects}.
 * The compiler resolves these from the AuthzContext principal set.
 */
@Service
public class PolicyCompiler {

    /**
     * Compile a policy RLS expression template into a concrete {@link RlsPredicate}.
     *
     * <p>Resolves {@code :user.allowed_projects} by extracting project:X principals from
     * the authz context and producing {@code project_key IN ('PLAT', 'CORE')}.
     *
     * @param rlsExpr   raw template expression (e.g. "project_key IN (:user.allowed_projects)")
     * @param authzCtx  resolved authorization context containing principal set
     * @return compiled RlsPredicate with concrete values substituted
     */
    public RlsPredicate compile(String rlsExpr, AuthzContext authzCtx) {
        if (rlsExpr == null || rlsExpr.isBlank()) {
            return new RlsPredicate(null);
        }

        String resolved = rlsExpr;

        // Resolve :user.allowed_projects placeholder
        if (rlsExpr.contains(":user.allowed_projects")) {
            List<String> allowedProjects = extractProjects(authzCtx.principalSet());
            String inList = allowedProjects.stream()
                    .map(p -> "'" + p.replace("'", "''") + "'")
                    .collect(Collectors.joining(", "));
            // If no projects, produce an always-false predicate
            if (inList.isEmpty()) {
                inList = "''";
            }
            resolved = rlsExpr.replace(":user.allowed_projects", inList);
        }

        return new RlsPredicate(resolved);
    }

    /**
     * Inject an RLS predicate into a SQL query by ANDing it into the WHERE clause.
     *
     * <p>Uses JSqlParser to parse the SQL, modify the AST, and re-emit it.
     *
     * @param originalSql the original SQL string
     * @param rls         the compiled RLS predicate
     * @return the SQL string with the RLS predicate injected
     */
    public String injectIntoSql(String originalSql, RlsPredicate rls) {
        if (rls == null || rls.expression() == null || rls.expression().isBlank()) {
            return originalSql;
        }

        try {
            Statement stmt = CCJSqlParserUtil.parse(originalSql);
            if (!(stmt instanceof Select select)) {
                return originalSql;
            }
            if (!(select.getSelectBody() instanceof PlainSelect ps)) {
                return originalSql;
            }

            Expression rlsExpr = CCJSqlParserUtil.parseCondExpression(rls.expression());

            Expression currentWhere = ps.getWhere();
            if (currentWhere == null) {
                ps.setWhere(rlsExpr);
            } else {
                ps.setWhere(new AndExpression(currentWhere, rlsExpr));
            }

            return select.toString();
        } catch (Exception e) {
            throw new UsqlException(ErrorCode.UNSUPPORTED_SQL,
                    "Failed to inject RLS predicate into SQL: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<String> extractProjects(Set<String> principals) {
        return principals.stream()
                .filter(p -> p.startsWith("project:"))
                .map(p -> p.substring("project:".length()))
                .sorted()
                .toList();
    }
}
