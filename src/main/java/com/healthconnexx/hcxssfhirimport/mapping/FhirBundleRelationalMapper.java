package com.healthconnexx.hcxssfhirimport.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * HDC-221: Maps Surescripts Medication History for Populations FHIR R4 JSON to the
 * normalized tables in medication_history_populations_postgresql.sql.
 *
 * <p>This class has no JDBC, JPA, jOOQ, or Spring dependency. The writer inserts rows
 * in dependency order, resolving each FhirKey to the generated BIGINT PK after insertion.
 *
 * <p>Ported from /resources/medication-history-fhir-mapper/ with package update only.
 */
public final class FhirBundleRelationalMapper {

    private static final String BUNDLE = "Bundle";
    private final ObjectMapper objectMapper;

    public FhirBundleRelationalMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Maps one Bundle JSON string, or a JSON array of multiple Bundles. */
    public List<MappedBundle> map(String json) throws java.io.IOException {
        JsonNode root = objectMapper.readTree(json);
        if (root.isArray()) {
            List<MappedBundle> result = new ArrayList<>();
            for (JsonNode bundle : root) result.add(mapBundle(bundle));
            return List.copyOf(result);
        }
        return List.of(mapBundle(root));
    }

    public MappedBundle mapBundle(JsonNode bundle) {
        requireType(bundle, BUNDLE);
        FhirKey bundleKey = key(bundle);
        List<TableRow> rows = new ArrayList<>();

        rows.add(row("bundle", m(
                "fhir_id", bundleKey.fhirId(),
                "bundle_type", text(bundle, "type"),
                "total", integer(bundle, "total"),
                "timestamp", dateTime(bundle, "timestamp"),
                "source_payload", bundle.toString())));

        addBundleLinks(bundle, bundleKey, rows);
        addBundleResources(bundle, bundleKey, rows);

        for (JsonNode entry : array(bundle, "entry")) {
            JsonNode resource = entry.path("resource");
            if (!resource.isObject()) continue;
            switch (text(resource, "resourceType")) {
                case "Patient"             -> mapPatient(bundleKey, resource, rows);
                case "Organization"        -> mapOrganization(bundleKey, resource, rows);
                case "Practitioner"        -> mapPractitioner(bundleKey, resource, rows);
                case "Medication"          -> mapMedication(bundleKey, resource, rows);
                case "Condition"           -> mapCondition(bundleKey, resource, rows);
                case "MedicationRequest"   -> mapMedicationRequest(bundleKey, resource, rows);
                case "MedicationDispense"  -> mapMedicationDispense(bundleKey, resource, rows);
                case "Communication"       -> mapCommunication(bundleKey, resource, entry, rows);
                default -> { /* BundleResource retains unknown resource types for audit. */ }
            }
        }
        return new MappedBundle(bundleKey, List.copyOf(rows));
    }

    // HDC-221: Extract the population-id from a Communication resource's identifier array.
    public static Optional<String> extractPopulationId(JsonNode bundle) {
        for (JsonNode entry : array(bundle, "entry")) {
            JsonNode resource = entry.path("resource");
            if (!"Communication".equals(text(resource, "resourceType"))) continue;
            for (JsonNode identifier : array(resource, "identifier")) {
                if ("http://fhirdocs.surescripts.net/identifiers/population-id"
                        .equals(text(identifier, "system"))) {
                    String value = text(identifier, "value");
                    if (value != null) return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private void addBundleLinks(JsonNode bundle, FhirKey bundleKey, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode link : array(bundle, "link")) {
            rows.add(row("bundle_link", m("bundle_id", bundleKey, "relation", text(link, "relation"),
                    "url", text(link, "url"), "ordinal", ordinal++)));
        }
    }

    private void addBundleResources(JsonNode bundle, FhirKey bundleKey, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode entry : array(bundle, "entry")) {
            JsonNode resource = entry.path("resource");
            if (!resource.isObject() || text(resource, "resourceType") == null || text(resource, "id") == null) continue;
            rows.add(row("bundle_resource", m(
                    "bundle_id", bundleKey, "ordinal", ordinal, "full_url", text(entry, "fullUrl"),
                    "resource_type", text(resource, "resourceType"), "resource_fhir_id", text(resource, "id"),
                    "search_mode", text(entry.path("search"), "mode"), "source_payload", resource.toString())));
            ordinal++;
        }
    }

    private void mapPatient(FhirKey bundle, JsonNode r, List<TableRow> rows) {
        FhirKey owner = key(r);
        // HDC-221: mrn is resolved by FhirImportWriter using product.file_config.DTL.AssigningAuthority
        rows.add(resourceRow("patient", bundle, owner, r, m(
                "mrn", null,
                "active", bool(r, "active"), "gender", text(r, "gender"), "birth_date", localDate(r, "birthDate"))));
        identifiers("patient_identifier", "patient_id", bundle, owner, r, rows);
        names("patient_name", "patient_id", bundle, owner, r, rows);
        telecoms("patient_telecom", "patient_id", bundle, owner, r, rows);
        addresses("patient_address", "patient_id", bundle, owner, r, rows);
    }

    private void mapOrganization(FhirKey bundle, JsonNode r, List<TableRow> rows) {
        FhirKey owner = key(r);
        rows.add(resourceRow("organization", bundle, owner, r, m("active", bool(r, "active"), "name", text(r, "name"))));
        identifiers("organization_identifier", "organization_id", bundle, owner, r, rows);
        telecoms("organization_telecom", "organization_id", bundle, owner, r, rows);
        addresses("organization_address", "organization_id", bundle, owner, r, rows);
    }

    private void mapPractitioner(FhirKey bundle, JsonNode r, List<TableRow> rows) {
        FhirKey owner = key(r);
        rows.add(resourceRow("practitioner", bundle, owner, r, m("active", bool(r, "active"))));
        identifiers("practitioner_identifier", "practitioner_id", bundle, owner, r, rows);
        names("practitioner_name", "practitioner_id", bundle, owner, r, rows);
        telecoms("practitioner_telecom", "practitioner_id", bundle, owner, r, rows);
        addresses("practitioner_address", "practitioner_id", bundle, owner, r, rows);
    }

    private void mapMedication(FhirKey bundle, JsonNode r, List<TableRow> rows) {
        FhirKey owner = key(r);
        rows.add(resourceRow("medication", bundle, owner, r, m()));
        identifiers("medication_identifier", "medication_id", bundle, owner, r, rows);
        codings("medication_code_coding", "medication_id", bundle, owner, r.path("code").path("coding"), rows);
    }

    private void mapCondition(FhirKey bundle, JsonNode r, List<TableRow> rows) {
        FhirKey owner = key(r);
        rows.add(resourceRow("condition", bundle, owner, r, m(
                "patient_id", reference(r.path("subject")))));
        identifiers("condition_identifier", "condition_id", bundle, owner, r, rows);
        codings("condition_code_coding", "condition_id", bundle, owner, r.path("code").path("coding"), rows);
        codings("condition_category_coding", "condition_id", bundle, owner, r.path("category").path("coding"), rows);
    }

    private void mapMedicationRequest(FhirKey bundle, JsonNode r, List<TableRow> rows) {
        FhirKey owner = key(r);
        JsonNode dispenseRequest = r.path("dispenseRequest");
        JsonNode quantity = dispenseRequest.path("quantity");
        rows.add(resourceRow("medication_request", bundle, owner, r, m(
                "status", text(r, "status"), "intent", text(r, "intent"),
                "medication_id", reference(r.path("medicationReference")),
                "patient_id", reference(r.path("subject")),
                "requester_practitioner_id", reference(r.path("requester")),
                "reason_condition_id", firstReference(r, "reasonReference"),
                "authored_on", dateTime(r, "authoredOn"),
                "repeats_allowed", integer(dispenseRequest, "numberOfRepeatsAllowed"),
                "dispense_quantity_value", decimal(quantity, "value"),
                "dispense_quantity_unit", text(quantity, "unit"))));
        identifiers("medication_request_identifier", "medication_request_id", bundle, owner, r, rows);
        int ordinal = 0;
        for (JsonNode performer : array(r, "performer")) {
            rows.add(row("medication_request_performer", m("bundle_id", bundle,
                    "medication_request_id", owner, "organization_id", reference(performer.path("actor")), "ordinal", ordinal++)));
        }
    }

    private void mapMedicationDispense(FhirKey bundle, JsonNode r, List<TableRow> rows) {
        FhirKey owner = key(r);
        JsonNode quantity = r.path("quantity");
        JsonNode daysSupply = r.path("daysSupply");
        rows.add(resourceRow("medication_dispense", bundle, owner, r, m(
                "status", text(r, "status"), "medication_id", reference(r.path("medicationReference")),
                "patient_id", reference(r.path("subject")),
                "quantity_value", decimal(quantity, "value"), "quantity_unit", text(quantity, "unit"),
                "quantity_system", text(quantity, "system"), "quantity_code", text(quantity, "code"),
                "days_supply_value", decimal(daysSupply, "value"), "days_supply_unit", text(daysSupply, "unit"),
                "days_supply_system", text(daysSupply, "system"), "days_supply_code", text(daysSupply, "code"),
                "when_prepared", fhirDateTime(r, "whenPrepared"), "when_handed_over", fhirDateTime(r, "whenHandedOver"))));
        identifiers("medication_dispense_identifier", "medication_dispense_id", bundle, owner, r, rows);
        simpleCodings("medication_dispense_type_coding", "medication_dispense_id", bundle, owner, r.path("type").path("coding"), rows);
        extensions("medication_dispense_extension", "medication_dispense_id", bundle, owner, r, rows);
        int ordinal = 0;
        for (JsonNode performer : array(r, "performer")) {
            rows.add(row("medication_dispense_performer", m("bundle_id", bundle,
                    "medication_dispense_id", owner, "organization_id", reference(performer.path("actor")), "ordinal", ordinal++)));
        }
        ordinal = 0;
        for (JsonNode prescription : array(r, "authorizingPrescription")) {
            rows.add(row("medication_dispense_prescription", m("bundle_id", bundle,
                    "medication_dispense_id", owner, "medication_request_id", reference(prescription), "ordinal", ordinal++)));
        }
        ordinal = 0;
        for (JsonNode dosage : array(r, "dosageInstruction")) {
            rows.add(row("medication_dispense_dosage_instruction", m("bundle_id", bundle,
                    "medication_dispense_id", owner, "ordinal", ordinal++, "text", text(dosage, "text"))));
        }
    }

    private void mapCommunication(FhirKey bundle, JsonNode r, JsonNode entry, List<TableRow> rows) {
        FhirKey owner = key(r);
        rows.add(resourceRow("communication", bundle, owner, r, m(
                "status", text(r, "status"), "patient_id", reference(r.path("subject")),
                "received", dateTime(r, "received"), "search_mode", text(entry.path("search"), "mode"))));
        identifiers("communication_identifier", "communication_id", bundle, owner, r, rows);
        simpleCodings("communication_category_coding", "communication_id", bundle, owner, r.path("category").path(0).path("coding"), rows);
        simpleCodings("communication_reason_coding", "communication_id", bundle, owner, r.path("reasonCode").path(0).path("coding"), rows);
        extensions("communication_extension", "communication_id", bundle, owner, r, rows);
        int ordinal = 0;
        for (JsonNode basedOn : array(r, "basedOn")) {
            FhirKey target = reference(basedOn);
            Map<String, Object> columns = m("bundle_id", bundle, "communication_id", owner, "ordinal", ordinal++);
            if (target != null && "MedicationDispense".equals(target.resourceType())) columns.put("medication_dispense_id", target);
            if (target != null && "MedicationRequest".equals(target.resourceType())) columns.put("medication_request_id", target);
            rows.add(row("communication_based_on", columns));
        }
    }

    private TableRow resourceRow(String table, FhirKey bundle, FhirKey resource, JsonNode r, Map<String, Object> extra) {
        Map<String, Object> columns = m("bundle_id", bundle, "fhir_id", resource.fhirId(),
                "meta_version_id", text(r.path("meta"), "versionId"),
                "meta_last_updated", dateTime(r.path("meta"), "lastUpdated"), "source_payload", r.toString());
        columns.putAll(extra);
        return row(table, columns);
    }

    private void identifiers(String table, String ownerColumn, FhirKey bundle, FhirKey owner, JsonNode r, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode identifier : array(r, "identifier")) {
            JsonNode period = identifier.path("period");
            rows.add(row(table, m("bundle_id", bundle, ownerColumn, owner, "use_code", text(identifier, "use"),
                    "type_text", text(identifier.path("type"), "text"), "system_uri", text(identifier, "system"),
                    "value", text(identifier, "value"), "period_start", localDate(period, "start"),
                    "period_end", localDate(period, "end"), "ordinal", ordinal++)));
        }
    }

    private void names(String table, String ownerColumn, FhirKey bundle, FhirKey owner, JsonNode r, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode name : array(r, "name")) {
            rows.add(row(table, m("bundle_id", bundle, ownerColumn, owner, "use_code", text(name, "use"),
                    "text_value", text(name, "text"), "family", text(name, "family"),
                    "prefix_values", stringList(name, "prefix"), "given_values", stringList(name, "given"),
                    "suffix_values", stringList(name, "suffix"), "ordinal", ordinal++)));
        }
    }

    private void telecoms(String table, String ownerColumn, FhirKey bundle, FhirKey owner, JsonNode r, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode telecom : array(r, "telecom")) {
            rows.add(row(table, m("bundle_id", bundle, ownerColumn, owner, "system_code", text(telecom, "system"),
                    "value", text(telecom, "value"), "use_code", text(telecom, "use"),
                    "rank_value", integer(telecom, "rank"), "ordinal", ordinal++)));
        }
    }

    private void addresses(String table, String ownerColumn, FhirKey bundle, FhirKey owner, JsonNode r, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode address : array(r, "address")) {
            JsonNode period = address.path("period");
            rows.add(row(table, m("bundle_id", bundle, ownerColumn, owner, "use_code", text(address, "use"),
                    "type_code", text(address, "type"), "text_value", text(address, "text"), "line_values", stringList(address, "line"),
                    "city", text(address, "city"), "district", text(address, "district"), "state", text(address, "state"),
                    "postal_code", text(address, "postalCode"), "country", text(address, "country"),
                    "period_start", localDate(period, "start"), "period_end", localDate(period, "end"), "ordinal", ordinal++)));
        }
    }

    private void codings(String table, String ownerColumn, FhirKey bundle, FhirKey owner, JsonNode codingArray, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode coding : asArray(codingArray)) {
            rows.add(row(table, m("bundle_id", bundle, ownerColumn, owner, "system_uri", text(coding, "system"),
                    "version", text(coding, "version"), "code", text(coding, "code"), "display", text(coding, "display"),
                    "user_selected", bool(coding, "userSelected"), "ordinal", ordinal++)));
        }
    }

    private void simpleCodings(String table, String ownerColumn, FhirKey bundle, FhirKey owner, JsonNode codingArray, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode coding : asArray(codingArray)) {
            rows.add(row(table, m("bundle_id", bundle, ownerColumn, owner, "system_uri", text(coding, "system"),
                    "code", text(coding, "code"), "display", text(coding, "display"), "ordinal", ordinal++)));
        }
    }

    private void extensions(String table, String ownerColumn, FhirKey bundle, FhirKey owner, JsonNode r, List<TableRow> rows) {
        int ordinal = 0;
        for (JsonNode extension : array(r, "extension")) {
            JsonNode coding = extension.path("valueCoding");
            rows.add(row(table, m("bundle_id", bundle, ownerColumn, owner, "url", text(extension, "url"),
                    "value_string", text(extension, "valueString"), "value_code", text(extension, "valueCode"),
                    "value_datetime", dateTime(extension, "valueDateTime"), "value_date", localDate(extension, "valueDate"),
                    "value_coding_system", text(coding, "system"), "value_coding_code", text(coding, "code"),
                    "value_coding_display", text(coding, "display"), "value_coding_id", text(coding, "id"), "ordinal", ordinal++)));
        }
    }

    private static FhirKey key(JsonNode resource) {
        String type = text(resource, "resourceType");
        String id = text(resource, "id");
        if (type == null || id == null) throw new IllegalArgumentException("FHIR resource must have resourceType and id");
        return new FhirKey(type, id);
    }

    private static FhirKey reference(JsonNode reference) {
        String raw = text(reference, "reference");
        if (raw == null || raw.isBlank() || raw.startsWith("#")) return null;
        String normalized = raw.replaceAll("/+$", "");
        String[] parts = normalized.split("/");
        if (parts.length < 2) throw new IllegalArgumentException("Unsupported FHIR reference: " + raw);
        return new FhirKey(parts[parts.length - 2], parts[parts.length - 1]);
    }

    private static FhirKey firstReference(JsonNode resource, String field) {
        JsonNode value = resource.path(field);
        return value.isArray() ? (value.isEmpty() ? null : reference(value.get(0))) : reference(value);
    }

    private static TableRow row(String table, Map<String, Object> columns) { return new TableRow(table, columns); }
    private static List<JsonNode> array(JsonNode node, String field) { return asArray(node.path(field)); }
    private static List<JsonNode> asArray(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<JsonNode> values = new ArrayList<>(); node.forEach(values::add); return values;
    }
    private static String text(JsonNode node, String field) { return node.path(field).isValueNode() ? node.path(field).asText(null) : null; }
    private static Boolean bool(JsonNode node, String field) { return node.path(field).isBoolean() ? node.path(field).booleanValue() : null; }
    private static Integer integer(JsonNode node, String field) { return node.path(field).isInt() ? node.path(field).intValue() : null; }
    private static BigDecimal decimal(JsonNode node, String field) { return node.path(field).isNumber() ? node.path(field).decimalValue() : null; }
    private static LocalDate localDate(JsonNode node, String field) { String v = text(node, field); return v == null || v.isBlank() ? null : LocalDate.parse(v); }
    private static OffsetDateTime dateTime(JsonNode node, String field) { String v = text(node, field); return v == null || v.isBlank() ? null : OffsetDateTime.parse(v); }
    private static OffsetDateTime fhirDateTime(JsonNode node, String field) {
        String v = text(node, field); if (v == null || v.isBlank()) return null;
        return v.length() == 10 ? LocalDate.parse(v).atStartOfDay().atOffset(ZoneOffset.UTC) : OffsetDateTime.parse(v);
    }
    private static List<String> stringList(JsonNode node, String field) {
        return array(node, field).stream().filter(JsonNode::isTextual).map(JsonNode::textValue).toList();
    }
    private static void requireType(JsonNode node, String expected) {
        if (!expected.equals(text(node, "resourceType"))) throw new IllegalArgumentException("Expected " + expected + " resource");
    }
    private static Map<String, Object> m(Object... values) {
        if (values.length % 2 != 0) throw new IllegalArgumentException("Expected name/value pairs");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
        return map;
    }
}
