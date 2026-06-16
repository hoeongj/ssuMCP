package com.ssuai.domain.auth.lms;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.lms")
public class LmsSsoProperties {

    private String gwCallbackUrl = "https://lms.ssu.ac.kr/xn-sso/gw-cb.php";
    private String canvasBaseUrl = "https://canvas.ssu.ac.kr";
    private String commonsBaseUrl = "https://commons.ssu.ac.kr";
    private Duration timeout = Duration.ofSeconds(10);

    public String getGwCallbackUrl() { return gwCallbackUrl; }
    public void setGwCallbackUrl(String gwCallbackUrl) { this.gwCallbackUrl = gwCallbackUrl; }

    public String getCanvasBaseUrl() { return canvasBaseUrl; }
    public void setCanvasBaseUrl(String canvasBaseUrl) { this.canvasBaseUrl = canvasBaseUrl; }

    public String getCommonsBaseUrl() { return commonsBaseUrl; }
    public void setCommonsBaseUrl(String commonsBaseUrl) { this.commonsBaseUrl = commonsBaseUrl; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
}
