CREATE TABLE library_seat_samples (
    sampled_at             TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    room_id                INT                         NOT NULL,
    external_seat_id       BIGINT                      NOT NULL,
    seat_label             VARCHAR(32)                 NOT NULL,
    seat_type              VARCHAR(64),
    status_code            CHAR(1)                     NOT NULL,
    remaining_time_minutes INT                         NOT NULL DEFAULT 0,
    charge_time_minutes    INT                         NOT NULL DEFAULT 0,
    created_at             TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_library_seat_samples PRIMARY KEY (sampled_at, room_id, external_seat_id)
);

CREATE INDEX idx_library_seat_samples_room_sampled_at
    ON library_seat_samples(room_id, sampled_at);

CREATE TABLE library_room_occupancy_hourly (
    room_id             INT                         NOT NULL,
    bucket_start        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    sample_count        INT                         NOT NULL,
    avg_available_seats DOUBLE PRECISION            NOT NULL,
    avg_occupied_seats  DOUBLE PRECISION            NOT NULL,
    max_occupied_seats  INT                         NOT NULL,
    CONSTRAINT pk_library_room_occupancy_hourly PRIMARY KEY (room_id, bucket_start)
);
