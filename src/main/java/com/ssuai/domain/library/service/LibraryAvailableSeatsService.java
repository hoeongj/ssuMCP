package com.ssuai.domain.library.service;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsResponse;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsRoomSummary;
import com.ssuai.domain.library.dto.LibraryRoomAvailableSeatsResponse;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Service
public class LibraryAvailableSeatsService {

    private static final Logger log = LoggerFactory.getLogger(LibraryAvailableSeatsService.class);

    static final List<Integer> ALL_ROOM_IDS = List.of(15, 53, 54, 57, 58, 59, 60);

    private static final Map<Integer, String> ROOM_NAMES;
    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(15, "1열람실(B1F)");
        m.put(53, "숭실스퀘어ON(2F)");
        m.put(54, "오픈열람실(2F)");
        m.put(57, "마루열람실(6F)");
        m.put(58, "대학원열람실(6F)");
        m.put(59, "리클라이너(5F)");
        m.put(60, "숭실멀티라운지(5F)");
        ROOM_NAMES = Collections.unmodifiableMap(m);
    }

    private final LibraryRoomSeatCache roomSeatCache;
    private final LibrarySessionStore sessionStore;
    private final boolean authRequired;

    public LibraryAvailableSeatsService(
            LibraryRoomSeatCache roomSeatCache,
            LibrarySessionStore sessionStore,
            @Value("${ssuai.connector.library-seat:mock}") String connectorMode
    ) {
        this.roomSeatCache = roomSeatCache;
        this.sessionStore = sessionStore;
        this.authRequired = "real".equalsIgnoreCase(connectorMode);
    }

    public LibraryAllAvailableSeatsResponse getAllAvailableSeats(String sessionKey) {
        String token = resolveToken(sessionKey);

        // Fan out the 7 reading-room fetches concurrently: on a cold/expired cache
        // each is an independent upstream round-trip, so serial fetches paid ~7x
        // the latency. Virtual threads (Java 21) fit blocking I/O fan-out without a
        // shared pool to size or starve; the try-with-resources joins all tasks.
        List<LibraryAllAvailableSeatsRoomSummary> rooms;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<LibraryAllAvailableSeatsRoomSummary>> futures = ALL_ROOM_IDS.stream()
                    .map(roomId -> CompletableFuture.supplyAsync(() -> {
                        log.debug("fetching per-seat data: roomId={}", roomId);
                        return toRoomSummary(roomId, roomSeatCache.get(roomId, token));
                    }, executor))
                    .toList();
            try {
                rooms = futures.stream().map(CompletableFuture::join).toList();
            } catch (CompletionException e) {
                // Preserve the pre-fan-out contract: any room failure fails the whole
                // request, with the original exception (not the CompletionException wrapper).
                if (e.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw e;
            }
        }

        int totalAvailable = rooms.stream()
                .mapToInt(LibraryAllAvailableSeatsRoomSummary::availableSeats).sum();
        int totalAway = rooms.stream()
                .mapToInt(LibraryAllAvailableSeatsRoomSummary::awaySeats).sum();
        return new LibraryAllAvailableSeatsResponse(totalAvailable, totalAway, Instant.now(), rooms);
    }

    public LibraryRoomAvailableSeatsResponse getRoomAvailableSeats(int roomId, String sessionKey) {
        if (!ROOM_NAMES.containsKey(roomId)) {
            String allowed = ALL_ROOM_IDS.stream().map(String::valueOf).collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "지원하지 않는 열람실 ID: " + roomId + ". 가능한 값: " + allowed);
        }
        String token = resolveToken(sessionKey);
        log.debug("fetching per-seat data: roomId={}", roomId);
        List<PyxisSeatInfo> seats = roomSeatCache.get(roomId, token);
        return toRoomDetailResponse(roomId, seats);
    }

    private String resolveToken(String sessionKey) {
        if (!authRequired) {
            return null;
        }
        return sessionStore.token(sessionKey).orElseThrow(() -> {
            log.info("library available seats: no token for sessionKey={}",
                    LibrarySessionStore.fingerprint(sessionKey));
            return new LibraryAuthRequiredException();
        });
    }

    private LibraryAllAvailableSeatsRoomSummary toRoomSummary(int roomId, List<PyxisSeatInfo> seats) {
        List<Integer> availableIds = seats.stream()
                .filter(s -> "available".equals(s.status()))
                .map(PyxisSeatInfo::externalSeatId)
                .toList();
        List<String> availableLabels = seats.stream()
                .filter(s -> "available".equals(s.status()))
                .map(PyxisSeatInfo::label)
                .toList();
        int awayCount = (int) seats.stream().filter(s -> "away".equals(s.status())).count();

        return new LibraryAllAvailableSeatsRoomSummary(
                roomId,
                ROOM_NAMES.get(roomId),
                seats.size(),
                availableIds.size(),
                awayCount,
                availableIds,
                availableLabels
        );
    }

    private LibraryRoomAvailableSeatsResponse toRoomDetailResponse(int roomId, List<PyxisSeatInfo> seats) {
        int available = (int) seats.stream().filter(s -> "available".equals(s.status())).count();
        int occupied  = (int) seats.stream().filter(s -> "occupied".equals(s.status())).count();
        int away      = (int) seats.stream().filter(s -> "away".equals(s.status())).count();
        int inactive  = (int) seats.stream().filter(s -> "inactive".equals(s.status())).count();

        return new LibraryRoomAvailableSeatsResponse(
                roomId,
                ROOM_NAMES.get(roomId),
                seats.size(),
                available,
                occupied,
                away,
                inactive,
                Instant.now(),
                seats
        );
    }
}
