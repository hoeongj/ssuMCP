package com.ssuai.domain.library.timeseries;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogEntry;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogService;
import com.ssuai.domain.library.redis.LibrarySchedulerLeadership;
import com.ssuai.domain.library.service.LibraryRoomSeatCache;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Service
public class LibrarySeatSampleSampler {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatSampleSampler.class);

    private final LibrarySeatRoomCatalogService roomCatalogService;
    private final LibraryRoomSeatCache roomSeatCache;
    private final LibrarySeatSampleRepository sampleRepository;
    private final LibrarySeatSampleProperties properties;
    private final LibrarySamplerSessionManager sessionManager;
    private final Clock clock;
    private final LibrarySchedulerLeadership schedulerLeadership;

    @Autowired
    public LibrarySeatSampleSampler(
            LibrarySeatRoomCatalogService roomCatalogService,
            LibraryRoomSeatCache roomSeatCache,
            LibrarySeatSampleRepository sampleRepository,
            LibrarySeatSampleProperties properties,
            LibrarySamplerSessionManager sessionManager,
            LibrarySchedulerLeadership schedulerLeadership,
            Clock clock) {
        this.roomCatalogService = roomCatalogService;
        this.roomSeatCache = roomSeatCache;
        this.sampleRepository = sampleRepository;
        this.properties = properties;
        this.sessionManager = sessionManager;
        this.schedulerLeadership = schedulerLeadership;
        this.clock = clock;
    }

    LibrarySeatSampleSampler(
            LibrarySeatRoomCatalogService roomCatalogService,
            LibraryRoomSeatCache roomSeatCache,
            LibrarySeatSampleRepository sampleRepository,
            LibrarySeatSampleProperties properties,
            LibrarySamplerSessionManager sessionManager,
            Clock clock) {
        this(
                roomCatalogService,
                roomSeatCache,
                sampleRepository,
                properties,
                sessionManager,
                LibrarySchedulerLeadership.noop(),
                clock);
    }

    @Scheduled(fixedDelayString = "#{@librarySeatSampleProperties.cadence.toMillis()}")
    public void sampleScheduled() {
        schedulerLeadership.runIfLeader("seat-sampler", this::sampleScheduledWithLock);
    }

    private void sampleScheduledWithLock() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int inserted = sampleOnce();
            log.debug("library seat samples inserted: rows={}", inserted);
        } catch (RuntimeException exception) {
            log.warn("library seat sample run failed", exception);
        }
    }

    public int sampleOnce() {
        return sampleAt(clock.instant());
    }

    int sampleAt(Instant sampledAt) {
        if (!sessionManager.hasCredentials()) {
            try {
                return sampleAt(sampledAt, null);
            } catch (LibraryAuthRequiredException exception) {
                log.warn("library seat sampler skipped: service credentials are not configured");
                return 0;
            }
        }

        boolean loginAttempted = false;
        String token = sessionManager.currentToken().orElse(null);
        if (token == null) {
            token = sessionManager.loginForRun().orElse(null);
            loginAttempted = true;
        }
        if (token == null) {
            log.warn("library seat sampler skipped: service login unavailable");
            return 0;
        }

        try {
            return sampleAt(sampledAt, token);
        } catch (LibraryAuthRequiredException exception) {
            sessionManager.invalidateToken();
            if (loginAttempted) {
                log.warn("library seat sampler skipped: token rejected after login attempt");
                return 0;
            }
            String refreshed = sessionManager.loginForRun().orElse(null);
            if (refreshed == null) {
                log.warn("library seat sampler skipped: service re-login unavailable");
                return 0;
            }
            try {
                return sampleAt(sampledAt, refreshed);
            } catch (LibraryAuthRequiredException retryException) {
                sessionManager.invalidateToken();
                log.warn("library seat sampler skipped: token rejected after service re-login");
                return 0;
            }
        }
    }

    private int sampleAt(Instant sampledAt, String token) {
        Instant createdAt = clock.instant();
        List<LibrarySeatSample> samples = new ArrayList<>();
        for (int roomId : roomIdsToSample()) {
            List<PyxisSeatInfo> seats = roomSeatCache.get(roomId, token);
            for (PyxisSeatInfo seat : seats) {
                samples.add(new LibrarySeatSample(
                        sampledAt,
                        roomId,
                        seat.externalSeatId(),
                        seat.label(),
                        seat.seatType(),
                        LibrarySeatSampleStatus.codeFor(seat.status()),
                        seat.remainingTime(),
                        seat.chargeTime(),
                        createdAt));
            }
        }
        return sampleRepository.insertBatch(samples);
    }

    List<Integer> roomIdsToSample() {
        return roomCatalogService.rooms().stream()
                .filter(LibrarySeatRoomCatalogEntry::reservable)
                .filter(room -> room.floor() != null && room.floor() > 0)
                .map(LibrarySeatRoomCatalogEntry::roomId)
                .filter(roomId -> roomId != null && roomId > 0)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
