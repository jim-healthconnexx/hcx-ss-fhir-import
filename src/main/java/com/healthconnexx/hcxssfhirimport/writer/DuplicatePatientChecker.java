package com.healthconnexx.hcxssfhirimport.writer;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

import org.jooq.impl.DSL;

import com.healthconnexx.hcxssfhirimport.mapping.FhirKey;
import com.healthconnexx.hcxssfhirimport.mapping.MappedBundle;
import com.healthconnexx.hcxssfhirimport.mapping.TableRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * HDC-243: Checks whether the patient in an incoming FHIR bundle already exists in
 * the fhir schema before import. If all key fields match an existing record, the
 * bundle should be skipped to avoid duplicate data.
 *
 * <p>Duplicate criteria (ALL must match):
 * <ul>
 *   <li>Communication identifier with system = POPULATION_ID_SYSTEM and value = populationId</li>
 *   <li>Patient: gender, birth_date, mrn</li>
 *   <li>Patient name (ordinal 0): family, given_values</li>
 *   <li>Patient address (ordinal 0): postal_code</li>
 * </ul>
 *
 * <p>Fails open — returns {@code false} whenever any required field is null so that
 * valid data is never silently discarded due to incomplete incoming records.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicatePatientChecker {

    private static final String POPULATION_ID_SYSTEM =
            "http://fhirdocs.surescripts.net/identifiers/population-id";

    private final DSLContext dslContext;

    /**
     * HDC-243: Returns {@code true} only when the bundle's patient and communication
     * identifier already exist in the database with matching demographics.
     *
     * @param bundle             mapped FHIR bundle to check
     * @param populationId       population-id value from Communication.identifier
     * @param assigningAuthority system URI used to locate the patient MRN identifier
     */
    public boolean isDuplicate(MappedBundle bundle, String populationId, String assigningAuthority) {
        PatientFields fields = extractFields(bundle, populationId, assigningAuthority);

        if (fields == null) {
            log.warn("HDC-243: Cannot perform duplicate check — one or more required fields are null; proceeding with import");
            return false;
        }

        log.debug("HDC-243: Checking for duplicate — populationId='{}' mrn='{}' gender='{}' birthDate='{}' family='{}' given={} postalCode='{}'",
                fields.populationId, fields.mrn, fields.gender, fields.birthDate,
                fields.family, fields.given, fields.postalCode);

        if (!communicationExists(fields.populationId)) {
            log.debug("HDC-243: No existing communication_identifier found for populationId='{}' — not a duplicate", fields.populationId);
            return false;
        }

        if (!patientExists(fields)) {
            log.debug("HDC-243: No existing patient found matching demographics — not a duplicate");
            return false;
        }

        log.info("HDC-243: Duplicate detected — populationId='{}' mrn='{}' already exists in fhir schema", fields.populationId, fields.mrn);
        return true;
    }

    // HDC-243: Extract required demographic fields from the mapped bundle rows.
    private PatientFields extractFields(MappedBundle bundle, String populationId, String assigningAuthority) {
        if (populationId == null || assigningAuthority == null) return null;

        List<TableRow> rows = bundle.rows();

        TableRow patientRow = rows.stream()
                .filter(r -> "patient".equals(r.table()))
                .findFirst().orElse(null);
        if (patientRow == null) return null;

        FhirKey patientKey = patientFhirKey(patientRow);

        String gender    = (String) patientRow.columns().get("gender");
        LocalDate birthDate = (LocalDate) patientRow.columns().get("birth_date");

        String mrn = rows.stream()
                .filter(r -> "patient_identifier".equals(r.table()))
                .filter(r -> patientKey != null && patientKey.equals(r.columns().get("patient_id")))
                .filter(r -> assigningAuthority.equals(r.columns().get("system_uri")))
                .map(r -> (String) r.columns().get("value"))
                .findFirst().orElse(null);

        TableRow nameRow = rows.stream()
                .filter(r -> "patient_name".equals(r.table()))
                .filter(r -> patientKey != null && patientKey.equals(r.columns().get("patient_id")))
                .filter(r -> Integer.valueOf(0).equals(r.columns().get("ordinal")))
                .findFirst().orElse(null);

        String family = nameRow != null ? (String) nameRow.columns().get("family") : null;
        @SuppressWarnings("unchecked")
        List<String> given = nameRow != null ? (List<String>) nameRow.columns().get("given_values") : null;

        TableRow addrRow = rows.stream()
                .filter(r -> "patient_address".equals(r.table()))
                .filter(r -> patientKey != null && patientKey.equals(r.columns().get("patient_id")))
                .filter(r -> Integer.valueOf(0).equals(r.columns().get("ordinal")))
                .findFirst().orElse(null);

        String postalCode = addrRow != null ? (String) addrRow.columns().get("postal_code") : null;

        if (gender == null || birthDate == null || mrn == null || family == null || given == null || postalCode == null) {
            log.debug("HDC-243: Missing field(s) — gender={} birthDate={} mrn={} family={} given={} postalCode={}",
                    gender, birthDate, mrn != null ? "present" : "null", family, given, postalCode);
            return null;
        }

        return new PatientFields(populationId, mrn, gender, birthDate, family, given, postalCode);
    }

    private FhirKey patientFhirKey(TableRow patientRow) {
        String fhirId = (String) patientRow.columns().get("fhir_id");
        return fhirId != null ? new FhirKey("Patient", fhirId) : null;
    }

    // HDC-243: Check A — verify population-id was already imported via communication_identifier.
    private boolean communicationExists(String populationId) {
        boolean found = dslContext.fetchExists(
                selectOne()
                        .from(table(name("fhir", "communication_identifier")))
                        .where(field(name("system_uri")).eq(POPULATION_ID_SYSTEM))
                        .and(field(name("value")).eq(populationId)));
        log.debug("HDC-243: communication_identifier exists for populationId='{}': {}", populationId, found);
        return found;
    }

    // HDC-243: Check B — verify patient demographics match an existing record.
    // Uses JOINs (not correlated EXISTS) to avoid jOOQ alias-scope issues.
    // given_values[1] (PostgreSQL 1-based) = first name; middle name is not required.
    private boolean patientExists(PatientFields f) {
        String firstName = f.given.isEmpty() ? null : f.given.get(0);
        if (firstName == null) {
            log.warn("HDC-243: given_values[0] (first name) is null — cannot match patient; proceeding with import");
            return false;
        }

        boolean found = dslContext.fetchExists(
                selectOne()
                        .from(table(name("fhir", "patient")).as("p"))
                        .join(table(name("fhir", "patient_name")).as("pn"))
                            .on(field(name("pn", "patient_id")).eq(field(name("p", "patient_id"))))
                        .join(table(name("fhir", "patient_address")).as("pa"))
                            .on(field(name("pa", "patient_id")).eq(field(name("p", "patient_id"))))
                        .where(field(name("p", "mrn")).eq(f.mrn))
                        .and(field(name("p", "gender")).eq(f.gender))
                        .and(field(name("p", "birth_date")).eq(f.birthDate))
                        .and(field(name("pn", "family")).eq(f.family))
                        .and(field(name("pa", "postal_code")).eq(f.postalCode))
                        .and(DSL.condition("{0}[1] = {1}", field(name("pn", "given_values")), val(firstName))));
        log.debug("HDC-243: patient exists matching demographics (incl. first name): {}", found);
        return found;
    }

    private record PatientFields(
            String populationId,
            String mrn,
            String gender,
            LocalDate birthDate,
            String family,
            List<String> given,
            String postalCode
    ) {}
}
