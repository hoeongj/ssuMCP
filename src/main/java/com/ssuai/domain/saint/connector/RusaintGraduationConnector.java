package com.ssuai.domain.saint.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.global.exception.SaintSessionExpiredException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-graduation", havingValue = "rusaint")
public class RusaintGraduationConnector implements SaintGraduationConnector {

    private final RusaintClient rusaintClient;

    public RusaintGraduationConnector(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public GraduationStatus fetchGraduationRequirements(String studentId, PortalCookies cookies) {
        try {
            return rusaintClient.fetchGraduationRequirements(studentId, cookies.sessionJson());
        } catch (RusaintClientException exception) {
            throw new SaintSessionExpiredException("rusaint graduation session rejected");
        }
    }
}
