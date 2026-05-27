package com.ssuai.domain.auth.lms;

/**
 * Raw {@code Cookie} header value captured after the LMS two-phase auth
 * (SmartID SSO → gw-cb.php → canvas dashboard). Sent as-is on every
 * canvas.ssu.ac.kr API request. Never logged — see docs/security.md §4.
 */
public record LmsCookies(String rawCookieHeader) {

    public LmsCookies {
        if (rawCookieHeader == null || rawCookieHeader.isBlank()) {
            throw new IllegalArgumentException("rawCookieHeader is required");
        }
    }
}
