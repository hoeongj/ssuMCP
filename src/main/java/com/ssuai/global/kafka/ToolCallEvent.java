package com.ssuai.global.kafka;

public record ToolCallEvent(
        String toolName,
        String requestId,
        long durationMs,
        String outcome,
        long timestampEpochMs,
        int schemaVersion) {

    public static final int SCHEMA_VERSION = 1;
}
