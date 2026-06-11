CREATE TABLE library_reservation_intents (
    id                  BIGSERIAL                     PRIMARY KEY,
    student_id          VARCHAR(64)                   NOT NULL,
    session_key         VARCHAR(128)                  NOT NULL,
    preferred_floor     VARCHAR(8),
    preferred_room_ids  TEXT,
    seat_attributes     TEXT,
    target_seat_id      BIGINT,
    status              VARCHAR(32)                   NOT NULL,
    attempt_count       INT                           NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP(6) WITH TIME ZONE   NOT NULL,
    locked_until        TIMESTAMP(6) WITH TIME ZONE,
    expires_at          TIMESTAMP(6) WITH TIME ZONE   NOT NULL,
    completed_at        TIMESTAMP(6) WITH TIME ZONE,
    outcome_code        VARCHAR(32),
    outcome_message     TEXT,
    created_at          TIMESTAMP(6) WITH TIME ZONE   NOT NULL,
    updated_at          TIMESTAMP(6) WITH TIME ZONE   NOT NULL
);

CREATE INDEX idx_library_reservation_intents_status_next
    ON library_reservation_intents(status, next_attempt_at);
CREATE INDEX idx_library_reservation_intents_student_status
    ON library_reservation_intents(student_id, status);

CREATE TABLE library_reservation_outbox (
    id            BIGSERIAL                     PRIMARY KEY,
    event_type    VARCHAR(64)                   NOT NULL,
    intent_id     BIGINT                        NOT NULL,
    payload       TEXT                          NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE   NOT NULL,
    published_at  TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX idx_library_reservation_outbox_published_id
    ON library_reservation_outbox(published_at, id);
