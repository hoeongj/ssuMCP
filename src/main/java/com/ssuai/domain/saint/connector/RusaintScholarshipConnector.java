package com.ssuai.domain.saint.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.global.exception.SaintSessionExpiredException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-scholarship", havingValue = "rusaint")
public class RusaintScholarshipConnector implements SaintScholarshipConnector {

    private final RusaintClient rusaintClient;

    public RusaintScholarshipConnector(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public List<ScholarshipEntry> fetchScholarships(String studentId, PortalCookies cookies) {
        try {
            return rusaintClient.fetchScholarships(studentId, cookies.sessionJson());
        } catch (RusaintClientException exception) {
            throw new SaintSessionExpiredException("rusaint scholarship session rejected");
        }
    }
}
