package com.ssuai.global.exception;

/**
 * Thrown when an LMS API call fails with a non-authentication error
 * (any non-2xx status other than 401/403).
 * Distinct from {@link LmsSessionExpiredException} to prevent mis-reporting
 * API errors as "session expired / please log in again".
 */
public class LmsApiException extends RuntimeException {

    private final int statusCode;

    public LmsApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
