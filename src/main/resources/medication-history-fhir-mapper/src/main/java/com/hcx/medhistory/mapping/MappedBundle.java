package com.hcx.medhistory.mapping;

import java.util.List;
import java.util.Map;

/** Result of mapping one FHIR searchset Bundle. */
public record MappedBundle(FhirKey bundleKey, List<TableRow> rows) {

    /** Convenience view for a database writer that batches by target table. */
    public Map<String, List<TableRow>> rowsByTable() {
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(
                TableRow::table,
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()));
    }
}
