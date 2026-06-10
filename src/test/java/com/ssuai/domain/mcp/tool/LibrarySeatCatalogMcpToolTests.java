package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogResponse;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogService;

class LibrarySeatCatalogMcpToolTests {

    private final LibrarySeatCatalogMcpTool tool =
            new LibrarySeatCatalogMcpTool(new LibrarySeatRoomCatalogService());

    @Test
    void returnsStaticCatalogWithoutAuthentication() {
        LibrarySeatRoomCatalogResponse response =
                tool.getLibrarySeatCatalog("B1", null, true, null);

        assertThat(response.roomCount()).isEqualTo(1);
        assertThat(response.rooms().getFirst().roomCode()).isEqualTo("basement-reading-b1");
        assertThat(response.rooms().getFirst().textLayout()).isNotEmpty();
    }

    @Test
    void hidesInternalCaptureNotesByDefault() {
        LibrarySeatRoomCatalogResponse response =
                tool.getLibrarySeatCatalog("B1", null, false, null);

        assertThat(response.rooms().getFirst().captureNotes()).isEmpty();
    }

    @Test
    void includesCaptureNotesOnlyInDebugMode() {
        LibrarySeatRoomCatalogResponse response =
                tool.getLibrarySeatCatalog("B1", null, false, true);

        assertThat(response.rooms().getFirst().captureNotes()).isNotEmpty();
    }
}
