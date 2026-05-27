package com.ssuai.domain.library.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.connector.LibraryLoansConnector;
import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Service
public class LibraryLoansService {

    private static final Logger log = LoggerFactory.getLogger(LibraryLoansService.class);

    private final LibraryLoansConnector connector;
    private final LibrarySessionStore sessionStore;
    private final boolean authRequired;

    public LibraryLoansService(
            LibraryLoansConnector connector,
            LibrarySessionStore sessionStore,
            @Value("${ssuai.connector.library-loans:mock}") String connectorMode
    ) {
        this.connector = connector;
        this.sessionStore = sessionStore;
        this.authRequired = "real".equalsIgnoreCase(connectorMode);
    }

    public LibraryLoansResponse getLoansForSession(String sessionKey) {
        String token = null;
        if (authRequired) {
            token = sessionStore.token(sessionKey).orElseThrow(() -> {
                log.info("library loans: session required, no token for sessionKey={}",
                        LibrarySessionStore.fingerprint(sessionKey));
                return new LibraryAuthRequiredException();
            });
        }
        try {
            return connector.fetchLoans(token);
        } catch (ConnectorException exception) {
            log.warn("library loans fetch failure: code={}", exception.getErrorCode().name());
            throw exception;
        }
    }
}
