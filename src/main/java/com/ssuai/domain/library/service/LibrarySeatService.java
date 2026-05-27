package com.ssuai.domain.library.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Service
public class LibrarySeatService {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatService.class);

    private final LibrarySeatCache cache;
    private final LibrarySessionStore sessionStore;
    private final boolean authRequired;

    public LibrarySeatService(
            LibrarySeatCache cache,
            LibrarySessionStore sessionStore,
            @Value("${ssuai.connector.library-seat:mock}") String connectorMode
    ) {
        this.cache = cache;
        this.sessionStore = sessionStore;
        this.authRequired = "real".equalsIgnoreCase(connectorMode);
    }

    public LibrarySeatStatusResponse getSeatStatus(LibraryFloor floor) {
        return fetchWithToken(floor, null);
    }

    public LibrarySeatStatusResponse getSeatStatusForSession(LibraryFloor floor, String sessionKey) {
        String token = null;
        if (authRequired) {
            token = sessionStore.token(sessionKey).orElseThrow(() -> {
                log.info("library seat: session required, no token for sessionKey={}",
                        LibrarySessionStore.fingerprint(sessionKey));
                return new LibraryAuthRequiredException();
            });
        }
        return fetchWithToken(floor, token);
    }

    private LibrarySeatStatusResponse fetchWithToken(LibraryFloor floor, String token) {
        try {
            return cache.get(floor, token);
        } catch (ConnectorException exception) {
            log.warn("library seat fetch failure: floor={} code={}",
                    floor.displayLabel(), exception.getErrorCode().name());
            throw exception;
        }
    }

    boolean isAuthRequired() {
        return authRequired;
    }
}
