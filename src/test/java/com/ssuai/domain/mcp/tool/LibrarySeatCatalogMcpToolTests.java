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
                tool.getLibrarySeatCatalog("B1", null, true);

        assertThat(response.roomCount()).isEqualTo(1);
        assertThat(response.rooms().getFirst().roomCode()).isEqualTo("basement-reading-b1");
        assertThat(response.rooms().getFirst().textLayout()).isNotEmpty();
    }

    @Test
    void neverExposesInternalCaptureNotesViaPublicTool() {
        // The public tool no longer accepts a debug param, so captureNotes can never
        // be requested by an MCP caller (security follow-up #14).
        assertThat(tool.getLibrarySeatCatalog("B1", null, false)
                .rooms().getFirst().captureNotes()).isEmpty();
    }

    @Test
    void captureNotesStillReachableViaServiceForInternalUse() {
        LibrarySeatRoomCatalogResponse response =
                new LibrarySeatRoomCatalogService().catalog("B1", null, false, true);

        assertThat(response.rooms().getFirst().captureNotes()).isNotEmpty();
    }
}
