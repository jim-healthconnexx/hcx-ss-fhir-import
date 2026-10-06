package com.healthconnexx.hcxssfhirimport.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HDC-294: Verifies that MedicationDispense.whenPrepared/whenHandedOver are stored as the
 * literal wall-clock value written in the FHIR message, without being shifted by the
 * value's time zone offset.
 */
class FhirBundleRelationalMapperTest {

    private final FhirBundleRelationalMapper mapper = new FhirBundleRelationalMapper(new ObjectMapper());

    @Test
    void medicationDispenseDatesPreserveLiteralValueRegardlessOfOffset() throws Exception {
        String json = """
                {
                  "resourceType": "Bundle",
                  "id": "bundle-1",
                  "type": "collection",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "MedicationDispense",
                        "id": "disp-1",
                        "status": "completed",
                        "whenPrepared": "2026-08-26T00:00:00+00:00",
                        "whenHandedOver": "2026-08-25T20:00:00-04:00"
                      }
                    }
                  ]
                }
                """;

        List<MappedBundle> mapped = mapper.map(json);
        TableRow dispenseRow = mapped.get(0).rows().stream()
                .filter(r -> "medication_dispense".equals(r.table()))
                .findFirst().orElseThrow();
        Map<String, Object> columns = dispenseRow.columns();

        // Same literal wall-clock values in the source FHIR fields (2026-08-26T00:00:00 and
        // 2026-08-25T20:00:00), even though they represent the same instant with different offsets.
        assertThat(columns.get("when_prepared")).isEqualTo(LocalDateTime.of(2026, 8, 26, 0, 0, 0));
        assertThat(columns.get("when_handed_over")).isEqualTo(LocalDateTime.of(2026, 8, 25, 20, 0, 0));
    }

    @Test
    void medicationDispenseDateOnlyValueStartsAtMidnight() throws Exception {
        String json = """
                {
                  "resourceType": "Bundle",
                  "id": "bundle-2",
                  "type": "collection",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "MedicationDispense",
                        "id": "disp-2",
                        "status": "completed",
                        "whenPrepared": "2026-08-26"
                      }
                    }
                  ]
                }
                """;

        List<MappedBundle> mapped = mapper.map(json);
        TableRow dispenseRow = mapped.get(0).rows().stream()
                .filter(r -> "medication_dispense".equals(r.table()))
                .findFirst().orElseThrow();

        assertThat(dispenseRow.columns().get("when_prepared")).isEqualTo(LocalDateTime.of(2026, 8, 26, 0, 0, 0));
    }
}
