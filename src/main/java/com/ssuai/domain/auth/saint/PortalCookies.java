package com.ssuai.domain.auth.saint;

/**
 * Plaintext SAINT session material loaded from {@link SaintSessionStore}.
 *
 * <p>The legacy Java WebDynpro connectors interpret {@code rawCookieHeader}
 * as an HTTP {@code Cookie} header. The rusaint connectors interpret the same
 * opaque slot as serialized rusaint session JSON. Keeping the record shape
 * stable avoids a session-store migration; existing entries simply expire and
 * users re-run SmartID SSO.
 *
 * <p>Never log {@code rawCookieHeader} directly. It can contain upstream
 * cookies or rusaint's serialized authenticated session.
 */
public record PortalCookies(String rawCookieHeader) {

    public PortalCookies {
        if (rawCookieHeader == null || rawCookieHeader.isBlank()) {
            throw new IllegalArgumentException("rawCookieHeader is required");
        }
    }

    public String sessionJson() {
        return rawCookieHeader;
    }
}
