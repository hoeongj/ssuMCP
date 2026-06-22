package com.ssuai.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RefreshTokenDenylistTests {

    private static final Instant T0 = Instant.parse("2026-05-16T10:00:00Z");

    @Test
    void deniesJtiUntilExpiryThenPrunesIt() {
        MutableClock clock = new MutableClock(T0);
        RefreshTokenDenylist denylist = new RefreshTokenDenylist(clock);

        denylist.deny("refresh-jti", T0.plus(Duration.ofMinutes(5)));

        assertThat(denylist.isDenied("refresh-jti")).isTrue();

        clock.advance(Duration.ofMinutes(5));

        assertThat(denylist.isDenied("refresh-jti")).isFalse();
        assertThat(denylist.size()).isZero();
    }

    @Test
    void ignoresBlankOrAlreadyExpiredJtis() {
        RefreshTokenDenylist denylist = new RefreshTokenDenylist(Clock.fixed(T0, ZoneOffset.UTC));

        denylist.deny("", T0.plus(Duration.ofMinutes(5)));
        denylist.deny("expired-jti", T0.minusSeconds(1));

        assertThat(denylist.isDenied("")).isFalse();
        assertThat(denylist.isDenied("expired-jti")).isFalse();
        assertThat(denylist.size()).isZero();
    }

    @Test
    void failsOpenWhenRedisErrorsOnBothReadAndWrite() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getBucket(anyString(), eq(StringCodec.INSTANCE)))
                .thenThrow(new IllegalStateException("redis down"));
        RefreshTokenDenylist denylist =
                new RefreshTokenDenylist(redissonClient, Clock.fixed(T0, ZoneOffset.UTC));

        // Write error must not propagate (refresh/logout hot path).
        assertThatCode(() -> denylist.deny("jti", T0.plus(Duration.ofMinutes(5))))
                .doesNotThrowAnyException();
        // Read error must fail open (treated as not-denied).
        assertThat(denylist.isDenied("jti")).isFalse();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
