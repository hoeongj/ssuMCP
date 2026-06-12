package com.ssuai.domain.library.timeseries;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.redis.LibrarySchedulerLeadership;

@Component
public class LibraryRoomOccupancyHourlyRollupJob {

    private static final Logger log = LoggerFactory.getLogger(LibraryRoomOccupancyHourlyRollupJob.class);

    private final LibraryRoomOccupancyHourlyRepository repository;
    private final LibrarySeatSampleProperties properties;
    private final Clock clock;
    private final LibrarySchedulerLeadership schedulerLeadership;

    @Autowired
    public LibraryRoomOccupancyHourlyRollupJob(
            LibraryRoomOccupancyHourlyRepository repository,
            LibrarySeatSampleProperties properties,
            LibrarySchedulerLeadership schedulerLeadership,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.schedulerLeadership = schedulerLeadership;
        this.clock = clock;
    }

    LibraryRoomOccupancyHourlyRollupJob(
            LibraryRoomOccupancyHourlyRepository repository,
            LibrarySeatSampleProperties properties,
            Clock clock) {
        this(repository, properties, LibrarySchedulerLeadership.noop(), clock);
    }

    @Scheduled(cron = "0 7 * * * *", zone = "UTC")
    public void rollupScheduled() {
        schedulerLeadership.runIfLeader("seat-hourly-rollup", this::rollupScheduledWithLock);
    }

    private void rollupScheduledWithLock() {
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
