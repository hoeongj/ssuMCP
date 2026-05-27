package com.ssuai.domain.library.connector;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;

public interface LibrarySeatConnector {

    /**
     * @param token Pyxis-Auth-Token for upstream API calls; null for mock connector
     */
    LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token);
}
