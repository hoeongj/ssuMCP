package com.ssuai.global.kafka;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ssuai.kafka", name = "enabled", havingValue = "true")
public class ToolCallEventConsumer {

    private static final Logger auditLog = LoggerFactory.getLogger("mcp.toolcall.audit");

    private final ObjectMapper objectMapper;

    public ToolCallEventConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${ssuai.kafka.topic}",
            groupId = "mcp-tool-call-logger",
            containerFactory = "toolCallKafkaListenerContainerFactory")
    public void onEvent(String json) {
        try {
            ToolCallEvent event = objectMapper.readValue(json, ToolCallEvent.class);
            auditLog.info("mcp tool call event {} {} {} {} {}",
                    kv("toolName", event.toolName()),
                    kv("requestId", event.requestId()),
                    kv("durationMs", event.durationMs()),
                    kv("outcome", event.outcome()),
                    kv("timestampEpochMs", event.timestampEpochMs()));
        } catch (Exception ex) {
            auditLog.warn("dropped malformed mcp tool call event: {}", ex.getClass().getSimpleName());
        }
    }
}
