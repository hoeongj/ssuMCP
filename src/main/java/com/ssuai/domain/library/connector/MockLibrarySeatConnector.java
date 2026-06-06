package com.ssuai.domain.library.connector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatItem;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;
import com.ssuai.domain.library.dto.PyxisSeatInfo;

/**
 * Deterministic fixture for `ssuai.connector.library-seat=mock` (default).
 * Numbers are chosen so each floor exposes a distinct, realistic-looking
 * shape; they do not reflect actual library occupancy. The real Jsoup-based
 * connector replaces this bean when the upstream URL/markup is settled.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.library-seat", havingValue = "mock", matchIfMissing = true)
public class MockLibrarySeatConnector implements LibrarySeatConnector {

    @Override
    public LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token) {
        return switch (floor) {
            case F2 -> snapshot(floor, 344, 230, 112, 2, List.of(
                    zone("숭실스퀘어ON(2F)", "2-A", "A", 75, 35, 2),
                    zone("오픈열람실(2F)", "2-B", "B", 155, 77, 0)
            ));
            case F5 -> snapshot(floor, 104, 70, 32, 2, List.of(
                    zone("숭실멀티라운지(5F)", "5-A", "A", 65, 31, 2),
                    zone("리클라이너(5F)", "5-B", "B", 5, 1, 0)
            ));
            case F6 -> snapshot(floor, 308, 200, 100, 8, List.of(
                    zone("마루열람실(6F)", "6-A", "A", 160, 78, 8),
                    zone("대학원열람실(6F)", "6-B", "B", 40, 22, 0)
            ));
        };
    }

    private LibrarySeatZone zone(
            String label,
            String idPrefix,
            String seatLabelPrefix,
            int available,
            int occupied,
            int outOfService
    ) {
        int total = available + occupied + outOfService;
        List<LibrarySeatItem> seats = new ArrayList<>(total);
        for (int number = 1; number <= total; number++) {
            String status = number <= available
                    ? "available"
                    : number <= available + occupied ? "occupied" : "outOfService";
            seats.add(new LibrarySeatItem(
                    "%s-%03d".formatted(idPrefix, number),
                    seatLabelPrefix + "-" + number,
                    status
            ));
        }
        List<String> seatIds = seats.stream()
                .filter(seat -> "available".equals(seat.status()))
                .map(LibrarySeatItem::id)
                .toList();
        return new LibrarySeatZone(label, total, available, seatIds, seats);
    }

    @Override
    public List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token) {
        List<PyxisSeatInfo> seats = new ArrayList<>(5);
        int baseId = roomId * 100;
        for (int i = 1; i <= 5; i++) {
            seats.add(new PyxisSeatInfo(baseId + i, String.valueOf(i), "일반용", "available", 0, 0));
        }
        return List.copyOf(seats);
    }

    private LibrarySeatStatusResponse snapshot(
            LibraryFloor floor,
            int total,
            int available,
            int reserved,
            int outOfService,
            List<LibrarySeatZone> zones
    ) {
        return new LibrarySeatStatusResponse(
                floor.code(),
                floor.displayLabel(),
                total,
                available,
                reserved,
                outOfService,
                Instant.now(),
                zones
        );
    }
}
