package com.ssuai.domain.library.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.dto.LibraryFloor;

class LibrarySeatCatalogServiceTests {

    private final LibrarySeatCatalogService catalogService = new LibrarySeatCatalogService();

    @Test
    void loadsSeatCatalogFromClasspath() {
        assertThat(catalogService.entriesFor(LibraryFloor.F2))
                .extracting(LibrarySeatCatalogEntry::seatId)
                .containsExactly("2-A-001", "2-A-002", "2-A-076", "2-B-001");
    }

    @Test
    void findsSeatIgnoringInputCase() {
        LibrarySeatCatalogEntry seat = catalogService.find(LibraryFloor.F2, "2-a-001")
                .orElseThrow();

        assertThat(seat.roomCode()).isEqualTo("open-reading-2f");
        assertThat(seat.zone()).isEqualTo("2F A Zone");
        assertThat(seat.attributes().window()).isTrue();
        assertThat(seat.attributes().outlet()).isTrue();
    }

    @Test
    void returnsEmptyWhenSeatIsNotCataloged() {
        assertThat(catalogService.find(LibraryFloor.F2, "2-A-999")).isEmpty();
    }
}
