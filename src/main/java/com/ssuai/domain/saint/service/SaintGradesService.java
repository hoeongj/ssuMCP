package com.ssuai.domain.saint.service;

import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintGradesConnector;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

/**
 * Façade for the realtime u-SAINT grades fetch (Task 16 PR 16c).
 * Mirrors {@link SaintScheduleService}: look the caller's post-SSO portal
 * cookies up in {@link SaintSessionStore}, hand them to the active
 * {@link SaintGradesConnector}, and forward {@link SaintSessionExpiredException}
 * to the global handler (→ HTTP 401 {@code SAINT_SESSION_EXPIRED}).
 *
 * <p>No caching yet — every controller call re-runs the multi-term prev
 * iterate against ecc. The cookie store TTL (30 minutes) bounds how
 * often this can fire, and Mock is the default profile.
 */
@Service
public class SaintGradesService {

    private final SaintGradesConnector connector;
    private final SaintSessionStore sessionStore;

    public SaintGradesService(SaintGradesConnector connector, SaintSessionStore sessionStore) {
        this.connector = connector;
        this.sessionStore = sessionStore;
    }

    public GradesResponse fetchGrades(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        PortalCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(SaintSessionExpiredException::new);
        return connector.fetchGrades(studentId, cookies);
    }
}
