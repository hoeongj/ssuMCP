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
) PARTITION BY RANGE (sampled_at);

CREATE INDEX idx_library_seat_samples_room_sampled_at
    ON library_seat_samples(room_id, sampled_at);

DO $$
DECLARE
    month_base      DATE := date_trunc('month', now() AT TIME ZONE 'UTC')::DATE;
    month_offset    INT;
    partition_start DATE;
    partition_end   DATE;
BEGIN
    FOR month_offset IN 0..1 LOOP
        partition_start := (month_base + (month_offset * INTERVAL '1 month'))::DATE;
        partition_end := (partition_start + INTERVAL '1 month')::DATE;
        EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF library_seat_samples FOR VALUES FROM (%L) TO (%L)',
                'library_seat_samples_' || to_char(partition_start, 'YYYYMM'),
                to_char(partition_start, 'YYYY-MM-DD') || ' 00:00:00+00',
                to_char(partition_end, 'YYYY-MM-DD') || ' 00:00:00+00');
    END LOOP;
END $$;

CREATE TABLE library_room_occupancy_hourly (
    room_id             INT                         NOT NULL,
    bucket_start        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    sample_count        INT                         NOT NULL,
    avg_available_seats DOUBLE PRECISION            NOT NULL,
    avg_occupied_seats  DOUBLE PRECISION            NOT NULL,
    max_occupied_seats  INT                         NOT NULL,
    CONSTRAINT pk_library_room_occupancy_hourly PRIMARY KEY (room_id, bucket_start)
);
