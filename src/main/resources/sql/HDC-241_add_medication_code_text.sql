-- HDC-241: Add code_text column to fhir.medication to store Medication.code.text
ALTER TABLE fhir.medication ADD COLUMN IF NOT EXISTS code_text TEXT;
