package com.healthconnexx.hcxssfhirimport.mapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** HDC-221: Result of mapping one FHIR searchset Bundle. */
public record MappedBundle(FhirKey bundleKey, List<TableRow> rows) {

    /** Convenience view grouping rows by target table for batch writing. */
    public Map<String, List<TableRow>> rowsByTable() {
        return rows.stream().collect(Collectors.groupingBy(
                TableRow::table,
                LinkedHashMap::new,
                Collectors.toList()));
    }
}
