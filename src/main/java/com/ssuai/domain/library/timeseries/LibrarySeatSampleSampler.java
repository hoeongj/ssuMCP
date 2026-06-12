package com.ssuai.domain.library.timeseries;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogEntry;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogService;
import com.ssuai.domain.library.service.LibraryRoomSeatCache;

@Service
public class LibrarySeatSampleSampler {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatSampleSampler.class);

    private final LibrarySeatRoomCatalogService roomCatalogService;
    private final LibraryRoomSeatCache roomSeatCache;
    private final LibrarySeatSampleRepository sampleRepository;
    private final LibrarySeatSampleProperties properties;
    private final Clock clock;

    public LibrarySeatSampleSampler(
            LibrarySeatRoomCatalogService roomCatalogService,
            LibraryRoomSeatCache roomSeatCache,
            LibrarySeatSampleRepository sampleRepository,
            LibrarySeatSampleProperties properties,
            Clock clock) {
        this.roomCatalogService = roomCatalogService;
        this.roomSeatCache = roomSeatCache;
        this.sampleRepository = sampleRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "#{@librarySeatSampleProperties.cadence.toMillis()}")
    public void sampleScheduled() {
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
        Instant createdAt = clock.instant();
        List<LibrarySeatSample> samples = new ArrayList<>();
        for (int roomId : roomIdsToSample()) {
            List<PyxisSeatInfo> seats = roomSeatCache.get(roomId, null);
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
