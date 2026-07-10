package com.ssuai.global.exception;

import java.time.Duration;

public class ConnectorRateLimitedException extends ConnectorException {

    private final Duration retryAfter;

    public ConnectorRateLimitedException(Duration retryAfter, Throwable cause) {
        super(ErrorCode.UPSTREAM_RATE_LIMITED, cause);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
