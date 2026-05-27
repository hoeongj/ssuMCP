package com.ssuai.domain.saint.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.global.exception.SaintSessionExpiredException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-chapel", havingValue = "rusaint")
public class RusaintChapelConnector implements SaintChapelConnector {

    private final RusaintClient rusaintClient;

    public RusaintChapelConnector(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public ChapelInfo fetchChapelInfo(String studentId, PortalCookies cookies, Integer year, String semester) {
        try {
            return rusaintClient.fetchChapelInfo(studentId, cookies.sessionJson(), year, semester);
        } catch (RusaintClientException exception) {
            throw new SaintSessionExpiredException("rusaint chapel session rejected");
        }
    }
}
