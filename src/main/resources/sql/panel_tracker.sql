-- HDC-309: Audit table recording each panel status transition for this app, including
-- which app wrote it and the CloudWatch log_stream the write happened in.
CREATE TABLE healthdata.panel_tracker (
    panel_tracker_id int GENERATED ALWAYS AS IDENTITY,
    panel_id int,
    app varchar(255) NULL,
    status varchar(255) NULL,
    retry_count int NULL,
    created_on timestamptz NULL,
    log_stream varchar(255) NULL,
    CONSTRAINT xpk_panel_tracker PRIMARY KEY (panel_tracker_id));
