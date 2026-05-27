package com.ssuai.domain.library.connector;

import com.ssuai.domain.library.dto.LibraryLoansResponse;

public interface LibraryLoansConnector {

    /**
     * @param token Pyxis-Auth-Token for the authenticated user; null for mock connector
     */
    LibraryLoansResponse fetchLoans(String token);
}
