package com.ssuai.global.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext
@EmbeddedKafka(
        partitions = 1,
        topics = "mcp.toolcall.events.v1",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@SpringBootTest(
        classes = {
                ToolCallKafkaConfig.class,
                ToolCallEventProducer.class,
                ToolCallEventConsumer.class,
                ToolCallEventPipelineIT.TestConfig.class
        },
        properties = {
                "ssuai.kafka.enabled=true",
                "ssuai.kafka.topic=mcp.toolcall.events.v1",
                "ssuai.kafka.partitions=1",
                "ssuai.kafka.queue-capacity=10"
        })
class ToolCallEventPipelineIT {

    private final ToolCallEventProducer producer;
    private final ObjectMapper objectMapper;
    private final KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    ToolCallEventPipelineIT(
            ToolCallEventProducer producer,
            ObjectMapper objectMapper,
            KafkaListenerEndpointRegistry listenerRegistry) {
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.listenerRegistry = listenerRegistry;
    }

    @Test
    void producerConsumerRoundTripLogsWhitelistedFieldsOnly() throws Exception {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }

        Logger logger = (Logger) LoggerFactory.getLogger("mcp.toolcall.audit");
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            producer.tryEmit("get_today_meal", "req-pipeline", 42, "ok");

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(appender.list).isNotEmpty();
                String message = appender.list.get(0).getFormattedMessage();
                assertThat(message)
                        .contains("toolName=get_today_meal")
                        .contains("requestId=req-pipeline")
                        .contains("durationMs=42")
                        .contains("outcome=ok")
                        .contains("timestampEpochMs=");
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        String json = objectMapper.writeValueAsString(new ToolCallEvent(
                "get_today_meal",
                "req-pipeline",
                42,
                "ok",
                123456789L,
                ToolCallEvent.SCHEMA_VERSION));
        JsonNode node = objectMapper.readTree(json);
        Set<String> keySet = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keySet::add);

        assertThat(keySet).containsExactlyInAnyOrder(
                "toolName",
                "requestId",
                "durationMs",
                "outcome",
                "timestampEpochMs",
                "schemaVersion");
        assertThat(json)
                .doesNotContain("token")
                .doesNotContain("session")
                .doesNotContain("principal");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
