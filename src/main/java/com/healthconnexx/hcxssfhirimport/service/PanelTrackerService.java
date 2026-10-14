package com.healthconnexx.hcxssfhirimport.service;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.healthconnexx.hcxssfhirimport.model.PanelTrackerRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * HDC-309: Writes an audit row to healthdata.panel_tracker every time this app marks a
 * panel's status (success or error), so progress can be monitored externally.
 * retry_count is intentionally left NULL — not applicable to this app.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PanelTrackerService {

    private final DSLContext dslContext;
    private final LogStreamService logStreamService;

    @Value("${spring.application.name}")
    private String appName;

    public void insertTrackerRecord(int panelId, String status) {
        PanelTrackerRecord trackerRecord = new PanelTrackerRecord(
                panelId, status, appName, OffsetDateTime.now(), logStreamService.getLogStream());
        insertTrackerRecord(trackerRecord);
    }

    private void insertTrackerRecord(PanelTrackerRecord trackerRecord) {
        try {
            dslContext.insertInto(table(name("healthdata", "panel_tracker")))
                    .set(field(name("panel_id")), trackerRecord.panelId())
                    .set(field(name("status")), trackerRecord.status())
                    .set(field(name("app")), trackerRecord.app())
                    .set(field(name("created_on")), trackerRecord.createdOn())
                    .set(field(name("log_stream")), trackerRecord.logStream())
                    .execute();
            log.debug("HDC-309: Wrote panel_tracker row panel_id={} status='{}' log_stream='{}'",
                    trackerRecord.panelId(), trackerRecord.status(), trackerRecord.logStream());
        } catch (RuntimeException e) {
            log.error("HDC-309: Failed to write panel_tracker row panel_id={} status='{}'",
                    trackerRecord.panelId(), trackerRecord.status(), e);
            throw e;
        }
    }
}
