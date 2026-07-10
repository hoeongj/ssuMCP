package com.ssuai.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryAfterTests {

    @Test
    void negativeDeltaSecondsIsTreatedAsAbsent() {
        assertThat(RetryAfter.parse("-5")).isEmpty();
    }

    @Test
    void pastHttpDateIsTreatedAsAbsent() {
        assertThat(RetryAfter.parse("Sun, 06 Nov 1994 08:49:37 GMT")).isEmpty();
    }

    @Test
    void zeroDeltaSecondsRemainsPresent() {
        assertThat(RetryAfter.parse("0")).hasValue(Duration.ZERO);
    }
}
