# Medication History FHIR mapper

Standalone Java 21 mapping code for the `medication_history` PostgreSQL DDL.

`FhirBundleRelationalMapper` accepts either a single FHIR searchset Bundle or an
array of Bundles (the format produced when a downloader combines pages). It
returns `TableRow` records whose table name and column names match the DDL.

Example:

```java
var mapper = new FhirBundleRelationalMapper(new ObjectMapper());
var mappedBundles = mapper.map(fhirJson);

for (TableRow row : mappedBundles.getFirst().rows()) {
    System.out.println(row.table() + " -> " + row.columns());
}
```

No database connection is included. The mapper uses `FhirKey(resourceType,
fhirId)` values wherever the DDL has a generated `BIGINT` foreign key. A later
database writer should insert the `bundle` row, create a per-Bundle map from
each `FhirKey` to the generated primary key, then replace each `FhirKey` with
the matching `BIGINT` while inserting dependent rows.

The mapper covers Bundle links and entries; Patient, Organization, Practitioner,
Medication, Condition, MedicationRequest, MedicationDispense, and Communication;
and the normalized identifiers, names, telecoms, addresses, codings, extensions,
dosages, performers, prescriptions, and `basedOn` references defined in the DDL.
