package com.hcx.medhistory.mapping;

import java.util.Objects;

/** Logical key for a resource in one received Bundle. */
public record FhirKey(String resourceType, String fhirId) {
    public FhirKey {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(fhirId, "fhirId");
    }
}
