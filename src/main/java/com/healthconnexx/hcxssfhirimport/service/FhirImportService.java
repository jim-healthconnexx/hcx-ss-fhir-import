package com.healthconnexx.hcxssfhirimport.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthconnexx.hcxssfhirimport.mapping.FhirBundleRelationalMapper;
import com.healthconnexx.hcxssfhirimport.mapping.MappedBundle;
import com.healthconnexx.hcxssfhirimport.model.ImportResult;
import com.healthconnexx.hcxssfhirimport.writer.FhirImportWriter;
import com.healthconnexx.hcxssfhirimport.writer.PanelStatusWriter;
import com.healthconnexx.hcxssfhirimport.writer.RxHistoryResponseWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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

    private static final String POPULATION_ID_SYSTEM =
            "http://fhirdocs.surescripts.net/identifiers/population-id";

    private final FhirS3Service fhirS3Service;
    private final FhirImportWriter fhirImportWriter;
    private final PanelStatusWriter panelStatusWriter;
    private final RxHistoryResponseWriter rxHistoryResponseWriter;
    private final ObjectMapper objectMapper;

    public ImportResult processAll() {
        List<String> keys = fhirS3Service.listFhirFiles();
        int success = 0;
        int error = 0;

        for (String key : keys) {
            try {
                processFile(key);
                fhirS3Service.moveToProcessed(key);
                success++;
            } catch (Exception e) {
                error++;
                log.error("HDC-221: Failed to process FHIR file '{}' — moving to error/", key, e);
                tryMoveToError(key);
            }
        }

        log.info("HDC-221: Import complete — total={} success={} error={}", keys.size(), success, error);
        return new ImportResult(keys.size(), success, error);
    }

    private void processFile(String key) throws Exception {
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

        fhirImportWriter.persist(mappedBundle, assigningAuthority);
        log.info("HDC-221: Persisted bundle fhir_id={} from '{}'", mappedBundle.bundleKey().fhirId(), key);

        // HDC-221: Update panel.status after successful DB write.
        populationId.ifPresent(ref -> panelStatusWriter.updatePanelStatus(ref, "FHIR Loaded"));

        // HDC-238: Insert patient count summary row into healthdata.ss_rx_history_response.
        populationId.ifPresent(rxHistoryResponseWriter::writeCountsForPopulation);
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
