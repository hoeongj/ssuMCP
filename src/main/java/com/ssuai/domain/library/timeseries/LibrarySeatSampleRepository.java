package com.ssuai.domain.library.timeseries;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LibrarySeatSampleRepository {

    private static final String INSERT_SQL = """
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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public LibrarySeatSampleRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    LibrarySeatSampleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertBatch(List<LibrarySeatSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        int[] updates = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                LibrarySeatSample sample = samples.get(index);
                setInstant(statement, 1, sample.sampledAt());
                statement.setInt(2, sample.roomId());
                statement.setLong(3, sample.externalSeatId());
                statement.setString(4, sample.seatLabel());
                statement.setString(5, sample.seatType());
                statement.setString(6, sample.statusCode());
                statement.setInt(7, sample.remainingTimeMinutes());
                statement.setInt(8, sample.chargeTimeMinutes());
                setInstant(statement, 9, sample.createdAt());
            }

            @Override
            public int getBatchSize() {
                return samples.size();
            }
        });
        return Arrays.stream(updates).sum();
    }

    static void setInstant(PreparedStatement statement, int parameterIndex, Instant instant) throws SQLException {
        statement.setObject(parameterIndex, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
