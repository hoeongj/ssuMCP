package com.ssuai.global.response;

import java.util.UUID;

import org.slf4j.MDC;

public record ApiResponse<T>(
        T data,
        ErrorResponse error,
        String traceId
) {

    private static final String TRACE_ID_KEY = "traceId";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, null, currentTraceId());
    }

    public static <T> ApiResponse<T> error(ErrorResponse error) {
        return new ApiResponse<>(null, error, currentTraceId());
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString();
    }
}

