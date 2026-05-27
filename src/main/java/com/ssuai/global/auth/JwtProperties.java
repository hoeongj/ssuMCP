package com.ssuai.global.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.jwt")
public class JwtProperties {

    /**
     * Base64-encoded HMAC-SHA256 secret. Must decode to at least 32 bytes.
     * Production reads this from SSUAI_JWT_SECRET — never committed.
     * Empty default → JwtProvider auto-generates a random secret on start
     * (dev/test convenience) and fails fast in prod via configuration check.
     */
    private String secret = "";

    private String issuer = "ssuai";

    private Duration accessTtl = Duration.ofMinutes(15);

    private Duration refreshTtl = Duration.ofDays(14);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }
}
