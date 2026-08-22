package com.healthconnexx.hcxssfhirimport.writer;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * HDC-238: After a FHIR bundle import completes for a given population-id, counts unique patients
 * by communication_reason_coding.code and inserts a summary row into
 * healthdata.ss_rx_history_response.
 *
 * <p>HDC-272: Called once per unique reference_number after all files for a batch are processed,
 * ensuring a single summary row per reference_number rather than one row per file.
 *
 * <p>Code mappings (per HDC-238):
 * <ul>
 *   <li>crc.code IS NULL → ok_count</li>
 *   <li>crc.code = 'AQ'  → multiple_response_count</li>
 *   <li>crc.code = 'DJ'  → not_found_count</li>
 *   <li>crc.code = 'DM'  → error_count</li>
 *   <li>empty_count, incomplete_count, unknown_count → 0 (codes not documented)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RxHistoryResponseWriter {

    private static final String POPULATION_ID_SYSTEM =
            "http://fhirdocs.surescripts.net/identifiers/population-id";

    private final DSLContext dslContext;

    @Transactional
    public void writeCountsForPopulation(String populationId) {
        log.debug("HDC-238: Computing patient counts for population-id='{}'", populationId);

        // HDC-238: Aggregate unique patient counts per reason code in a single query.
        var counts = dslContext
                .select(
                        org.jooq.impl.DSL.countDistinct(field(name("p", "patient_id"))).as("total_count"),
                        org.jooq.impl.DSL.countDistinct(
                                org.jooq.impl.DSL.when(field(name("crc", "code")).isNull(),
                                        field(name("p", "patient_id")))).as("ok_count"),
                        org.jooq.impl.DSL.countDistinct(
                                org.jooq.impl.DSL.when(field(name("crc", "code")).eq("AQ"),
                                        field(name("p", "patient_id")))).as("multiple_response_count"),
                        org.jooq.impl.DSL.countDistinct(
                                org.jooq.impl.DSL.when(field(name("crc", "code")).eq("DJ"),
                                        field(name("p", "patient_id")))).as("not_found_count"),
                        org.jooq.impl.DSL.countDistinct(
                                org.jooq.impl.DSL.when(field(name("crc", "code")).eq("DM"),
                                        field(name("p", "patient_id")))).as("error_count")
                )
                .from(table(name("fhir", "communication")).as("c"))
                .leftJoin(table(name("fhir", "communication_reason_coding")).as("crc"))
                    .on(field(name("c", "communication_id")).eq(field(name("crc", "communication_id"))))
                .leftJoin(table(name("fhir", "communication_identifier")).as("ci"))
                    .on(field(name("c", "communication_id")).eq(field(name("ci", "communication_id"))))
                .leftJoin(table(name("fhir", "patient")).as("p"))
                    .on(field(name("c", "patient_id")).eq(field(name("p", "patient_id"))))
                .where(field(name("ci", "system_uri")).eq(POPULATION_ID_SYSTEM))
                .and(field(name("ci", "value")).eq(populationId))
                .fetchOne();

        if (counts == null) {
            log.warn("HDC-238: Count query returned no result for population-id='{}'; skipping insert", populationId);
            return;
        }

        int totalCount     = counts.get("total_count",             Integer.class);
        int okCount        = counts.get("ok_count",                Integer.class);
        int multipleCount  = counts.get("multiple_response_count", Integer.class);
        int notFoundCount  = counts.get("not_found_count",         Integer.class);
        int errorCount     = counts.get("error_count",             Integer.class);

        log.debug("HDC-238: Counts for population-id='{}' — total={} ok={} multiple={} notFound={} error={}",
                populationId, totalCount, okCount, multipleCount, notFoundCount, errorCount);

        dslContext.insertInto(table(name("healthdata", "ss_rx_history_response")))
                .set(field(name("reference_number")),        populationId)
                .set(field(name("total_count")),             totalCount)
                .set(field(name("patient_count")),           totalCount)
                .set(field(name("ok_count")),                okCount)
                .set(field(name("multiple_response_count")), multipleCount)
                .set(field(name("not_found_count")),         notFoundCount)
                .set(field(name("error_count")),             errorCount)
                .set(field(name("empty_count")),             0)
                .set(field(name("incomplete_count")),        0)
                .set(field(name("unknown_count")),           0)
                .set(field(name("inserted_on")),             OffsetDateTime.now())
                .set(field(name("updated_on")),              (Object) null)
                .execute();

        log.info("HDC-238: Inserted ss_rx_history_response for population-id='{}' — total={}", populationId, totalCount);
    }
}
