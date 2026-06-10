package com.ssuai.domain.library.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LibrarySeatRoomCatalogServiceTests {

    private final LibrarySeatRoomCatalogService catalogService = new LibrarySeatRoomCatalogService();

    @Test
    void loadsEveryCapturedRoom() {
        LibrarySeatRoomCatalogResponse response = catalogService.catalog(null, null, false);

        assertThat(response.roomCount()).isEqualTo(7);
        assertThat(response.rooms())
                .extracting(LibrarySeatRoomCatalogEntry::roomCode)
                .contains(
                        "soongsil-square-on",
                        "open-reading-2f",
                        "pc-multi-zone-5f",
                        "recliner-5f",
                        "maru-reading",
                        "graduate-reading",
                        "basement-reading-b1");
        assertThat(response.rooms()).allSatisfy(room -> assertThat(room.textLayout()).isEmpty());
    }

    @Test
    void filtersByFloorAndIncludesTextLayoutWhenRequested() {
        LibrarySeatRoomCatalogResponse response = catalogService.catalog("2", null, true);

        assertThat(response.roomCount()).isEqualTo(2);
        assertThat(response.includesLayout()).isTrue();
        assertThat(response.rooms())
                .extracting(LibrarySeatRoomCatalogEntry::floorCode)
                .containsOnly("2F");
        assertThat(response.rooms())
                .allSatisfy(room -> assertThat(room.textLayout()).isNotEmpty());
    }

    @Test
    void marksGraduateRoomRestriction() {
        LibrarySeatRoomCatalogResponse response = catalogService.catalog(null, "graduate-reading", false);

        assertThat(response.roomCount()).isEqualTo(1);
        LibrarySeatRoomCatalogEntry room = response.rooms().getFirst();
        assertThat(room.graduateOnly()).isTrue();
        assertThat(room.audience()).isEqualTo("graduate_only");
        // captureNotes are internal data-collection notes — hidden unless debug=true
        assertThat(room.captureNotes()).isEmpty();

        LibrarySeatRoomCatalogResponse debugResponse =
                catalogService.catalog(null, "graduate-reading", false, true);
        assertThat(debugResponse.rooms().getFirst().captureNotes()).anySatisfy(note ->
                assertThat(note).contains("해당유형은 사용이 불가능한 신분입니다"));
    }

    @Test
    void b1ExistsOnlyInStaticCatalogForNow() {
        LibrarySeatRoomCatalogResponse response = catalogService.catalog("B1", null, true);

        assertThat(response.roomCount()).isEqualTo(1);
        assertThat(response.rooms().getFirst().roomCode()).isEqualTo("basement-reading-b1");
        assertThat(response.rooms().getFirst().floor()).isEqualTo(-1);
    }
}
