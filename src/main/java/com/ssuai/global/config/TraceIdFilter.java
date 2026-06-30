package com.ssuai.global.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    // MDC key is "requestId" (not "traceId") so Micrometer Tracing owns the "traceId"/"spanId"
    // MDC keys for log<->trace correlation. The X-Trace-Id response header and the API
    // envelope's traceId field keep sourcing this per-request id (value unchanged).
    public static final String TRACE_ID_KEY = "requestId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final int MAX_TRACE_ID_LENGTH = 128;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private static String resolveTraceId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String candidate = headerValue.strip();
        if (candidate.length() > MAX_TRACE_ID_LENGTH || hasControlCharacter(candidate)) {
            return UUID.randomUUID().toString();
        }
        return candidate;
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }
}
