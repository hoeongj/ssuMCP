package com.ssuai.domain.auth.saint;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.saint")
public class SaintSsoProperties {

    private String ssoUrl = "https://saint.ssu.ac.kr/webSSO/sso.jsp";
    private String portalUrl = "https://saint.ssu.ac.kr/irj/portal";
    private Duration timeout = Duration.ofSeconds(10);

    public String getSsoUrl() {
        return ssoUrl;
    }

    public void setSsoUrl(String ssoUrl) {
        this.ssoUrl = ssoUrl;
    }

    public String getPortalUrl() {
        return portalUrl;
    }

    public void setPortalUrl(String portalUrl) {
        this.portalUrl = portalUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
