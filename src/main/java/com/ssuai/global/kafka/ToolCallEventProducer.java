package com.ssuai.global.kafka;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ssuai.kafka", name = "enabled", havingValue = "true")
public class ToolCallEventProducer {

    private static final String METRIC_NAME = "mcp.toolcall.event";
    private static final String RESULT_SENT = "sent";
    private static final String RESULT_DROPPED_QUEUE_FULL = "dropped_queue_full";
    private static final String RESULT_DROPPED_ERROR = "dropped_error";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ToolCallKafkaProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;
    private final ThreadPoolExecutor executor;

    public ToolCallEventProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            ToolCallKafkaProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry registry) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.executor = new ThreadPoolExecutor(
                1,
                2,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                namedThreadFactory("toolcall-kafka-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.prestartCoreThread();
    }

    public void tryEmit(String toolName, String requestId, long durationMs, String outcome) {
        try {
            ToolCallEvent event = new ToolCallEvent(
                    toolName,
                    requestId,
                    durationMs,
                    outcome,
                    System.currentTimeMillis(),
                    ToolCallEvent.SCHEMA_VERSION);
            try {
                executor.execute(() -> send(event));
            } catch (RejectedExecutionException ex) {
                increment(RESULT_DROPPED_QUEUE_FULL);
            }
        } catch (Throwable ex) {
            increment(RESULT_DROPPED_ERROR);
        }
    }

    private void send(ToolCallEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(properties.getTopic(), event.toolName(), json)
                    .whenComplete((metadata, ex) -> increment(ex == null ? RESULT_SENT : RESULT_DROPPED_ERROR));
        } catch (Throwable ex) {
            increment(RESULT_DROPPED_ERROR);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void increment(String result) {
        try {
            registry.counter(METRIC_NAME, "result", result).increment();
        } catch (Throwable ignored) {
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
