package com.ssuai.domain.auth.saint;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for {@link SaintSessionStore}.
 *
 * <p>{@code encryptionKey} reads the shared
 * {@code SSUAI_CREDENTIAL_ENCRYPTION_KEY} env var
 * ({@code docs/security.md} §3). Empty default → {@code SaintSessionStore}
 * generates an ephemeral random key per JVM start (dev/test convenience —
 * any stored cookies become unreadable after restart, forcing the user
 * to re-do SSO). Prod must set the env var; otherwise rotating the JVM
 * silently invalidates everyone's saved session.
 *
 * <p>{@code ttl} caps how long the post-SSO portal cookies live in the
 * store. 30 minutes is the spec default ({@code docs/tasks/16-...} §1).
 * Shorter than the worst-case upstream cookie lifetime, so the store
 * never holds something the upstream already rejected.
 *
 * <p>{@code maxSessions} bounds the LRU map so a flood of SSO callbacks
 * cannot exhaust heap; eldest-accessed entry evicts when the cap is hit.
 */
@Component
@ConfigurationProperties(prefix = "ssuai.saint.session")
public class SaintSessionProperties {

    private Duration ttl = Duration.ofMinutes(30);
    private int maxSessions = 1000;
    private String encryptionKey = "";

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
}
