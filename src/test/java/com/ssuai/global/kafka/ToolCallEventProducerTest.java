package com.ssuai.global.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class ToolCallEventProducerTest {

    @Test
    void tryEmitDropsErrorWhenKafkaSendThrows() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("broker metadata unavailable"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ToolCallEventProducer producer = new ToolCallEventProducer(
                kafkaTemplate,
                properties(10),
                new ObjectMapper(),
                registry);

        try {
            assertThatCode(() -> producer.tryEmit("get_today_meal", "req-1", 12, "ok"))
                    .doesNotThrowAnyException();

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter(registry, "dropped_error")).isEqualTo(1.0));
        } finally {
            producer.shutdown();
        }
    }

    @Test
    void tryEmitDropsWhenBoundedQueueIsFull() throws InterruptedException {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CountDownLatch blockingSendsEntered = new CountDownLatch(2);
        CountDownLatch releaseSends = new CountDownLatch(1);

        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            blockingSendsEntered.countDown();
            releaseSends.await(5, TimeUnit.SECONDS);
            return new CompletableFuture<>();
        });

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ToolCallEventProducer producer = new ToolCallEventProducer(
                kafkaTemplate,
                properties(1),
                new ObjectMapper(),
                registry);

        try {
            assertThatCode(() -> producer.tryEmit("tool-a", "req-1", 1, "ok")).doesNotThrowAnyException();
            assertThatCode(() -> producer.tryEmit("tool-b", "req-2", 1, "ok")).doesNotThrowAnyException();
            assertThatCode(() -> producer.tryEmit("tool-c", "req-3", 1, "ok")).doesNotThrowAnyException();
            assertThat(blockingSendsEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatCode(() -> producer.tryEmit("tool-d", "req-4", 1, "ok")).doesNotThrowAnyException();

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter(registry, "dropped_queue_full")).isEqualTo(1.0));
        } finally {
            releaseSends.countDown();
            producer.shutdown();
        }
    }

    private static ToolCallKafkaProperties properties(int queueCapacity) {
        ToolCallKafkaProperties properties = new ToolCallKafkaProperties();
        properties.setTopic("mcp.toolcall.events.v1");
        properties.setQueueCapacity(queueCapacity);
        return properties;
    }

    private static double counter(SimpleMeterRegistry registry, String result) {
        return registry.find("mcp.toolcall.event")
                .tag("result", result)
                .counter()
                .count();
    }
}
