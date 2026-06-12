package com.ssuai.domain.library.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LibrarySeatSamplePartitionMaintenanceTests {

    @Test
    void h2DatasourceIsNoOp() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LibrarySeatSampleProperties properties = new LibrarySeatSampleProperties();
        LibrarySeatSamplePartitionMaintenance maintenance = new LibrarySeatSamplePartitionMaintenance(
                jdbcTemplate,
                properties,
                "jdbc:h2:mem:ssuai;MODE=PostgreSQL",
                fixedClock());

        maintenance.maintain();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void sqlCreatesCurrentAndNextPartitionsAndDropsExpiredMonthlyPartitions() {
        LibrarySeatSamplePartitionMaintenance maintenance = new LibrarySeatSamplePartitionMaintenance(
                mock(JdbcTemplate.class),
                new LibrarySeatSampleProperties(),
                "jdbc:postgresql://db/ssuai",
                fixedClock());

        List<String> sql = maintenance.maintenanceSql(
                Instant.parse("2026-06-12T12:00:00Z"),
                90,
                List.of(
                        "library_seat_samples_202601",
                        "library_seat_samples_202602",
                        "library_seat_samples_202603",
                        "not_a_partition"));

        assertThat(sql).containsExactly(
                "CREATE TABLE IF NOT EXISTS library_seat_samples_202606 PARTITION OF library_seat_samples FOR VALUES FROM ('2026-06-01T00:00:00Z') TO ('2026-07-01T00:00:00Z')",
                "CREATE TABLE IF NOT EXISTS library_seat_samples_202607 PARTITION OF library_seat_samples FOR VALUES FROM ('2026-07-01T00:00:00Z') TO ('2026-08-01T00:00:00Z')",
                "DROP TABLE IF EXISTS library_seat_samples_202601",
                "DROP TABLE IF EXISTS library_seat_samples_202602");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);
    }
}
