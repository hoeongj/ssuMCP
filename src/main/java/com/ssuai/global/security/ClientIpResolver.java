package com.ssuai.global.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the originating client IP for per-IP rate limiting (security review
 * Wave 3; multi-pod HA hardening — SCALE-ROADMAP Phase 1 audit item A2).
 *
 * <h2>Why {@code X-Forwarded-For} and how much of it we trust</h2>
 * <p>The backend runs behind a k3s Traefik ingress (and, for some routes, an
 * additional Vercel proxy in front of that), so {@code getRemoteAddr()} is
 * only the nearest trusted proxy's hop, never the real client — every request
 * would share one IP and the per-IP limiter would be useless. Each proxy in
 * the chain <em>appends</em> the address of whichever peer it received the
 * connection from, so the header grows left → right as it passes through more
 * hops: {@code client, proxy1, proxy2, …, proxyN} where {@code proxyN} is the
 * value appended by the hop closest to us.</p>
 *
 * <h2>Right-hop trust, not left-most (2026-07 fix)</h2>
 * <p>{@code X-Forwarded-For} is entirely client-supplied input up to the point
 * our own infrastructure starts appending to it — a client can prepend
 * whatever left-most value it likes (including a comma-separated string that
 * fakes an entire proxy chain), so trusting the left-most entry lets it rotate
 * its rate-limit bucket on every request. What we <em>can</em> trust is the
 * fixed number of hops our own infrastructure contributes: with
 * {@code trustedProxyCount} configured proxies between the client and us, the
 * real client IP is always exactly {@code trustedProxyCount} entries from the
 * <em>right</em> end of the header, because that's how many trustworthy
 * append operations happened after the client's original (forgeable) input.
 * Everything to the left of that position — however many entries an attacker
 * chooses to prepend — is ignored.</p>
 *
 * <p>Default {@code trustedProxyCount} is {@code 1} (Traefik ingress only,
 * the common case). Deployments that additionally sit behind Vercel for some
 * routes configure {@code 2} so the resolved position accounts for both
 * trusted append operations. If the header has fewer entries than
 * {@code trustedProxyCount} (malformed input, or a hop was skipped) we cannot
 * safely compute a trusted position, so we fall back to
 * {@code getRemoteAddr()} rather than guessing — that fallback can never be
 * client-spoofed, only coarser (it collapses to the nearest proxy's IP).</p>
 *
 * <p>{@code X-Forwarded-For} is trusted ONLY for the narrow purpose of
 * bucketing rate-limit counters — never for authz or audit identity. When the
 * header is absent, blank, or unusable we fall back to
 * {@code getRemoteAddr()}.</p>
 */
final class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    /**
     * Returns the best-effort client IP: the {@code X-Forwarded-For} entry
     * {@code trustedProxyCount} hops from the right when the header has at
     * least that many entries, else {@code getRemoteAddr()}. Never returns
     * blank — falls back to {@code "unknown"} only if both sources are empty
     * (so all such requests share one bucket rather than bypassing the
     * limiter).
     *
     * @param trustedProxyCount number of trusted proxy hops between the
     *                          client and this service (0 disables XFF
     *                          entirely and always uses {@code getRemoteAddr()})
     */
    static String resolve(HttpServletRequest request, int trustedProxyCount) {
        if (trustedProxyCount > 0) {
            String forwarded = request.getHeader(X_FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                if (hops.length >= trustedProxyCount) {
                    String candidate = hops[hops.length - trustedProxyCount].trim();
                    if (!candidate.isEmpty()) {
                        return candidate;
                    }
                }
                // Fewer usable entries than trustedProxyCount (or a blank entry at the
                // trusted position) — cannot locate a trustworthy hop. Fall through to
                // remoteAddr instead of guessing at a left-most, attacker-controlled value.
            }
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && !remoteAddr.isBlank()) {
            return remoteAddr;
        }
        return "unknown";
    }
}
