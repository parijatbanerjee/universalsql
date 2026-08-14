package com.ema.usql.planner.catalog;

import java.util.List;

/**
 * Catalog of available source tables and their columns.
 * Used by SqlParser for validation.
 */
public interface SourceCatalog {

    /** Returns true if the given table name exists in the catalog. */
    boolean tableExists(String tableName);

    /** Returns true if the given column exists in the given table. */
    boolean columnExists(String tableName, String columnName);

    /** Returns all column names for the given table. */
    List<String> getColumns(String tableName);

    /** Returns all table names in the catalog. */
    List<String> getTables();
}
