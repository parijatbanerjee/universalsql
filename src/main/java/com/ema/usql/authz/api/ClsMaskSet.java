package com.ema.usql.authz.api;

import java.util.Map;

/**
 * The set of column-level security masks to apply to the projection.
 * Each entry maps a column name to its mask type (e.g. "REDACT", "HASH", "NULL").
 * Applied at plan time before data is fetched.
 */
public record ClsMaskSet(Map<String, String> maskedColumns) {
}
