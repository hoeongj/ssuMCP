package com.ssuai.domain.library.timeseries;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LibraryRoomOccupancyHourlyRollupJob {

    private static final Logger log = LoggerFactory.getLogger(LibraryRoomOccupancyHourlyRollupJob.class);

    private final LibraryRoomOccupancyHourlyRepository repository;
    private final LibrarySeatSampleProperties properties;
    private final Clock clock;

    public LibraryRoomOccupancyHourlyRollupJob(
            LibraryRoomOccupancyHourlyRepository repository,
            LibrarySeatSampleProperties properties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 7 * * * *", zone = "UTC")
    public void rollupScheduled() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int rows = rollupPreviousCompletedHour();
            log.debug("library room occupancy hourly rollup completed: rows={}", rows);
        } catch (RuntimeException exception) {
            log.warn("library room occupancy hourly rollup failed", exception);
        }
    }

    public int rollupPreviousCompletedHour() {
        Instant bucketStart = clock.instant()
                .truncatedTo(ChronoUnit.HOURS)
                .minus(1, ChronoUnit.HOURS);
        return repository.rollupHour(bucketStart);
    }
}
