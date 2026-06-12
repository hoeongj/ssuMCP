package com.ssuai.domain.library.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class LibraryRoomOccupancyHourlyRepositoryTests {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private LibraryRoomOccupancyHourlyRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("library-rollup-test;MODE=PostgreSQL")
                .addScript("db/migration/h2/V10__create_library_seat_samples.sql")
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        repository = new LibraryRoomOccupancyHourlyRepository(
                jdbcTemplate,
                "jdbc:h2:mem:library-rollup-test;MODE=PostgreSQL");
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void aggregatesPreviousHourByRoomAndRerunReplacesSameBucket() {
        Instant bucket = Instant.parse("2026-06-12T10:00:00Z");
        insertSample(bucket, 53, List.of("A", "O", "W", "I"));
        insertSample(bucket.plusSeconds(300), 53, List.of("A", "A", "O", "I"));
        insertSample(bucket, 54, List.of("A", "O"));
        insertSample(bucket.plusSeconds(3600), 53, List.of("A", "A", "A"));

        int firstRunRows = repository.rollupHour(bucket);
        int secondRunRows = repository.rollupHour(bucket);

        assertThat(firstRunRows).isEqualTo(2);
        assertThat(secondRunRows).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM library_room_occupancy_hourly",
                Integer.class)).isEqualTo(2);

        Map<String, Object> room53 = jdbcTemplate.queryForMap("""
                SELECT sample_count, avg_available_seats, avg_occupied_seats, max_occupied_seats
                FROM library_room_occupancy_hourly
                WHERE room_id = 53
                """);
        assertThat(((Number) room53.get("SAMPLE_COUNT")).intValue()).isEqualTo(2);
        assertThat(((Number) room53.get("AVG_AVAILABLE_SEATS")).doubleValue()).isCloseTo(1.5, within(0.001));
        assertThat(((Number) room53.get("AVG_OCCUPIED_SEATS")).doubleValue()).isCloseTo(1.5, within(0.001));
        assertThat(((Number) room53.get("MAX_OCCUPIED_SEATS")).intValue()).isEqualTo(2);

        insertSample(bucket.plusSeconds(600), 53, List.of("O", "W", "I"));
        repository.rollupHour(bucket);

        Map<String, Object> updatedRoom53 = jdbcTemplate.queryForMap("""
                SELECT sample_count, avg_available_seats, avg_occupied_seats, max_occupied_seats
                FROM library_room_occupancy_hourly
                WHERE room_id = 53
                """);
        assertThat(((Number) updatedRoom53.get("SAMPLE_COUNT")).intValue()).isEqualTo(3);
        assertThat(((Number) updatedRoom53.get("AVG_AVAILABLE_SEATS")).doubleValue()).isCloseTo(1.0, within(0.001));
        assertThat(((Number) updatedRoom53.get("AVG_OCCUPIED_SEATS")).doubleValue()).isCloseTo(1.667, within(0.001));
        assertThat(((Number) updatedRoom53.get("MAX_OCCUPIED_SEATS")).intValue()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM library_room_occupancy_hourly",
                Integer.class)).isEqualTo(2);
    }

    private void insertSample(Instant sampledAt, int roomId, List<String> statusCodes) {
        for (int index = 0; index < statusCodes.size(); index++) {
            int externalSeatId = roomId * 1000 + (int) sampledAt.getEpochSecond() % 1000 + index + 1;
            jdbcTemplate.update("""
                    INSERT INTO library_seat_samples (
                        sampled_at,
                        room_id,
                        external_seat_id,
                        seat_label,
                        seat_type,
                        status_code,
                        remaining_time_minutes,
                        charge_time_minutes,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?)
                    """,
                    utc(sampledAt),
                    roomId,
                    externalSeatId,
                    Integer.toString(index + 1),
                    "general",
                    statusCodes.get(index),
                    utc(sampledAt));
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
