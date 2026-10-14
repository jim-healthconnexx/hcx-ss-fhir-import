package com.healthconnexx.hcxssfhirimport.model;

import java.time.OffsetDateTime;

/** HDC-309: Immutable row for healthdata.panel_tracker — one row per panel status write. */
public record PanelTrackerRecord(
        int panelId,
        String status,
        String app,
        OffsetDateTime createdOn,
        String logStream) {}
