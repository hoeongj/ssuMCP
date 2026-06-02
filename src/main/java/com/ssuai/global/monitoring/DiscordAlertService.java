package com.ssuai.global.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.ssuai.global.exception.ErrorCode;

@Component
public class DiscordAlertService {

    private static final Logger log = LoggerFactory.getLogger(DiscordAlertService.class);

    private final String webhookUrl;
    private final Duration dedupeWindow;
    private final Clock clock;
    private final WebhookSender webhookSender;
    private final Map<String, Instant> lastSentAt = new ConcurrentHashMap<>();

    public DiscordAlertService(
            @Value("${ssuai.monitoring.discord.webhook-url:}") String webhookUrl,
            @Value("${ssuai.monitoring.discord.dedupe-window:60s}") Duration dedupeWindow) {
        this(webhookUrl, dedupeWindow, Clock.systemUTC(), new RestClientWebhookSender(RestClient.builder().build()));
    }

    DiscordAlertService(
            String webhookUrl,
            Duration dedupeWindow,
            Clock clock,
            WebhookSender webhookSender) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.dedupeWindow = dedupeWindow == null || dedupeWindow.isNegative() ? Duration.ofSeconds(60) : dedupeWindow;
        this.clock = clock;
        this.webhookSender = webhookSender;
    }

    public void alertConnectorFailure(AlertLevel level, ErrorCode errorCode, Throwable exception) {
        if (!isEnabled()) {
            return;
        }
        AlertLevel safeLevel = level == null ? AlertLevel.WARNING : level;
        ErrorCode safeErrorCode = errorCode == null ? ErrorCode.CONNECTOR_ERROR : errorCode;
        String exceptionType = exception == null ? "UnknownException" : exception.getClass().getSimpleName();
        String dedupeKey = safeLevel + ":" + safeErrorCode.name() + ":" + exceptionType;
        if (!shouldSend(dedupeKey)) {
            return;
        }

        try {
            webhookSender.send(webhookUrl, new DiscordWebhookMessage(formatContent(safeLevel, safeErrorCode, exceptionType)));
        } catch (RuntimeException sendFailure) {
            log.warn("Discord alert delivery failed: level={} code={} exceptionType={}",
                    safeLevel, safeErrorCode.name(), exceptionType, sendFailure);
        }
    }

    boolean isEnabled() {
        return !webhookUrl.isBlank();
    }

    private synchronized boolean shouldSend(String dedupeKey) {
        Instant now = clock.instant();
        Instant lastSent = lastSentAt.get(dedupeKey);
        if (lastSent != null && lastSent.plus(dedupeWindow).isAfter(now)) {
            return false;
        }
        lastSentAt.put(dedupeKey, now);
        return true;
    }

    private static String formatContent(AlertLevel level, ErrorCode errorCode, String exceptionType) {
        return "ssuMCP connector alert"
                + "\nlevel=" + level
                + "\ncode=" + errorCode.name()
                + "\nexception=" + exceptionType;
    }

    record DiscordWebhookMessage(String content) {
    }

    interface WebhookSender {
        void send(String webhookUrl, DiscordWebhookMessage message);
    }

    private static final class RestClientWebhookSender implements WebhookSender {

        private final RestClient restClient;

        private RestClientWebhookSender(RestClient restClient) {
            this.restClient = restClient;
        }

        @Override
        public void send(String webhookUrl, DiscordWebhookMessage message) {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();
        }
    }
}
