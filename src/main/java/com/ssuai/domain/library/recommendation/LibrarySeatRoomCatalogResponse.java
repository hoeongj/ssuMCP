package com.ssuai.domain.library.recommendation;

import java.util.List;

public record LibrarySeatRoomCatalogResponse(
        int roomCount,
        boolean includesLayout,
        String message,
        List<LibrarySeatRoomCatalogEntry> rooms
) {

    public LibrarySeatRoomCatalogResponse {
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
    }
}
