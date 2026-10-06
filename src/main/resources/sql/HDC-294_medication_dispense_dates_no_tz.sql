-- HDC-294: when_prepared/when_handed_over were TIMESTAMPTZ, which normalizes to UTC
-- internally and renders in the reading session's timezone — making the FHIR literal
-- date appear to shift (e.g. 2026-08-26T00:00:00+00:00 displayed as 2026-08-25 20:00 -0400).
-- Switch both columns to TIMESTAMP (no time zone) so the wall-clock value from the FHIR
-- message is stored and read back verbatim, regardless of client/session timezone.
-- Existing rows are backfilled using their UTC wall-clock reading (best-effort — the
-- original offset of a non-UTC source literal is not recoverable from the stored instant).

BEGIN;

ALTER TABLE fhir.medication_dispense
    ALTER COLUMN when_prepared TYPE TIMESTAMP USING when_prepared AT TIME ZONE 'UTC';
ALTER TABLE fhir.medication_dispense
    ALTER COLUMN when_handed_over TYPE TIMESTAMP USING when_handed_over AT TIME ZONE 'UTC';

COMMIT;
