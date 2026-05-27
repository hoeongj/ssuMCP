package com.ssuai.global.exception;

/**
 * Thrown when the saint.ssu.ac.kr phase 1 handshake rejects the supplied
 * {@code sToken} / {@code sIdno} pair (HTTP error, network failure, or the
 * response body is missing the {@code location.href = "/irj/portal"} success
 * marker). Intentionally a plain RuntimeException — the SSO callback
 * controller catches this and 302-redirects to the frontend with an
 * {@code error=auth_failed} query parameter, bypassing
 * {@code GlobalExceptionHandler}.
 */
public class SaintAuthFailedException extends RuntimeException {

    public SaintAuthFailedException(String message) {
        super(message);
    }

    public SaintAuthFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
