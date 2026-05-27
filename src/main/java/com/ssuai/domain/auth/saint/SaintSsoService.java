package com.ssuai.domain.auth.saint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ssuai.domain.saint.connector.RusaintAuthenticatedSession;
import com.ssuai.domain.saint.connector.RusaintClient;
import com.ssuai.domain.saint.connector.RusaintClientException;
import com.ssuai.global.exception.SaintAuthFailedException;

/**
 * Confirms a SSU student's identity through rusaint using the one-shot SmartID
 * token pair received by the callback controller.
 *
 * <p>The old Java implementation consumed {@code sToken} / {@code sIdno} to
 * build a raw portal cookie header and then reimplemented SAP WebDynpro in
 * Java. The rusaint path consumes the same one-shot token once, stores
 * rusaint's serialized session JSON in {@link SaintSessionStore}, and lets
 * rusaint own the SAP protocol details.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>{@code sToken} / {@code sIdno} are method-scoped locals and never logged.
 *   <li>Serialized rusaint session JSON is encrypted in {@link SaintSessionStore}.
 *   <li>Cookie, token, and session JSON values are never echoed into responses or logs.
 * </ul>
 */
@Service
public class SaintSsoService {

    private static final Logger log = LoggerFactory.getLogger(SaintSsoService.class);

    private final SaintSessionStore sessionStore;
    private final RusaintClient rusaintClient;

    public SaintSsoService(SaintSessionStore sessionStore, RusaintClient rusaintClient) {
        this.sessionStore = sessionStore;
        this.rusaintClient = rusaintClient;
    }

    public UsaintAuthResult authenticate(String sToken, String sIdno) {
        if (sToken == null || sToken.isBlank()) {
            throw new SaintAuthFailedException("sToken is required");
        }
        if (sIdno == null || sIdno.isBlank()) {
            throw new SaintAuthFailedException("sIdno is required");
        }

        try {
            RusaintAuthenticatedSession session =
                    rusaintClient.authenticateWithToken(sIdno.trim(), sToken);
            sessionStore.put(session.studentId(), new PortalCookies(session.sessionJson()));
            log.info("saint rusaint session stored: studentFp={}",
                    SaintSessionStore.fingerprint(session.studentId()));
            return new UsaintAuthResult(
                    session.studentId(),
                    session.name(),
                    session.major(),
                    session.enrollmentStatus());
        } catch (RusaintClientException exception) {
            throw new SaintAuthFailedException("rusaint token authentication failed", exception);
        }
    }
}
