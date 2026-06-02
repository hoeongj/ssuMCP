package com.ssuai.global.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.ErrorCode;

class DiscordAlertServiceTests {

    private static final Instant T0 = Instant.parse("2026-06-02T10:00:00Z");

    @Test
    void blankWebhookUrlIsNoop() {
        CapturingSender sender = new CapturingSender();
        DiscordAlertService service = new DiscordAlertService(
                "", Duration.ofSeconds(60), Clock.fixed(T0, ZoneOffset.UTC), sender);

        service.alertConnectorFailure(
                AlertLevel.ERROR,
                ErrorCode.CONNECTOR_UNAVAILABLE,
                new ConnectorUnavailableException(new RuntimeException("token=secret")));

        assertThat(service.isEnabled()).isFalse();
        assertThat(sender.messages).isEmpty();
    }

    @Test
    void payloadContainsOnlySanitizedFailureMetadata() {
        CapturingSender sender = new CapturingSender();
        DiscordAlertService service = new DiscordAlertService(
                "https://discord.test/webhook", Duration.ofSeconds(60), Clock.fixed(T0, ZoneOffset.UTC), sender);

        service.alertConnectorFailure(
                AlertLevel.ERROR,
                ErrorCode.CONNECTOR_UNAVAILABLE,
                new ConnectorUnavailableException(new RuntimeException("token=secret cookie=abc password=pw raw body")));

        assertThat(sender.messages).hasSize(1);
        String content = sender.messages.getFirst().content();
        assertThat(content)
                .contains("level=ERROR", "code=CONNECTOR_UNAVAILABLE", "exception=ConnectorUnavailableException")
                .doesNotContain("secret", "cookie=abc", "password=pw", "raw body");
    }

    @Test
    void dedupesSameAlertWithinWindow() {
        MutableClock clock = new MutableClock(T0);
        CapturingSender sender = new CapturingSender();
        DiscordAlertService service = new DiscordAlertService(
                "https://discord.test/webhook", Duration.ofSeconds(60), clock, sender);

        ConnectorUnavailableException exception = new ConnectorUnavailableException();
        service.alertConnectorFailure(AlertLevel.ERROR, ErrorCode.CONNECTOR_UNAVAILABLE, exception);
        service.alertConnectorFailure(AlertLevel.ERROR, ErrorCode.CONNECTOR_UNAVAILABLE, exception);

        assertThat(sender.messages).hasSize(1);

        clock.advance(Duration.ofSeconds(61));
        service.alertConnectorFailure(AlertLevel.ERROR, ErrorCode.CONNECTOR_UNAVAILABLE, exception);

        assertThat(sender.messages).hasSize(2);
    }

    @Test
    void deliveryFailureIsSwallowed() {
        DiscordAlertService service = new DiscordAlertService(
                "https://discord.test/webhook",
                Duration.ofSeconds(60),
                Clock.fixed(T0, ZoneOffset.UTC),
                (url, message) -> {
                    throw new RuntimeException("webhook down");
                });

        assertThatCode(() -> service.alertConnectorFailure(
                AlertLevel.WARNING,
                ErrorCode.CONNECTOR_PARSE_ERROR,
                new ConnectorUnavailableException()))
                .doesNotThrowAnyException();
    }

    private static final class CapturingSender implements DiscordAlertService.WebhookSender {

        private final List<DiscordAlertService.DiscordWebhookMessage> messages = new ArrayList<>();

        @Override
        public void send(String webhookUrl, DiscordAlertService.DiscordWebhookMessage message) {
            messages.add(message);
        }
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
