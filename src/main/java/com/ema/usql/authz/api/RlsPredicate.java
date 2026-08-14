package com.ema.usql.authz.api;

/**
 * A row-level security predicate expression to be injected into the query plan.
 * Enforcement happens at plan time — before any data is fetched.
 */
public record RlsPredicate(String expression) {
}
