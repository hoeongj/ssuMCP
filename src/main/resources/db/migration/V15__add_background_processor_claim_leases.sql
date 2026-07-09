ALTER TABLE library_reservation_outbox
    ADD COLUMN claimed_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE library_reservation_outbox
    ADD COLUMN claimed_by VARCHAR(128);

CREATE INDEX idx_library_reservation_outbox_claim
    ON library_reservation_outbox(published_at, claimed_at, id);

ALTER TABLE lms_export_jobs
    ADD COLUMN claimed_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE lms_export_jobs
    ADD COLUMN claimed_by VARCHAR(128);

CREATE INDEX idx_lms_export_jobs_claim
    ON lms_export_jobs(status, claimed_at, created_at);
