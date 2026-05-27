package com.ssuai.domain.saint.connector;

public record RusaintAuthenticatedSession(
        String studentId,
        String name,
        String major,
        String enrollmentStatus,
        String sessionJson
) {

    public RusaintAuthenticatedSession {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (sessionJson == null || sessionJson.isBlank()) {
            throw new IllegalArgumentException("sessionJson is required");
        }
    }
}
