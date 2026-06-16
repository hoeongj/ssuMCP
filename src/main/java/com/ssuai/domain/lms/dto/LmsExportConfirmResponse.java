package com.ssuai.domain.lms.dto;

public record LmsExportConfirmResponse(
    String jobId, int fileCount, long estimatedBytes,
    String expiresAt, String downloadUrl, String warning) {}
