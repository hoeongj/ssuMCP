package com.ssuai.global.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the originating client IP for per-IP rate limiting (security review
 * Wave 3, Codex #9).
 *
 * <h2>Why {@code X-Forwarded-For} and how much of it we trust</h2>
 * <p>The backend runs behind a k3s ingress, so {@code getRemoteAddr()} is the
 * ingress hop, not the real client — every request would share one IP and the
 * per-IP limiter would be useless. The ingress appends the immediate peer to
 * {@code X-Forwarded-For}, so the chain reads {@code client, proxy1, proxy2, …}
 * left → right. We therefore take the <em>left-most</em> entry as the client.</p>
 *
 * <p>{@code X-Forwarded-For} is client-spoofable in general, so we trust it ONLY
 * for the narrow purpose of bucketing rate-limit counters — never for authz or
 * audit identity. A caller that forges the header can at worst spread its own
 * traffic across fake IPs (weakening its own throttle) or impersonate another
 * IP's bucket; neither escalates privileges. When the header is absent or blank
 * we fall back to {@code getRemoteAddr()}.</p>
 */
final class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    /**
     * Returns the best-effort client IP: the left-most {@code X-Forwarded-For}
     * entry when present, else {@code getRemoteAddr()}. Never returns blank —
     * falls back to {@code "unknown"} only if both sources are empty (so all
     * such requests share one bucket rather than bypassing the limiter).
     */
    static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            // Left-most hop is the original client (ingress appends on the right).
            int comma = forwarded.indexOf(',');
            String client = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!client.isEmpty()) {
                return client;
            }
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && !remoteAddr.isBlank()) {
            return remoteAddr;
        }
        return "unknown";
    }
}
