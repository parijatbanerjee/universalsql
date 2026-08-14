package com.ema.usql.planner;

import com.ema.usql.planner.catalog.InMemorySourceCatalog;
import com.ema.usql.planner.catalog.SourceCatalog;
import com.ema.usql.shared.ErrorCode;
import com.ema.usql.shared.UsqlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 8 acceptance tests: SQL parser and logical plan.
 * Pure unit tests — no Spring context required.
 */
class Task8SqlParserTest {

    private SqlParser parser;
    private SourceCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new InMemorySourceCatalog();
        parser = new SqlParser();
    }

    // =========================================================================
    // VALID CASES
    // =========================================================================

    /** 1. SELECT * FROM jira_issues */
    @Test
    void validCase1_selectStarFromJiraIssues() {
        LogicalPlan plan = parser.parse("SELECT * FROM jira_issues", catalog);

        assertThat(plan.projections()).containsExactly("*");
        assertThat(plan.tables()).containsExactly("jira_issues");
        assertThat(plan.joinCondition()).isNull();
        assertThat(plan.joinTable()).isNull();
        assertThat(plan.whereClause()).isNull();
        assertThat(plan.orderBy()).isNull();
        assertThat(plan.limit()).isNull();
    }

    /** 2. SELECT specific columns with WHERE equality */
    @Test
    void validCase2_selectColumnsWithWhereEquality() {
        LogicalPlan plan = parser.parse(
                "SELECT issue_key, status FROM jira_issues WHERE project_key = 'PLAT'", catalog);

        assertThat(plan.projections()).containsExactly("issue_key", "status");
        assertThat(plan.tables()).containsExactly("jira_issues");
        assertThat(plan.whereClause()).isEqualTo("project_key = 'PLAT'");
    }

    /** 3. SELECT * with WHERE IN */
    @Test
    void validCase3_selectStarWhereIn() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM jira_issues WHERE project_key IN ('PLAT', 'CORE')", catalog);

        assertThat(plan.projections()).containsExactly("*");
        assertThat(plan.whereClause()).isNotNull();
        assertThat(plan.whereClause()).containsIgnoringCase("IN");
    }

    /** 4. SELECT * with WHERE greater-than */
    @Test
    void validCase4_selectStarWhereGreaterThan() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM jira_issues WHERE updated_at > '2024-01-01'", catalog);

        assertThat(plan.projections()).containsExactly("*");
        assertThat(plan.whereClause()).isNotNull();
        assertThat(plan.whereClause()).contains(">");
    }

    /** 5. SELECT * with WHERE AND */
    @Test
    void validCase5_selectStarWhereAnd() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM jira_issues WHERE project_key = 'PLAT' AND status = 'Open'", catalog);

        assertThat(plan.projections()).containsExactly("*");
        assertThat(plan.whereClause()).isNotNull();
        assertThat(plan.whereClause()).containsIgnoringCase("AND");
    }

    /** 6. ORDER BY and LIMIT */
    @Test
    void validCase6_orderByAndLimit() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM jira_issues ORDER BY updated_at DESC LIMIT 25", catalog);

        assertThat(plan.projections()).containsExactly("*");
        assertThat(plan.orderBy()).isEqualTo("updated_at DESC");
        assertThat(plan.limit()).isEqualTo(25);
    }

    /** 7. JOIN case — jira_issues JOIN github_prs with WHERE */
    @Test
    void validCase7_joinWithWhere() {
        String sql = "SELECT i.issue_key, p.title FROM jira_issues i " +
                     "JOIN github_prs p ON p.linked_issue_key = i.issue_key " +
                     "WHERE i.project_key = 'PLAT'";
        LogicalPlan plan = parser.parse(sql, catalog);

        assertThat(plan.projections()).hasSize(2);
        assertThat(plan.tables()).containsExactlyInAnyOrder("jira_issues", "github_prs");
        assertThat(plan.joinTable()).isEqualTo("github_prs");
        assertThat(plan.joinCondition()).isNotNull();
        assertThat(plan.joinCondition()).contains("linked_issue_key");
        assertThat(plan.tableAliases()).containsEntry("i", "jira_issues");
        assertThat(plan.tableAliases()).containsEntry("p", "github_prs");
        assertThat(plan.whereClause()).contains("project_key");
    }

    /** 8. Multiple AND in WHERE */
    @Test
    void validCase8_multipleAndInWhere() {
        LogicalPlan plan = parser.parse(
                "SELECT issue_key FROM jira_issues " +
                "WHERE project_key = 'PLAT' AND status = 'Open' AND priority = 'High'",
                catalog);

        assertThat(plan.projections()).containsExactly("issue_key");
        assertThat(plan.whereClause()).containsIgnoringCase("AND");
    }

    /** 9. github_prs table with LIMIT */
    @Test
    void validCase9_githubPrsWithLimit() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM github_prs WHERE state = 'open' LIMIT 10", catalog);

        assertThat(plan.tables()).containsExactly("github_prs");
        assertThat(plan.whereClause()).contains("state");
        assertThat(plan.limit()).isEqualTo(10);
    }

    // =========================================================================
    // INVALID CASES
    // =========================================================================

    /** 10. UPDATE statement is not allowed */
    @Test
    void invalidCase10_updateNotAllowed() {
        assertThatThrownBy(() ->
                parser.parse(
                        "UPDATE jira_issues SET status = 'Closed' WHERE issue_key = 'PLAT-1'",
                        catalog))
                .isInstanceOf(UsqlException.class)
                .satisfies(e -> assertThat(((UsqlException) e).getErrorCode())
                        .isEqualTo(ErrorCode.UNSUPPORTED_SQL));
    }

    /** 11. OR in WHERE is not allowed */
    @Test
    void invalidCase11_orNotAllowed() {
        assertThatThrownBy(() ->
                parser.parse(
                        "SELECT * FROM jira_issues WHERE project_key = 'A' OR project_key = 'B'",
                        catalog))
                .isInstanceOf(UsqlException.class)
                .satisfies(e -> {
                    UsqlException ex = (UsqlException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_SQL);
                    assertThat(ex.getMessage()).containsIgnoringCase("OR");
                });
    }

    /** 12. Unknown table */
    @Test
    void invalidCase12_unknownTable() {
        assertThatThrownBy(() ->
                parser.parse("SELECT * FROM unknown_table", catalog))
                .isInstanceOf(UsqlException.class)
                .satisfies(e -> {
                    UsqlException ex = (UsqlException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_SQL);
                    assertThat(ex.getMessage()).contains("Unknown table: unknown_table");
                });
    }

    /** 13. Unknown column */
    @Test
    void invalidCase13_unknownColumn() {
        assertThatThrownBy(() ->
                parser.parse("SELECT nonexistent_col FROM jira_issues", catalog))
                .isInstanceOf(UsqlException.class)
                .satisfies(e -> {
                    UsqlException ex = (UsqlException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_SQL);
                    assertThat(ex.getMessage()).contains("Unknown column: nonexistent_col");
                });
    }

    /** 14. More than one JOIN is not allowed */
    @Test
    void invalidCase14_moreThanOneJoinNotAllowed() {
        String sql = "SELECT * FROM jira_issues i " +
                     "JOIN github_prs p ON p.linked_issue_key = i.issue_key " +
                     "JOIN jira_issues j ON j.issue_key = p.pr_number";
        assertThatThrownBy(() -> parser.parse(sql, catalog))
                .isInstanceOf(UsqlException.class)
                .satisfies(e -> {
                    UsqlException ex = (UsqlException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_SQL);
                    assertThat(ex.getMessage()).containsIgnoringCase("join");
                });
    }

    /** 15. Aggregate functions are not allowed */
    @Test
    void invalidCase15_aggregateNotAllowed() {
        assertThatThrownBy(() ->
                parser.parse("SELECT COUNT(*) FROM jira_issues", catalog))
                .isInstanceOf(UsqlException.class)
                .satisfies(e -> {
                    UsqlException ex = (UsqlException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_SQL);
                    assertThat(ex.getMessage()).containsIgnoringCase("aggregate");
                });
    }

    /** 16. Subquery in WHERE IN is not allowed */
    @Test
    void invalidCase16_subqueryNotAllowed() {
        assertThatThrownBy(() ->
                parser.parse(
                        "SELECT * FROM jira_issues WHERE issue_key IN (SELECT issue_key FROM github_prs)",
                        catalog))
                .isInstanceOf(UsqlException.class)
                .satisfies(e -> {
                    UsqlException ex = (UsqlException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_SQL);
                    assertThat(ex.getMessage()).containsIgnoringCase("subquer");
                });
    }
}
