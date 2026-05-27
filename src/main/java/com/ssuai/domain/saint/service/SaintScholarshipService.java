package com.ssuai.domain.saint.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintScholarshipConnector;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class SaintScholarshipService {

    private final SaintScholarshipConnector connector;
    private final SaintSessionStore sessionStore;

    public SaintScholarshipService(SaintScholarshipConnector connector, SaintSessionStore sessionStore) {
        this.connector = connector;
        this.sessionStore = sessionStore;
    }

    public List<ScholarshipEntry> fetchScholarships(String studentId, Integer year) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        if (year != null && year <= 0) {
            throw new IllegalArgumentException("year must be positive");
        }
        PortalCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(SaintSessionExpiredException::new);
        List<ScholarshipEntry> entries = connector.fetchScholarships(studentId, cookies);
        if (year == null) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> entry.year() == year)
                .toList();
    }
}
