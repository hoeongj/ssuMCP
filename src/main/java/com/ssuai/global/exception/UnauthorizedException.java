package com.ssuai.global.exception;

/**
 * Thrown by controllers when an endpoint requires a valid ssuAI access JWT
 * but the request didn't carry one (missing/invalid/expired
 * {@code Authorization: Bearer ...}). Mapped by GlobalExceptionHandler to
 * HTTP 401 with code {@code UNAUTHORIZED}.
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException() {
        super(ErrorCode.UNAUTHORIZED);
    }
}
