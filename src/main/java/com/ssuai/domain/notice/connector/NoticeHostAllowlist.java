package com.ssuai.domain.notice.connector;

import java.net.URI;
import java.util.Locale;

/**
 * SSRF host allowlist for notice fetches (security follow-up #13): http(s) only, host equal to
 * the official Soongsil domain or one of its subdomains. Shared by the initial caller-supplied
 * URL check in {@code NoticeService} and the per-hop redirect re-validation in
 * {@code RealNoticeConnector}, so both gates can never drift apart.
 */
public final class NoticeHostAllowlist {

    /** Official Soongsil domain — notice pages live under scatch.ssu.ac.kr. */
    public static final NoticeHostAllowlist OFFICIAL = new NoticeHostAllowlist("ssu.ac.kr");

    private final String allowedDomain;

    // package-private so connector tests can point the allowlist at a local mock server host
    NoticeHostAllowlist(String allowedDomain) {
        this.allowedDomain = allowedDomain.toLowerCase(Locale.ROOT);
    }

    /**
     * An IP literal or any other host can never end with "." + the allowed domain, so this
     * also blocks internal/link-local addresses (defense-in-depth).
     */
    public boolean allows(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return normalizedHost.equals(allowedDomain)
                || normalizedHost.endsWith("." + allowedDomain);
    }
}
