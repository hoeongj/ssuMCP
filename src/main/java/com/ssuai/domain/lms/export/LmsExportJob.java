package com.ssuai.domain.lms.export;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "lms_export_jobs")
public class LmsExportJob {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "status", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    private LmsExportStatus status;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "file_path", length = 512)
    private String filePath;

    @Column(name = "file_count")
    private Integer fileCount;

    @Column(name = "total_bytes")
    private Long totalBytes;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by", length = 128)
    private String claimedBy;

    protected LmsExportJob() {
        // JPA
    }

    private LmsExportJob(String id, String studentId, String tokenHash, LmsExportStatus status,
                         String payload, Instant createdAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.studentId = requireNonBlank(studentId, "studentId");
        this.tokenHash = requireNonBlank(tokenHash, "tokenHash");
        this.status = Objects.requireNonNull(status, "status");
        this.payload = requireNonBlank(payload, "payload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public static LmsExportJob createQueued(String studentId, String tokenHash, String payload,
                                           Instant createdAt, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        return new LmsExportJob(id, studentId, tokenHash, LmsExportStatus.QUEUED, payload, createdAt, expiresAt);
    }

    public void markBuilding() {
        if (status != LmsExportStatus.QUEUED) {
            throw new IllegalStateException("Only QUEUED jobs can transition to BUILDING. Current status: " + status);
        }
        this.status = LmsExportStatus.BUILDING;
    }

    public void claim(String owner, Instant now) {
        if (status != LmsExportStatus.QUEUED && status != LmsExportStatus.BUILDING) {
            throw new IllegalStateException("Only QUEUED or BUILDING jobs can be claimed. Current status: " + status);
        }
        this.status = LmsExportStatus.BUILDING;
        this.claimedBy = requireNonBlank(owner, "owner");
        this.claimedAt = Objects.requireNonNull(now, "now");
    }

    public void markReady(String filePath, int fileCount, long totalBytes, Instant completedAt) {
        if (status != LmsExportStatus.BUILDING) {
            throw new IllegalStateException("Only BUILDING jobs can transition to READY. Current status: " + status);
        }
        this.filePath = requireNonBlank(filePath, "filePath");
        this.fileCount = fileCount;
        this.totalBytes = totalBytes;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.status = LmsExportStatus.READY;
        clearClaim();
    }

    public void markFailed(String reason, Instant completedAt) {
        if (status != LmsExportStatus.QUEUED && status != LmsExportStatus.BUILDING) {
            throw new IllegalStateException("Only QUEUED or BUILDING jobs can transition to FAILED. Current status: " + status);
        }
        this.failureReason = reason;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.status = LmsExportStatus.FAILED;
        clearClaim();
    }

    public void markExpired(Instant expiredAt) {
        this.status = LmsExportStatus.EXPIRED;
        this.completedAt = Objects.requireNonNull(expiredAt, "expiredAt");
        clearClaim();
    }

    public String getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LmsExportStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public Long getTotalBytes() {
        return totalBytes;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    private void clearClaim() {
        this.claimedAt = null;
        this.claimedBy = null;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
