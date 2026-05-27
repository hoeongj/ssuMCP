package com.ssuai.global.exception;

/**
 * Thrown when a realtime u-SAINT data path needs the portal cookies
 * captured during the SSO callback but the store has nothing — either
 * the user never completed an SSO flow this session, or the captured
 * cookies have aged past the store TTL ({@code ssuai.saint.session.ttl},
 * default 30 minutes).
 *
 * <p>Mapped by {@code GlobalExceptionHandler} to HTTP 401
 * {@code SAINT_SESSION_EXPIRED}, prompting the frontend to route the user
 * back to {@code /auth/login} for a fresh SSO loop.
 */
public class SaintSessionExpiredException extends ApiException {

    public SaintSessionExpiredException() {
        super(ErrorCode.SAINT_SESSION_EXPIRED);
    }

    public SaintSessionExpiredException(String message) {
        super(ErrorCode.SAINT_SESSION_EXPIRED, message);
    }
}
