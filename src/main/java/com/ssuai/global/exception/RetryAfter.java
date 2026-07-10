package com.ssuai.global.exception;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class RetryAfter {

    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private RetryAfter() {
    }

    public static Optional<Duration> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        Optional<Duration> deltaSeconds = parseDeltaSeconds(trimmed);
        if (deltaSeconds.isPresent()) {
            return deltaSeconds;
        }
        return parseHttpDate(trimmed);
    }

    private static Optional<Duration> parseDeltaSeconds(String value) {
        if (!value.matches("-?\\d+")) {
            return Optional.empty();
        }
        BigInteger seconds = new BigInteger(value);
        if (seconds.signum() < 0) {
            return Optional.of(Duration.ZERO);
        }
        BigInteger cappedSeconds = seconds.min(BigInteger.valueOf(MAX_RETRY_AFTER.toSeconds()));
        return Optional.of(Duration.ofSeconds(cappedSeconds.longValue()));
    }

    private static Optional<Duration> parseHttpDate(String value) {
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Optional.of(clamp(Duration.between(Instant.now(), retryAt)));
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    private static Duration clamp(Duration duration) {
        if (duration.isNegative()) {
            return Duration.ZERO;
        }
        if (duration.compareTo(MAX_RETRY_AFTER) > 0) {
            return MAX_RETRY_AFTER;
        }
        return duration;
    }
}
