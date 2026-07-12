package com.ssuai.domain.library.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.library.session")
public class LibrarySessionProperties {

    private Duration ttl = Duration.ofHours(2);
    private int maxSessions = 1000;
    private int maxTokenLength = 4096;
    private String encryptionKey = "";
    private SessionCookie cookie = new SessionCookie();

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

    public int getMaxTokenLength() {
        return maxTokenLength;
    }

    public void setMaxTokenLength(int maxTokenLength) {
        this.maxTokenLength = maxTokenLength;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public SessionCookie getCookie() {
        return cookie;
    }

    public void setCookie(SessionCookie cookie) {
        this.cookie = cookie;
    }

    /**
     * Settings for the persistent library-session cookie minted at {@code POST
     * /api/library/login} (ADR 0096). The cookie value is the {@link LibrarySessionStore} key —
     * a server-generated random UUID, never derived from the Tomcat servlet session — so the
     * library login survives a backend redeploy or a pod switch across replicas. Mirrors
     * {@code AuthProperties.RefreshCookie}. {@code httpOnly} is intentionally not configurable:
     * it is always {@code true}, hard-coded where the {@code ResponseCookie} is built, since
     * there is never a legitimate reason for browser JS to read this value.
     */
    public static class SessionCookie {

        private String name = "ssuai_library_session";
        private String path = "/";
        private boolean secure = false;
        private String sameSite = "Lax";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }
    }
}
