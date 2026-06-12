package com.ssuai.domain.library.timeseries;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.library.sampler")
public class LibrarySamplerSessionProperties {

    private String loginId = "";
    private String password = "";

    public String getLoginId() {
        return loginId;
    }

    public void setLoginId(String loginId) {
        this.loginId = loginId == null ? "" : loginId.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public boolean hasCredentials() {
        return !loginId.isBlank() && !password.isBlank();
    }
}
