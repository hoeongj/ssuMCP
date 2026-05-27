package com.ssuai.domain.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for ssuAI's authentication endpoints (Task 14).
 * Separate from {@code JwtProperties} (which only governs JWT signing /
 * TTLs) and from the {@code ssuai.saint.*} block (which configures the
 * upstream saint.ssu.ac.kr handshake inside {@code SaintSsoService}).
 */
@Component
@ConfigurationProperties(prefix = "ssuai.auth")
public class AuthProperties {

    /** Public origin of THIS backend, used to build the SmartID apiReturnUrl. */
    private String apiBaseUrl = "http://localhost:8080";

    /**
     * Public origin for MCP auth callbacks (login URL + SmartID return URL).
     * Defaults to apiBaseUrl when blank. Set to the k3s backend URL directly
     * so MCP auth bypasses the Vercel proxy; MCP SSE is already on
     * ssumcp.duckdns.org, and unlike the web auth flow there are no cookies
     * that need to land on the Vercel domain.
     */
    private String mcpApiBaseUrl = "";

    /** SmartID SSO entry point. SSU's central SSO. */
    private String smartidSsoUrl = "https://smartid.ssu.ac.kr/Symtra_sso/smln.asp";

    private RefreshCookie refreshCookie = new RefreshCookie();

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getMcpApiBaseUrl() {
        return (mcpApiBaseUrl != null && !mcpApiBaseUrl.isBlank()) ? mcpApiBaseUrl : apiBaseUrl;
    }

    public void setMcpApiBaseUrl(String mcpApiBaseUrl) {
        this.mcpApiBaseUrl = mcpApiBaseUrl;
    }

    public String getSmartidSsoUrl() {
        return smartidSsoUrl;
    }

    public void setSmartidSsoUrl(String smartidSsoUrl) {
        this.smartidSsoUrl = smartidSsoUrl;
    }

    public RefreshCookie getRefreshCookie() {
        return refreshCookie;
    }

    public void setRefreshCookie(RefreshCookie refreshCookie) {
        this.refreshCookie = refreshCookie;
    }

    /**
     * Settings for the refresh-token HttpOnly cookie. Path is scoped to
     * {@code /api/auth} so only refresh / logout endpoints ever receive it
     * (Task 14 spec §9 — refresh cookie path scoping).
     */
    public static class RefreshCookie {

        private String name = "ssuai_refresh";
        private String path = "/api/auth";
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
