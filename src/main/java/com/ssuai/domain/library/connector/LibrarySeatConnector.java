package com.ssuai.domain.library.connector;

import java.util.List;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.PyxisSeatInfo;

public interface LibrarySeatConnector {

    /**
     * @param token Pyxis-Auth-Token for upstream API calls; null for mock connector
     */
    LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token);

    /**
     * Fetches per-seat detail for a single reading room.
     * Calls GET /pyxis-api/1/api/rooms/{roomId}/seats.
     *
     * @param roomId Pyxis room ID (15, 53, 54, 57, 58, 59, or 60)
     * @param token  Pyxis-Auth-Token; null for mock connector
     */
    List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token);
}
