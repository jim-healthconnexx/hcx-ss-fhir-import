package com.healthconnexx.hcxssfhirimport.writer;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * HDC-221: Updates panel.status in the healthdata schema after a successful FHIR bundle import.
 * Identified by panel.reference_number which equals the FHIR population-id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PanelStatusWriter {

    private final DSLContext dslContext;

    @Transactional
    public void updatePanelStatus(String referenceNumber, String status) {
        try {
            int updated = dslContext.update(table(name("healthdata", "panel")))
                    .set(field(name("status")), status)
                    .where(field(name("reference_number")).eq(referenceNumber))
                    .execute();
            if (updated == 0) {
                log.warn("HDC-221: No panel found with reference_number='{}'; status not updated", referenceNumber);
            } else {
                log.info("HDC-221: Updated panel reference_number='{}' status to '{}'", referenceNumber, status);
            }
        } catch (RuntimeException e) {
            log.error("HDC-221: Failed to update panel reference_number='{}' status to '{}'",
                    referenceNumber, status, e);
            throw e;
        }
    }

    /** HDC-221: Loads product.file_config JSON for a given panel reference_number. */
    public String findAssigningAuthorityForPanel(String referenceNumber) {
        try {
            return dslContext
                    .select(field(name("p", "file_config"), String.class))
                    .from(table(name("healthdata", "panel")).as("pan"))
                    .join(table(name("healthdata", "product")).as("p"))
                    .on(field(name("pan", "product_id")).eq(field(name("p", "product_id"))))
                    .where(field(name("pan", "reference_number")).eq(referenceNumber))
                    .fetchOne(r -> r.get(field(name("p", "file_config"), String.class)));
        } catch (Exception e) {
            log.warn("HDC-221: Could not load file_config for reference_number='{}': {}", referenceNumber, e.getMessage());
            return null;
        }
    }
}
