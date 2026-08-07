package com.healthconnexx.hcxssfhirimport.writer;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

import com.healthconnexx.hcxssfhirimport.mapping.FhirKey;
import com.healthconnexx.hcxssfhirimport.mapping.MappedBundle;
import com.healthconnexx.hcxssfhirimport.mapping.TableRow;
import org.jooq.JSONB;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.InsertSetMoreStep;
import org.jooq.InsertSetStep;
import org.jooq.Record1;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HDC-221: Persists a {@link MappedBundle} to the fhir schema using jOOQ.
 *
 * <p>Insertion order respects FK dependencies:
 * bundle → bundle_link/bundle_resource →
 * patient/organization/practitioner/medication →
 * condition →
 * medication_request/medication_dispense →
 * all child/join/repeating-element tables →
 * communication → communication_based_on
 *
 * <p>FhirKey values in column maps are resolved to generated BIGINT PKs as rows are inserted.
 * The patient row has its mrn resolved from patient_identifier rows using the assigningAuthority.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FhirImportWriter {

    // HDC-228: Columns that require JSONB binding — jOOQ will not auto-cast String → JSONB.
    private static final Set<String> JSONB_COLUMNS = Set.of("source_payload");

    // HDC-221: Ordered list of table names for dependency-safe insertion.
    private static final List<String> INSERT_ORDER = List.of(
            "bundle",
            "bundle_link",
            "bundle_resource",
            "patient",
            "organization",
            "practitioner",
            "medication",
            "condition",
            "medication_request",
            "medication_dispense",
            "medication_request_performer",
            "medication_dispense_performer",
            "medication_dispense_prescription",
            "medication_dispense_type_coding",
            "medication_dispense_dosage_instruction",
            "medication_dispense_extension",
            "communication",
            "communication_based_on",
            "communication_category_coding",
            "communication_reason_coding",
            "communication_extension",
            "patient_identifier",
            "organization_identifier",
            "practitioner_identifier",
            "medication_identifier",
            "condition_identifier",
            "medication_request_identifier",
            "medication_dispense_identifier",
            "communication_identifier",
            "patient_name",
            "practitioner_name",
            "patient_telecom",
            "organization_telecom",
            "practitioner_telecom",
            "patient_address",
            "organization_address",
            "practitioner_address",
            "medication_code_coding",
            "condition_code_coding",
            "condition_category_coding"
    );

    // HDC-221: Maps resource table names to their PK column and the FhirKey column in that row.
    private static final Map<String, String> PK_COLUMN = Map.ofEntries(
            Map.entry("bundle",               "bundle_id"),
            Map.entry("patient",              "patient_id"),
            Map.entry("organization",         "organization_id"),
            Map.entry("practitioner",         "practitioner_id"),
            Map.entry("medication",           "medication_id"),
            Map.entry("condition",            "condition_id"),
            Map.entry("medication_request",   "medication_request_id"),
            Map.entry("medication_dispense",  "medication_dispense_id"),
            Map.entry("communication",        "communication_id")
    );

    private final DSLContext dslContext;

    /**
     * HDC-221: Persists one MappedBundle transactionally.
     * @param mappedBundle  the mapped FHIR bundle rows
     * @param assigningAuthority the DTL.AssigningAuthority system URI from product.file_config,
     *                           used to resolve patient MRN from patient_identifier rows.
     *                           May be null — mrn will be left null.
     */
    @Transactional
    public void persist(MappedBundle mappedBundle, String assigningAuthority) {
        // FhirKey → generated BIGINT PK resolved as each row is inserted
        Map<FhirKey, Long> pkMap = new HashMap<>();

        Map<String, List<TableRow>> byTable = mappedBundle.rowsByTable();

        for (String tableName : INSERT_ORDER) {
            List<TableRow> rows = byTable.getOrDefault(tableName, List.of());
            for (TableRow row : rows) {
                insertRow(tableName, row, pkMap, mappedBundle, assigningAuthority);
            }
        }

        // HDC-221: Insert any tables not in INSERT_ORDER (forward-compat safety net — should not normally trigger).
        for (Map.Entry<String, List<TableRow>> entry : byTable.entrySet()) {
            if (!INSERT_ORDER.contains(entry.getKey())) {
                log.warn("HDC-221: Table '{}' not in INSERT_ORDER — inserting last", entry.getKey());
                for (TableRow row : entry.getValue()) {
                    insertRow(entry.getKey(), row, pkMap, mappedBundle, assigningAuthority);
                }
            }
        }

        log.debug("HDC-221: Persisted bundle fhir_id={} — {} table(s), {} row(s) total",
                mappedBundle.bundleKey().fhirId(), byTable.size(), mappedBundle.rows().size());
    }

    private void insertRow(String tableName, TableRow row, Map<FhirKey, Long> pkMap,
                           MappedBundle bundle, String assigningAuthority) {
        Map<String, Object> resolvedColumns = resolveColumns(row, pkMap, tableName, bundle, assigningAuthority);

        // HDC-228: Tables are in the fhir schema, not medication_history.
        InsertSetStep<?> insertStep = dslContext.insertInto(table(name("fhir", tableName)));
        InsertSetMoreStep<?> step = null;
        for (Map.Entry<String, Object> col : resolvedColumns.entrySet()) {
            // HDC-228: Wrap source_payload (and any future jsonb columns) with JSONB.valueOf so
            // jOOQ sends the correct JDBC type instead of binding as VARCHAR.
            Object colValue = JSONB_COLUMNS.contains(col.getKey()) && col.getValue() instanceof String s
                    ? JSONB.valueOf(s)
                    : col.getValue();
            if (step == null) {
                step = insertStep.set(field(name(col.getKey())), colValue);
            } else {
                step = step.set(field(name(col.getKey())), colValue);
            }
        }
        if (step == null) return;

        String pkCol = PK_COLUMN.get(tableName);
        if (pkCol != null) {
            // HDC-221: Capture generated PK for FK resolution in subsequent rows.
            Long pk = step.returningResult(field(name(pkCol), Long.class))
                    .fetchOne(Record1::value1);
            FhirKey fhirKey = new FhirKey(resourceType(tableName), (String) resolvedColumns.get("fhir_id"));
            pkMap.put(fhirKey, pk);
            log.debug("HDC-221: Inserted {} fhir_id={} → pk={}", tableName, resolvedColumns.get("fhir_id"), pk);
        } else {
            step.execute();
        }
    }

    /**
     * HDC-221: Resolves all FhirKey values in the column map to their generated BIGINT PKs.
     * Also resolves patient.mrn from patient_identifier rows when assigningAuthority is provided.
     */
    private Map<String, Object> resolveColumns(TableRow row, Map<FhirKey, Long> pkMap,
                                               String tableName, MappedBundle bundle, String assigningAuthority) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.columns().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof FhirKey fhirKey) {
                Long pk = pkMap.get(fhirKey);
                if (pk == null) {
                    log.error("HDC-221: Unresolved FhirKey {}:{} in column '{}' of table '{}'",
                            fhirKey.resourceType(), fhirKey.fhirId(), entry.getKey(), tableName);
                }
                resolved.put(entry.getKey(), pk);
            } else {
                resolved.put(entry.getKey(), value);
            }
        }

        // HDC-221: Resolve patient.mrn from patient_identifier rows using AssigningAuthority system URI.
        if ("patient".equals(tableName) && assigningAuthority != null) {
            FhirKey patientFhirKey = findPatientFhirKey(row);
            if (patientFhirKey != null) {
                String mrn = bundle.rows().stream()
                        .filter(r -> "patient_identifier".equals(r.table()))
                        .filter(r -> patientFhirKey.equals(r.columns().get("patient_id")))
                        .filter(r -> assigningAuthority.equals(r.columns().get("system_uri")))
                        .map(r -> (String) r.columns().get("value"))
                        .findFirst()
                        .orElse(null);
                resolved.put("mrn", mrn);
                log.debug("HDC-221: Resolved mrn='{}' for patient fhir_id={}", mrn, patientFhirKey.fhirId());
            }
        }

        return resolved;
    }

    private FhirKey findPatientFhirKey(TableRow patientRow) {
        String fhirId = (String) patientRow.columns().get("fhir_id");
        return fhirId != null ? new FhirKey("Patient", fhirId) : null;
    }

    /** HDC-221: Maps table name to FHIR resourceType for PK map key construction. */
    private static String resourceType(String tableName) {
        return switch (tableName) {
            case "bundle"              -> "Bundle";
            case "patient"             -> "Patient";
            case "organization"        -> "Organization";
            case "practitioner"        -> "Practitioner";
            case "medication"          -> "Medication";
            case "condition"           -> "Condition";
            case "medication_request"  -> "MedicationRequest";
            case "medication_dispense" -> "MedicationDispense";
            case "communication"       -> "Communication";
            default                    -> tableName;
        };
    }
}
