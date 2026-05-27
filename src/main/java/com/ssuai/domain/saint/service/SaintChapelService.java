package com.ssuai.domain.saint.service;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintChapelConnector;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class SaintChapelService {

    private final SaintChapelConnector connector;
    private final SaintSessionStore sessionStore;

    public SaintChapelService(SaintChapelConnector connector, SaintSessionStore sessionStore) {
        this.connector = connector;
        this.sessionStore = sessionStore;
    }

    public ChapelInfo fetchChapelInfo(String studentId, Integer year, String semester) {
        requireStudentId(studentId);
        if (year != null && year <= 0) {
            throw new IllegalArgumentException("year must be positive");
        }
        String normalizedSemester = normalizeSemester(semester);
        PortalCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(SaintSessionExpiredException::new);
        return connector.fetchChapelInfo(studentId, cookies, year, normalizedSemester);
    }

    private static void requireStudentId(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
    }

    private static String normalizeSemester(String semester) {
        if (semester == null || semester.isBlank()) {
            return null;
        }
        return switch (semester.trim().toUpperCase(Locale.ROOT)) {
            case "1", "ONE", "1학기" -> "1학기";
            case "SUMMER", "여름학기" -> "여름학기";
            case "2", "TWO", "2학기" -> "2학기";
            case "WINTER", "겨울학기" -> "겨울학기";
            default -> throw new IllegalArgumentException(
                    "semester must be one of 1학기, 여름학기, 2학기, 겨울학기");
        };
    }
}
