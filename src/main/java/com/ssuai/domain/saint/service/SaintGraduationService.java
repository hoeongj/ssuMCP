package com.ssuai.domain.saint.service;

import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintGraduationConnector;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class SaintGraduationService {

    private final SaintGraduationConnector connector;
    private final SaintSessionStore sessionStore;

    public SaintGraduationService(SaintGraduationConnector connector, SaintSessionStore sessionStore) {
        this.connector = connector;
        this.sessionStore = sessionStore;
    }

    public GraduationStatus fetchGraduationRequirements(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        PortalCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(SaintSessionExpiredException::new);
        return connector.fetchGraduationRequirements(studentId, cookies);
    }
}
