-- Medication History for Populations (Surescripts) PostgreSQL schema
-- Based on Medication History for Populations Implementation Guide v1.1.1
-- Section 7.4.1 through 7.4.8 (pages 46-70); Bundle behavior: Section 8.5.
--
--- Medication History for Populations (Surescripts) PostgreSQL schema
-- Based on Medication History for Populations Implementation Guide v1.1.1
-- Section 7.4.1 through 7.4.8 (pages 46-70); Bundle behavior: Section 8.5.
--
-- Design notes
-- * bundle is the root record for one received FHIR searchset response.
-- * Every materialized FHIR resource and every normalized child row carries
--   bundle_id, allowing audit and deletion by received response.
-- * fhir_id is TEXT rather than UUID because FHIR ids are not universally UUIDs.
-- * source_payload retains the received fragment for audit/forward compatibility;
--   the relational columns are the queryable representation.

BEGIN;

-- Root FHIR Bundle.  This is intentionally the only table without bundle_id.
CREATE TABLE fhir.bundle (
                             bundle_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                             fhir_id                TEXT NOT NULL,
                             bundle_type            TEXT NOT NULL, -- normally searchset
                             total                  INTEGER,
                             timestamp              TIMESTAMPTZ,
                             request_identifier     TEXT, -- X-Requester-ID, if retained by the client
                             received_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                             source_payload         JSONB,
                             CONSTRAINT uq_bundle_fhir_id UNIQUE (fhir_id),
                             CONSTRAINT ck_bundle_total_nonnegative CHECK (total IS NULL OR total >= 0)
);

CREATE TABLE fhir.bundle_link (
                                  bundle_link_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                  bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                  relation               TEXT NOT NULL,
                                  url                    TEXT NOT NULL,
                                  ordinal                INTEGER NOT NULL DEFAULT 0,
                                  CONSTRAINT uq_bundle_link_ordinal UNIQUE (bundle_id, ordinal),
                                  CONSTRAINT uq_bundle_link_relation UNIQUE (bundle_id, relation)
);

-- Mirrors Bundle.entry[].  resource_type + resource_fhir_id is deliberately
-- polymorphic: the materialized resource tables provide the typed FKs.
CREATE TABLE fhir.bundle_resource (
                                      bundle_resource_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                      bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                      ordinal                INTEGER NOT NULL,
                                      full_url               TEXT,
                                      resource_type          TEXT NOT NULL,
                                      resource_fhir_id       TEXT NOT NULL,
                                      search_mode            TEXT, -- Bundle.entry.search.mode (usually match)
                                      source_payload         JSONB,
                                      CONSTRAINT uq_bundle_resource_ordinal UNIQUE (bundle_id, ordinal),
                                      CONSTRAINT uq_bundle_resource_identity UNIQUE (bundle_id, resource_type, resource_fhir_id)
);

-- Shared FHIR metadata is repeated in each resource table rather than hidden
-- in a generic parent table so that each resource remains independently loadable.
CREATE TABLE fhir.patient (
                              patient_id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                              bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                              mrn                    TEXT, --patient souce medical record number (MRN) from the EHR system
                              fhir_id                TEXT NOT NULL,
                              active                 BOOLEAN NOT NULL,
                              gender                 TEXT NOT NULL,
                              birth_date             DATE NOT NULL,
                              meta_version_id        TEXT NOT NULL,
                              meta_last_updated      TIMESTAMPTZ NOT NULL,
                              source_payload         JSONB,
                              CONSTRAINT uq_patient_bundle_fhir UNIQUE (bundle_id, fhir_id)
);

CREATE TABLE fhir.organization (
                                   organization_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                   bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                   fhir_id                TEXT NOT NULL,
                                   active                 BOOLEAN NOT NULL,
                                   name                   TEXT,
                                   meta_version_id        TEXT NOT NULL,
                                   meta_last_updated      TIMESTAMPTZ NOT NULL,
                                   source_payload         JSONB,
                                   CONSTRAINT uq_organization_bundle_fhir UNIQUE (bundle_id, fhir_id)
);

CREATE TABLE fhir.practitioner (
                                   practitioner_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                   bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                   fhir_id                TEXT NOT NULL,
                                   active                 BOOLEAN, -- not specifically required by the IG
                                   meta_version_id        TEXT NOT NULL,
                                   meta_last_updated      TIMESTAMPTZ NOT NULL,
                                   source_payload         JSONB,
                                   CONSTRAINT uq_practitioner_bundle_fhir UNIQUE (bundle_id, fhir_id)
);

CREATE TABLE fhir.medication (
                                 medication_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                 bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                 fhir_id                TEXT NOT NULL,
                                 meta_version_id        TEXT NOT NULL,
                                 meta_last_updated      TIMESTAMPTZ NOT NULL,
                                 source_payload         JSONB,
                                 CONSTRAINT uq_medication_bundle_fhir UNIQUE (bundle_id, fhir_id)
);

CREATE TABLE fhir.condition (
                                condition_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                fhir_id                TEXT NOT NULL,
                                patient_id             BIGINT,
                                meta_version_id        TEXT NOT NULL,
                                meta_last_updated      TIMESTAMPTZ NOT NULL,
                                source_payload         JSONB,
                                CONSTRAINT uq_condition_bundle_fhir UNIQUE (bundle_id, fhir_id),
                                CONSTRAINT fk_condition_patient FOREIGN KEY (patient_id)
                                    REFERENCES patient(patient_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE fhir.medication_request (
                                         medication_request_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                         bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                         fhir_id                TEXT NOT NULL,
                                         status                 TEXT NOT NULL,
                                         intent                 TEXT NOT NULL,
                                         medication_id          BIGINT NOT NULL,
                                         patient_id             BIGINT NOT NULL,
                                         requester_practitioner_id BIGINT NOT NULL,
                                         reason_condition_id    BIGINT,
                                         authored_on            TIMESTAMPTZ NOT NULL,
                                         repeats_allowed        INTEGER,
                                         dispense_quantity_value NUMERIC(18,6),
                                         dispense_quantity_unit TEXT,
                                         meta_version_id        TEXT NOT NULL,
                                         meta_last_updated      TIMESTAMPTZ NOT NULL,
                                         source_payload         JSONB,
                                         CONSTRAINT uq_medication_request_bundle_fhir UNIQUE (bundle_id, fhir_id),
                                         CONSTRAINT ck_medication_request_repeats CHECK (repeats_allowed IS NULL OR repeats_allowed >= 0),
                                         CONSTRAINT fk_medreq_medication FOREIGN KEY (medication_id)
                                             REFERENCES medication(medication_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                                         CONSTRAINT fk_medreq_patient FOREIGN KEY (patient_id)
                                             REFERENCES patient(patient_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                                         CONSTRAINT fk_medreq_requester FOREIGN KEY (requester_practitioner_id)
                                             REFERENCES practitioner(practitioner_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                                         CONSTRAINT fk_medreq_reason_condition FOREIGN KEY (reason_condition_id)
                                             REFERENCES condition(condition_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE fhir.medication_request_performer (
                                                   medication_request_performer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                   bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                   medication_request_id  BIGINT NOT NULL REFERENCES fhir.medication_request(medication_request_id) ON DELETE CASCADE,
                                                   organization_id        BIGINT NOT NULL REFERENCES fhir.organization(organization_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                                                   ordinal                INTEGER NOT NULL DEFAULT 0,
                                                   CONSTRAINT uq_medreq_performer UNIQUE (medication_request_id, ordinal)
);

CREATE TABLE fhir.medication_dispense (
                                          medication_dispense_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                          bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                          fhir_id                TEXT NOT NULL,
                                          status                 TEXT NOT NULL,
                                          medication_id          BIGINT NOT NULL,
                                          patient_id             BIGINT NOT NULL,
                                          quantity_value         NUMERIC(18,6) NOT NULL,
                                          quantity_unit          TEXT,
                                          quantity_system        TEXT,
                                          quantity_code          TEXT,
                                          days_supply_value      NUMERIC(18,6) NOT NULL,
                                          days_supply_unit       TEXT,
                                          days_supply_system     TEXT,
                                          days_supply_code       TEXT,
                                          when_prepared          TIMESTAMPTZ NOT NULL,
                                          when_handed_over       TIMESTAMPTZ,
                                          meta_version_id        TEXT NOT NULL,
                                          meta_last_updated      TIMESTAMPTZ NOT NULL,
                                          source_payload         JSONB,
                                          CONSTRAINT uq_medication_dispense_bundle_fhir UNIQUE (bundle_id, fhir_id),
                                          CONSTRAINT fk_meddisp_medication FOREIGN KEY (medication_id)
                                              REFERENCES fhir.medication(medication_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                                          CONSTRAINT fk_meddisp_patient FOREIGN KEY (patient_id)
                                              REFERENCES fhir.patient(patient_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE fhir.medication_dispense_performer (
                                                    medication_dispense_performer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                    bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                    medication_dispense_id BIGINT NOT NULL REFERENCES fhir.medication_dispense(medication_dispense_id) ON DELETE CASCADE,
                                                    organization_id        BIGINT NOT NULL REFERENCES fhir.organization(organization_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                                                    ordinal                INTEGER NOT NULL DEFAULT 0,
                                                    CONSTRAINT uq_meddisp_performer UNIQUE (medication_dispense_id, ordinal)
);

CREATE TABLE fhir.medication_dispense_prescription (
                                                       medication_dispense_prescription_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                       bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                       medication_dispense_id BIGINT NOT NULL REFERENCES fhir.medication_dispense(medication_dispense_id) ON DELETE CASCADE,
                                                       medication_request_id  BIGINT NOT NULL REFERENCES fhir.medication_request(medication_request_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                                                       ordinal                INTEGER NOT NULL DEFAULT 0,
                                                       CONSTRAINT uq_meddisp_prescription UNIQUE (medication_dispense_id, ordinal)
);

CREATE TABLE fhir.medication_dispense_type_coding (
                                                      medication_dispense_type_coding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                      bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                      medication_dispense_id BIGINT NOT NULL REFERENCES fhir.medication_dispense(medication_dispense_id) ON DELETE CASCADE,
                                                      system_uri             TEXT,
                                                      code                   TEXT,
                                                      display                TEXT,
                                                      ordinal                INTEGER NOT NULL DEFAULT 0,
                                                      CONSTRAINT uq_meddisp_type_coding UNIQUE (medication_dispense_id, ordinal)
);

CREATE TABLE fhir.medication_dispense_dosage_instruction (
                                                             medication_dispense_dosage_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                             bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                             medication_dispense_id BIGINT NOT NULL REFERENCES fhir.medication_dispense(medication_dispense_id) ON DELETE CASCADE,
                                                             ordinal                INTEGER NOT NULL DEFAULT 0,
                                                             text                   TEXT,
                                                             CONSTRAINT uq_meddisp_dosage UNIQUE (medication_dispense_id, ordinal)
);

CREATE TABLE fhir.medication_dispense_extension (
                                                    medication_dispense_extension_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                    bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                    medication_dispense_id BIGINT NOT NULL REFERENCES fhir.medication_dispense(medication_dispense_id) ON DELETE CASCADE,
                                                    url                    TEXT NOT NULL,
                                                    value_string           TEXT,
                                                    value_code             TEXT,
                                                    value_datetime         TIMESTAMPTZ,
                                                    value_date             DATE,
                                                    value_coding_system    TEXT,
                                                    value_coding_code      TEXT,
                                                    value_coding_display   TEXT,
                                                    value_coding_id        TEXT,
                                                    ordinal                INTEGER NOT NULL DEFAULT 0,
                                                    CONSTRAINT uq_meddisp_extension UNIQUE (medication_dispense_id, ordinal)
);

CREATE TABLE fhir.communication (
                                    communication_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                    bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                    fhir_id                TEXT NOT NULL,
                                    status                 TEXT NOT NULL,
                                    patient_id             BIGINT,
                                    received               TIMESTAMPTZ NOT NULL,
                                    search_mode            TEXT NOT NULL, -- Communication.search.mode; IG requires match
                                    meta_version_id        TEXT NOT NULL,
                                    meta_last_updated      TIMESTAMPTZ NOT NULL,
                                    source_payload         JSONB,
                                    CONSTRAINT uq_communication_bundle_fhir UNIQUE (bundle_id, fhir_id),
                                    CONSTRAINT fk_communication_patient FOREIGN KEY (patient_id)
                                        REFERENCES patient(patient_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

-- A Communication can point to either MedicationDispense or MedicationRequest.
CREATE TABLE fhir.communication_based_on (
                                             communication_based_on_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                             bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                             communication_id       BIGINT NOT NULL REFERENCES fhir.communication(communication_id) ON DELETE CASCADE,
                                             medication_dispense_id BIGINT,
                                             medication_request_id  BIGINT,
                                             ordinal                INTEGER NOT NULL DEFAULT 0,
                                             CONSTRAINT uq_communication_based_on UNIQUE (communication_id, ordinal),
                                             CONSTRAINT ck_communication_based_on_one_target CHECK (
                                                 (medication_dispense_id IS NOT NULL AND medication_request_id IS NULL) OR
                                                 (medication_dispense_id IS NULL AND medication_request_id IS NOT NULL)
                                                 ),
                                             CONSTRAINT fk_comm_based_on_meddisp FOREIGN KEY (medication_dispense_id)
                                                 REFERENCES fhir.medication_dispense(medication_dispense_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                                             CONSTRAINT fk_comm_based_on_medreq FOREIGN KEY (medication_request_id)
                                                 REFERENCES fhir.medication_request(medication_request_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE fhir.communication_category_coding (
                                                    communication_category_coding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                    bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                    communication_id       BIGINT NOT NULL REFERENCES fhir.communication(communication_id) ON DELETE CASCADE,
                                                    system_uri             TEXT,
                                                    code                   TEXT,
                                                    display                TEXT,
                                                    ordinal                INTEGER NOT NULL DEFAULT 0,
                                                    CONSTRAINT uq_comm_category_coding UNIQUE (communication_id, ordinal)
);

CREATE TABLE fhir.communication_reason_coding (
                                                  communication_reason_coding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                  bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                  communication_id       BIGINT NOT NULL REFERENCES fhir.communication(communication_id) ON DELETE CASCADE,
                                                  system_uri             TEXT,
                                                  code                   TEXT,
                                                  display                TEXT,
                                                  ordinal                INTEGER NOT NULL DEFAULT 0,
                                                  CONSTRAINT uq_comm_reason_coding UNIQUE (communication_id, ordinal)
);

CREATE TABLE fhir.communication_extension (
                                              communication_extension_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                              bundle_id              BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                              communication_id       BIGINT NOT NULL REFERENCES fhir.communication(communication_id) ON DELETE CASCADE,
                                              url                    TEXT NOT NULL,
                                              value_string           TEXT,
                                              value_code             TEXT,
                                              value_datetime         TIMESTAMPTZ,
                                              value_date             DATE,
                                              value_coding_system    TEXT,
                                              value_coding_code      TEXT,
                                              value_coding_display   TEXT,
                                              value_coding_id        TEXT,
                                              ordinal                INTEGER NOT NULL DEFAULT 0,
                                              CONSTRAINT uq_communication_extension UNIQUE (communication_id, ordinal)
);

-- Repeating FHIR identifiers, names, telecoms, addresses, and CodeableConcept.codings.
CREATE TABLE fhir.patient_identifier (
                                         patient_identifier_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                         bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                         patient_id BIGINT NOT NULL REFERENCES fhir.patient(patient_id) ON DELETE CASCADE,
                                         use_code TEXT, type_text TEXT, system_uri TEXT, value TEXT NOT NULL,
                                         period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0,
                                         CONSTRAINT uq_patient_identifier UNIQUE (patient_id, ordinal)
);
CREATE TABLE fhir.organization_identifier (
                                              organization_identifier_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                              bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                              organization_id BIGINT NOT NULL REFERENCES fhir.organization(organization_id) ON DELETE CASCADE,
                                              use_code TEXT, type_text TEXT, system_uri TEXT, value TEXT NOT NULL,
                                              period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0,
                                              CONSTRAINT uq_organization_identifier UNIQUE (organization_id, ordinal)
);
CREATE TABLE fhir.practitioner_identifier (
                                              practitioner_identifier_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                              bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                              practitioner_id BIGINT NOT NULL REFERENCES fhir.practitioner(practitioner_id) ON DELETE CASCADE,
                                              use_code TEXT, type_text TEXT, system_uri TEXT, value TEXT NOT NULL,
                                              period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0,
                                              CONSTRAINT uq_practitioner_identifier UNIQUE (practitioner_id, ordinal)
);
CREATE TABLE fhir.medication_identifier (
                                            medication_identifier_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                            bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                            medication_id BIGINT NOT NULL REFERENCES fhir.medication(medication_id) ON DELETE CASCADE,
                                            use_code TEXT, type_text TEXT, system_uri TEXT, value TEXT NOT NULL,
                                            period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0,
                                            CONSTRAINT uq_medication_identifier UNIQUE (medication_id, ordinal)
);
CREATE TABLE fhir.condition_identifier (
                                           condition_identifier_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                           bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                           condition_id BIGINT NOT NULL REFERENCES fhir.condition(condition_id) ON DELETE CASCADE,
                                           use_code TEXT, type_text TEXT, system_uri TEXT, value TEXT NOT NULL,
                                           period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0,
                                           CONSTRAINT uq_condition_identifier UNIQUE (condition_id, ordinal)
);
CREATE TABLE fhir.medication_request_identifier (
                                                    medication_request_identifier_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                    bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                    medication_request_id BIGINT NOT NULL REFERENCES fhir.medication_request(medication_request_id) ON DELETE CASCADE,
                                                    use_code TEXT, type_text TEXT, system_uri TEXT, value TEXT NOT NULL,
                                                    period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0,
                                                    CONSTRAINT uq_medreq_identifier UNIQUE (medication_request_id, ordinal)
);
CREATE TABLE fhir.medication_dispense_identifier (
                                                     medication_dispense_identifier_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                     bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                     medication_dispense_id BIGINT NOT NULL REFERENCES fhir.medication_dispense(medication_dispense_id) ON DELETE CASCADE,
                                                     use_code TEXT, type_text TEXT, system_uri TEXT, value TEXT NOT NULL,
                                                     period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0,
                                                     CONSTRAINT uq_meddisp_identifier UNIQUE (medication_dispense_id, ordinal)
);
CREATE TABLE fhir.communication_identifier (
                                               communication_identifier_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                               bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                               communication_id BIGINT NOT NULL REFERENCES fhir.communication(communication_id) ON DELETE CASCADE,
                                               use_code TEXT, type_text TEXT, system_uri TEXT, value TEXT NOT NULL,
                                               period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0,
                                               CONSTRAINT uq_comm_identifier UNIQUE (communication_id, ordinal)
);

CREATE TABLE fhir.patient_name (
                                   patient_name_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                   bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                   patient_id BIGINT NOT NULL REFERENCES fhir.patient(patient_id) ON DELETE CASCADE,
                                   use_code TEXT, text_value TEXT, family TEXT, prefix_values TEXT[], given_values TEXT[], suffix_values TEXT[],
                                   ordinal INTEGER NOT NULL DEFAULT 0, CONSTRAINT uq_patient_name UNIQUE (patient_id, ordinal)
);
CREATE TABLE fhir.practitioner_name (
                                        practitioner_name_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                        bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                        practitioner_id BIGINT NOT NULL REFERENCES fhir.practitioner(practitioner_id) ON DELETE CASCADE,
                                        use_code TEXT, text_value TEXT, family TEXT, prefix_values TEXT[], given_values TEXT[], suffix_values TEXT[],
                                        ordinal INTEGER NOT NULL DEFAULT 0, CONSTRAINT uq_practitioner_name UNIQUE (practitioner_id, ordinal)
);

CREATE TABLE fhir.patient_telecom (
                                      patient_telecom_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                      bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                      patient_id BIGINT NOT NULL REFERENCES fhir.patient(patient_id) ON DELETE CASCADE,
                                      system_code TEXT, value TEXT NOT NULL, use_code TEXT, rank_value INTEGER, ordinal INTEGER NOT NULL DEFAULT 0,
                                      CONSTRAINT uq_patient_telecom UNIQUE (patient_id, ordinal)
);
CREATE TABLE fhir.organization_telecom (
                                           organization_telecom_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                           bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                           organization_id BIGINT NOT NULL REFERENCES fhir.organization(organization_id) ON DELETE CASCADE,
                                           system_code TEXT, value TEXT NOT NULL, use_code TEXT, rank_value INTEGER, ordinal INTEGER NOT NULL DEFAULT 0,
                                           CONSTRAINT uq_organization_telecom UNIQUE (organization_id, ordinal)
);
CREATE TABLE fhir.practitioner_telecom (
                                           practitioner_telecom_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                           bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                           practitioner_id BIGINT NOT NULL REFERENCES fhir.practitioner(practitioner_id) ON DELETE CASCADE,
                                           system_code TEXT, value TEXT NOT NULL, use_code TEXT, rank_value INTEGER, ordinal INTEGER NOT NULL DEFAULT 0,
                                           CONSTRAINT uq_practitioner_telecom UNIQUE (practitioner_id, ordinal)
);

CREATE TABLE fhir.patient_address (
                                      patient_address_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                      bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                      patient_id BIGINT NOT NULL REFERENCES fhir.patient(patient_id) ON DELETE CASCADE,
                                      use_code TEXT, type_code TEXT, text_value TEXT, line_values TEXT[], city TEXT, district TEXT, state TEXT, postal_code TEXT, country TEXT,
                                      period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0, CONSTRAINT uq_patient_address UNIQUE (patient_id, ordinal)
);
CREATE TABLE fhir.organization_address (
                                           organization_address_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                           bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                           organization_id BIGINT NOT NULL REFERENCES fhir.organization(organization_id) ON DELETE CASCADE,
                                           use_code TEXT, type_code TEXT, text_value TEXT, line_values TEXT[], city TEXT, district TEXT, state TEXT, postal_code TEXT, country TEXT,
                                           period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0, CONSTRAINT uq_organization_address UNIQUE (organization_id, ordinal)
);
CREATE TABLE fhir.practitioner_address (
                                           practitioner_address_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                           bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                           practitioner_id BIGINT NOT NULL REFERENCES fhir.practitioner(practitioner_id) ON DELETE CASCADE,
                                           use_code TEXT, type_code TEXT, text_value TEXT, line_values TEXT[], city TEXT, district TEXT, state TEXT, postal_code TEXT, country TEXT,
                                           period_start DATE, period_end DATE, ordinal INTEGER NOT NULL DEFAULT 0, CONSTRAINT uq_practitioner_address UNIQUE (practitioner_id, ordinal)
);

CREATE TABLE fhir.medication_code_coding (
                                             medication_code_coding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                             bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                             medication_id BIGINT NOT NULL REFERENCES fhir.medication(medication_id) ON DELETE CASCADE,
                                             system_uri TEXT, version TEXT, code TEXT, display TEXT, user_selected BOOLEAN, ordinal INTEGER NOT NULL DEFAULT 0,
                                             CONSTRAINT uq_medication_code_coding UNIQUE (medication_id, ordinal)
);
CREATE TABLE fhir.condition_code_coding (
                                            condition_code_coding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                            bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                            condition_id BIGINT NOT NULL REFERENCES fhir.condition(condition_id) ON DELETE CASCADE,
                                            system_uri TEXT, version TEXT, code TEXT, display TEXT, user_selected BOOLEAN, ordinal INTEGER NOT NULL DEFAULT 0,
                                            CONSTRAINT uq_condition_code_coding UNIQUE (condition_id, ordinal)
);
CREATE TABLE fhir.condition_category_coding (
                                                condition_category_coding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                bundle_id BIGINT NOT NULL REFERENCES fhir.bundle(bundle_id) ON DELETE CASCADE,
                                                condition_id BIGINT NOT NULL REFERENCES fhir.condition(condition_id) ON DELETE CASCADE,
                                                system_uri TEXT, version TEXT, code TEXT, display TEXT, user_selected BOOLEAN, ordinal INTEGER NOT NULL DEFAULT 0,
                                                CONSTRAINT uq_condition_category_coding UNIQUE (condition_id, ordinal)
);

-- Operational indexes: bundle traversal, FHIR ID resolution, patient history,
-- and common identifier/code searches.  PK/unique constraints create their own indexes.
CREATE INDEX ix_bundle_resource_type_fhir ON fhir.bundle_resource (bundle_id, resource_type, resource_fhir_id);
CREATE INDEX ix_patient_bundle_fhir ON fhir.patient (bundle_id, fhir_id);
CREATE INDEX ix_organization_bundle_fhir ON fhir.organization (bundle_id, fhir_id);
CREATE INDEX ix_practitioner_bundle_fhir ON fhir.practitioner (bundle_id, fhir_id);
CREATE INDEX ix_medication_bundle_fhir ON fhir.medication (bundle_id, fhir_id);
CREATE INDEX ix_condition_patient ON fhir.condition (bundle_id, patient_id);
CREATE INDEX ix_medreq_patient_authored ON fhir.medication_request (bundle_id, patient_id, authored_on DESC);
CREATE INDEX ix_meddisp_patient_prepared ON fhir.medication_dispense (bundle_id, patient_id, when_prepared DESC);
CREATE INDEX ix_communication_patient_received ON fhir.communication (bundle_id, patient_id, received DESC);
CREATE INDEX ix_patient_identifier_lookup ON fhir.patient_identifier (system_uri, value);
CREATE INDEX ix_organization_identifier_lookup ON fhir.organization_identifier (system_uri, value);
CREATE INDEX ix_practitioner_identifier_lookup ON fhir.practitioner_identifier (system_uri, value);
CREATE INDEX ix_medreq_identifier_lookup ON fhir.medication_request_identifier (system_uri, value);
CREATE INDEX ix_meddisp_identifier_lookup ON fhir.medication_dispense_identifier (system_uri, value);
CREATE INDEX ix_comm_identifier_lookup ON fhir.communication_identifier (system_uri, value);
CREATE INDEX ix_medication_code_lookup ON fhir.medication_code_coding (system_uri, code);
CREATE INDEX ix_condition_code_lookup ON fhir.condition_code_coding (system_uri, code);
COMMIT;
