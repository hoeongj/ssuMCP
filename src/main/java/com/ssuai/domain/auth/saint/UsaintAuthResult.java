package com.ssuai.domain.auth.saint;

import java.util.Objects;

/**
 * Identity confirmed by the saint.ssu.ac.kr 2-phase auth handshake. Returned
 * by {@link SaintSsoService#authenticate(String, String)} and consumed by the
 * SSO callback controller to upsert a {@code Student} and mint a ssuAI JWT.
 *
 * <p>Contains no SSO tokens — sToken/sIdno are scoped to the service method
 * call only and discarded once this record is built (Task 14 spec §1, §5).
 */
public record UsaintAuthResult(
        String studentId,
        String name,
        String major,
        String enrollmentStatus
) {

    public UsaintAuthResult {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(name, "name");
        if (studentId.isBlank()) {
            throw new IllegalArgumentException("studentId must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
