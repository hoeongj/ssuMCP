package com.ssuai.domain.library.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.ssuai.domain.library.dto.LibraryFloor;

class LibrarySeatCatalogServiceTests {

    private final LibrarySeatCatalogService catalogService = new LibrarySeatCatalogService();

    @Test
    void loadsSeatCatalogFromClasspath() {
        assertThat(catalogService.entriesFor(LibraryFloor.F2)).hasSize(342);
        assertThat(catalogService.entriesFor(LibraryFloor.F5)).hasSize(104);
        assertThat(catalogService.entriesFor(LibraryFloor.F6)).hasSize(307);
    }

    @Test
    void ordersSeatsByFloorRoomAndNaturalSeatId() {
        assertThat(catalogService.entriesFor(LibraryFloor.F2))
                .extracting(entry -> entry.roomCode() + ":" + entry.seatId())
                .startsWith("open-reading-2f:1", "open-reading-2f:2", "open-reading-2f:3")
                .containsSubsequence(
                        "open-reading-2f:231",
                        "open-reading-2f:232",
                        "soongsil-square-on:1",
                        "soongsil-square-on:2")
                .endsWith("soongsil-square-on:108", "soongsil-square-on:109", "soongsil-square-on:110");
        assertThat(catalogService.entriesFor(LibraryFloor.F5))
                .extracting(entry -> entry.roomCode() + ":" + entry.seatId())
                .containsSubsequence("recliner-5f:R1", "recliner-5f:R2", "recliner-5f:R6");
    }

    @Test
    void findsNumericSeatAndKeepsExternalSeatIdAsString() {
        LibrarySeatCatalogEntry seat = catalogService.find(LibraryFloor.F6, "245")
                .orElseThrow();

        assertThat(seat.roomCode()).isEqualTo("maru-reading");
        assertThat(seat.externalSeatId()).isEqualTo("3350");
        assertThat(seat.attributes().quiet()).isTrue();
        assertThat(seat.attributes().outlet()).isFalse();
    }

    @Test
    void findsAlphaSeatIgnoringInputCase() {
        LibrarySeatCatalogEntry seat = catalogService.find(LibraryFloor.F5, "r4")
                .orElseThrow();

        assertThat(seat.roomCode()).isEqualTo("recliner-5f");
        assertThat(seat.externalSeatId()).isEqualTo("3355");
        assertThat(seat.seatType()).isEqualTo("recliner");
    }

    @Test
    void returnsEmptyWhenSeatIsNotCataloged() {
        assertThat(catalogService.find(LibraryFloor.F2, "999")).isEmpty();
    }

    @Test
    void roomScopedLookup_findsCorrectRoom() {
        assertThat(catalogService.findByExternalSeatId("3423", 53))
                .hasValueSatisfying(entry -> {
                    assertThat(entry.roomCode()).isEqualTo("soongsil-square-on");
                    assertThat(entry.seatId()).isEqualTo("1");
                });
        assertThat(catalogService.findByExternalSeatId("3423", 60))
                .hasValueSatisfying(entry -> {
                    assertThat(entry.roomCode()).isEqualTo("multi-lounge-5f");
                    assertThat(entry.seatId()).isEqualTo("66");
                });
    }

    @Test
    void globalFallback_logsWarnOnAmbiguous() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(LibrarySeatCatalogService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(catalogService.findByExternalSeatId("3423"))
                    .hasValueSatisfying(entry -> assertThat(entry.roomCode()).isEqualTo("soongsil-square-on"));

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("Ambiguous externalSeatId 3423")
                                .contains("2 rooms");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void allDuplicates_areCrossRoom() {
        Map<String, List<LibrarySeatCatalogEntry>> entriesByExternalSeatId =
                List.of(LibraryFloor.F2, LibraryFloor.F5, LibraryFloor.F6).stream()
                        .flatMap(floor -> catalogService.entriesFor(floor).stream())
                        .filter(entry -> entry.externalSeatId() != null)
                        .collect(Collectors.groupingBy(LibrarySeatCatalogEntry::externalSeatId));

        assertThat(entriesByExternalSeatId.entrySet())
                .filteredOn(entry -> entry.getValue().size() > 1)
                .hasSize(33)
                .allSatisfy(entry -> assertThat(entry.getValue())
                        .extracting(LibrarySeatCatalogEntry::roomCode)
                        .doesNotHaveDuplicates());
    }
}
