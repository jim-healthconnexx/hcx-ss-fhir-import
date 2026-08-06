package com.hcx.medhistory.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FhirBundleRelationalMapperTest {
    @Test
    void mapsBundleAndCrossResourceReferencesWithoutDatabaseKeys() throws Exception {
        String json = """
                {"resourceType":"Bundle","id":"bundle-1","type":"searchset","total":1,
                 "entry":[
                   {"fullUrl":"https://example/Patient/p-1","resource":{
                     "resourceType":"Patient","id":"p-1","active":true,"gender":"female","birthDate":"1990-01-02",
                     "meta":{"versionId":"1","lastUpdated":"2026-08-04T13:17:32Z"},
                     "identifier":[{"system":"urn:test","value":"123"}] }},
                   {"resource":{
                     "resourceType":"Communication","id":"c-1","status":"completed","received":"2026-08-04T13:16:30Z",
                     "meta":{"versionId":"1","lastUpdated":"2026-08-04T13:17:32Z"},
                     "subject":{"reference":"Patient/p-1"},"category":[{"coding":[{"code":"panel"}]}] },
                     "search":{"mode":"match"}}
                 ]}
                """;

        MappedBundle mapped = new FhirBundleRelationalMapper(new ObjectMapper()).map(json).getFirst();
        TableRow communication = mapped.rowsByTable().get("communication").getFirst();

        assertEquals(new FhirKey("Bundle", "bundle-1"), communication.columns().get("bundle_id"));
        assertEquals(new FhirKey("Patient", "p-1"), communication.columns().get("patient_id"));
        assertInstanceOf(FhirKey.class, communication.columns().get("patient_id"));
        assertTrue(mapped.rowsByTable().containsKey("patient_identifier"));
    }
}
