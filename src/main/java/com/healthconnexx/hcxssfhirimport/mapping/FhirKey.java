package com.healthconnexx.hcxssfhirimport.mapping;

import java.util.Objects;

/** HDC-221: Logical key for a FHIR resource within one received Bundle. */
public record FhirKey(String resourceType, String fhirId) {
    public FhirKey {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(fhirId, "fhirId");
    }
}
