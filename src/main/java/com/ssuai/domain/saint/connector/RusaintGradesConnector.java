package com.ssuai.domain.saint.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.global.exception.SaintSessionExpiredException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-grades", havingValue = "rusaint")
public class RusaintGradesConnector implements SaintGradesConnector {

    private final RusaintClient rusaintClient;

    public RusaintGradesConnector(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public GradesResponse fetchGrades(String studentId, PortalCookies cookies) {
        try {
            return rusaintClient.fetchGrades(studentId, cookies.sessionJson());
        } catch (RusaintClientException exception) {
            throw new SaintSessionExpiredException("rusaint grades session rejected");
        }
    }
}
