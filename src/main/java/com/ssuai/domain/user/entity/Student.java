package com.ssuai.domain.user.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * ssuAI's first user table — populated by the u-SAINT SSO callback after
 * the SmartID → saint 2-phase handshake confirms identity. {@code studentId}
 * is the SSU student number and serves as the primary key.
 *
 * <p>Passwords are never stored here (SSU SSO handles them on its own
 * login page; see Task 14 §1 non-goals). Major/department strings are
 * stored verbatim — we do not gate ssuAI features by major.
 */
@Entity
@Table(name = "students")
public class Student {

    @Id
    @Column(name = "student_id", length = 16, nullable = false)
    private String studentId;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "major", length = 128)
    private String major;

    @Column(name = "enrollment_status", length = 32)
    private String enrollmentStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    protected Student() {
        // JPA
    }

    public Student(String studentId, String name, String major, String enrollmentStatus, Instant now) {
        this.studentId = requireNonBlank(studentId, "studentId");
        this.name = requireNonBlank(name, "name");
        this.major = major;
        this.enrollmentStatus = enrollmentStatus;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.lastLoginAt = now;
    }

    public void updateProfile(String name, String major, String enrollmentStatus, Instant now) {
        this.name = requireNonBlank(name, "name");
        this.major = major;
        this.enrollmentStatus = enrollmentStatus;
        this.lastLoginAt = Objects.requireNonNull(now, "now");
    }

    public String getStudentId() {
        return studentId;
    }

    public String getName() {
        return name;
    }

    public String getMajor() {
        return major;
    }

    public String getEnrollmentStatus() {
        return enrollmentStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
