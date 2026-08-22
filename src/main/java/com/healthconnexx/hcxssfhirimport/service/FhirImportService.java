package com.healthconnexx.hcxssfhirimport.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthconnexx.hcxssfhirimport.mapping.FhirBundleRelationalMapper;
import com.healthconnexx.hcxssfhirimport.mapping.MappedBundle;
import com.healthconnexx.hcxssfhirimport.model.ImportResult;
import com.healthconnexx.hcxssfhirimport.writer.DuplicatePatientChecker;
import com.healthconnexx.hcxssfhirimport.writer.FhirImportWriter;
import com.healthconnexx.hcxssfhirimport.writer.PanelStatusWriter;
import com.healthconnexx.hcxssfhirimport.writer.RxHistoryResponseWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * HDC-221: Orchestrates FHIR file import.
 *
 * Per-file flow:
 *   1. Download JSON from S3
 *   2. Extract population-id (= panel.reference_number) from Communication resource
 *   3. Load product.file_config to get DTL.AssigningAuthority for MRN resolution
 *   4. Map FHIR bundle to relational rows
 *   5. Persist to medication_history schema (with MRN resolved)
 *   6. Update panel.status = 'FHIR Loaded'
 *   7. Move file to processed/; on any error move to error/
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FhirImportService {

    private final FhirS3Service fhirS3Service;
    private final FhirImportWriter fhirImportWriter;
    private final PanelStatusWriter panelStatusWriter;
    private final RxHistoryResponseWriter rxHistoryResponseWriter;
    private final DuplicatePatientChecker duplicatePatientChecker;
    private final ObjectMapper objectMapper;

    public ImportResult processAll() {
        List<String> keys = fhirS3Service.listFhirFiles();
        int success = 0;
        int error = 0;

        // HDC-272: Collect unique reference_numbers across all files so the summary row
        // is written once per reference_number rather than once per file.
        Set<String> processedPopulationIds = new LinkedHashSet<>();

        for (String key : keys) {
            try {
                Optional<String> populationId = processFile(key);
                fhirS3Service.moveToProcessed(key);
                success++;
                populationId.ifPresent(processedPopulationIds::add);
            } catch (Exception e) {
                error++;
                log.error("HDC-221: Failed to process FHIR file '{}' — moving to error/", key, e);
                tryMoveToError(key);
            }
        }

        // HDC-272: Write exactly one ss_rx_history_response row per reference_number.
        for (String populationId : processedPopulationIds) {
            try {
                rxHistoryResponseWriter.writeCountsForPopulation(populationId);
            } catch (Exception e) {
                log.error("HDC-272: Failed to write ss_rx_history_response for reference_number='{}' — skipping", populationId, e);
            }
        }

        log.info("HDC-221: Import complete — total={} success={} error={}", keys.size(), success, error);
        return new ImportResult(keys.size(), success, error);
    }

    // HDC-272: Returns the populationId so processAll() can deduplicate and write
    // ss_rx_history_response once per reference_number after all files are processed.
    private Optional<String> processFile(String key) throws Exception {
        log.info("HDC-221: Processing FHIR file '{}'", key);

        String json = fhirS3Service.download(key);
        JsonNode root = objectMapper.readTree(json);

        // HDC-221: Extract population-id from the Communication resource for panel linkage.
        Optional<String> populationId = FhirBundleRelationalMapper.extractPopulationId(root);
        if (populationId.isEmpty()) {
            log.warn("HDC-221: No population-id found in '{}'; panel status will not be updated", key);
        }

        // HDC-221: Load AssigningAuthority from product.file_config for MRN resolution.
        String assigningAuthority = null;
        if (populationId.isPresent()) {
            String fileConfig = panelStatusWriter.findAssigningAuthorityForPanel(populationId.get());
            assigningAuthority = extractAssigningAuthority(fileConfig);
            log.debug("HDC-221: AssigningAuthority='{}' for population-id='{}'",
                    assigningAuthority, populationId.get());
        }

        FhirBundleRelationalMapper mapper = new FhirBundleRelationalMapper(objectMapper);
        MappedBundle mappedBundle = mapper.mapBundle(root);

        // HDC-243: Skip import if patient already exists with matching demographics.
        if (duplicatePatientChecker.isDuplicate(mappedBundle, populationId.orElse(null), assigningAuthority)) {
            log.info("HDC-243: Duplicate patient detected — skipping bundle from '{}'", key);
            return populationId;
        }

        fhirImportWriter.persist(mappedBundle, assigningAuthority);
        log.info("HDC-221: Persisted bundle fhir_id={} from '{}'", mappedBundle.bundleKey().fhirId(), key);

        // HDC-221: Update panel.status after successful DB write.
        populationId.ifPresent(ref -> panelStatusWriter.updatePanelStatus(ref, "FHIR Loaded"));

        return populationId;
    }

    /**
     * HDC-221: Parses DTL.AssigningAuthority from the product file_config JSON string.
     * Returns null if not present or on parse error.
     */
    private String extractAssigningAuthority(String fileConfig) {
        if (fileConfig == null || fileConfig.isBlank()) return null;
        try {
            JsonNode config = objectMapper.readTree(fileConfig);
            JsonNode value = config.path("DTL").path("AssigningAuthority");
            return value.isTextual() ? value.asText(null) : null;
        } catch (Exception e) {
            log.warn("HDC-221: Could not parse AssigningAuthority from file_config: {}", e.getMessage());
            return null;
        }
    }

    private void tryMoveToError(String key) {
        try {
            fhirS3Service.moveToError(key);
        } catch (Exception e2) {
            log.error("HDC-221: Also failed to move '{}' to error/", key, e2);
        }
    }
}
