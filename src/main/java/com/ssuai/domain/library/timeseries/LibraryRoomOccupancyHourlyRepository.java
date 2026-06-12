package com.ssuai.domain.library.timeseries;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LibraryRoomOccupancyHourlyRepository {

    private static final String ROLLUP_SELECT = """
            SELECT
                room_id,
                ? AS bucket_start,
                CAST(COUNT(*) AS INT) AS sample_count,
                AVG(available_seats) AS avg_available_seats,
                AVG(occupied_seats) AS avg_occupied_seats,
                CAST(MAX(occupied_seats) AS INT) AS max_occupied_seats
            FROM (
                SELECT
                    room_id,
                    sampled_at,
                    SUM(CASE WHEN status_code = 'A' THEN 1 ELSE 0 END) AS available_seats,
                    SUM(CASE WHEN status_code IN ('O', 'W') THEN 1 ELSE 0 END) AS occupied_seats
                FROM library_seat_samples
                WHERE sampled_at >= ?
                  AND sampled_at < ?
                GROUP BY room_id, sampled_at
            ) per_sample
            GROUP BY room_id
            """;

    private static final String POSTGRES_UPSERT = """
            INSERT INTO library_room_occupancy_hourly (
                room_id,
                bucket_start,
                sample_count,
                avg_available_seats,
                avg_occupied_seats,
                max_occupied_seats
            )
            """ + ROLLUP_SELECT + """
            ON CONFLICT (room_id, bucket_start) DO UPDATE SET
                sample_count = EXCLUDED.sample_count,
                avg_available_seats = EXCLUDED.avg_available_seats,
                avg_occupied_seats = EXCLUDED.avg_occupied_seats,
                max_occupied_seats = EXCLUDED.max_occupied_seats
            """;

    private static final String INSERT_ROLLUP = """
            INSERT INTO library_room_occupancy_hourly (
                room_id,
                bucket_start,
                sample_count,
                avg_available_seats,
                avg_occupied_seats,
                max_occupied_seats
            )
            """ + ROLLUP_SELECT;

    private final JdbcTemplate jdbcTemplate;
    private final boolean postgres;

    @Autowired
    public LibraryRoomOccupancyHourlyRepository(DataSource dataSource, Environment environment) {
        this(new JdbcTemplate(dataSource), environment.getProperty("spring.datasource.url", ""));
    }

    LibraryRoomOccupancyHourlyRepository(JdbcTemplate jdbcTemplate, String datasourceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.postgres = isPostgresUrl(datasourceUrl);
    }

    @Transactional
    public int rollupHour(Instant bucketStart) {
        Instant normalizedStart = bucketStart.truncatedTo(ChronoUnit.HOURS);
        Instant bucketEnd = normalizedStart.plus(1, ChronoUnit.HOURS);
        Object[] params = rollupParams(normalizedStart, bucketEnd);
        if (postgres) {
            return jdbcTemplate.update(POSTGRES_UPSERT, params);
        }
        jdbcTemplate.update(
                "DELETE FROM library_room_occupancy_hourly WHERE bucket_start = ?",
                utc(normalizedStart));
        return jdbcTemplate.update(INSERT_ROLLUP, params);
    }

    private static Object[] rollupParams(Instant bucketStart, Instant bucketEnd) {
        return new Object[] {utc(bucketStart), utc(bucketStart), utc(bucketEnd)};
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static boolean isPostgresUrl(String datasourceUrl) {
        return datasourceUrl != null
                && datasourceUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:");
    }
}
