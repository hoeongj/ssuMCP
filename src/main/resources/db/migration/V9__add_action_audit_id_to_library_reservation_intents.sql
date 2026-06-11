ALTER TABLE library_reservation_intents ADD COLUMN action_audit_id BIGINT;

CREATE INDEX idx_library_reservation_intents_action_audit
    ON library_reservation_intents(action_audit_id);
