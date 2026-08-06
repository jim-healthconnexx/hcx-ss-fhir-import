package com.healthconnexx.hcxssfhirimport.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * HDC-221: One normalized database row. Keys in {@code columns} are the column names in
 * medication_history_populations_postgresql.sql.
 *
 * Values may contain {@link FhirKey} for foreign-key columns. The persistence layer
 * resolves each FhirKey to the generated BIGINT PK after the referenced row is inserted.
 */
public record TableRow(String table, Map<String, Object> columns) {
    public TableRow {
        Objects.requireNonNull(table, "table");
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }
}
