package com.ssuai.domain.lms.dto;

public record LmsExportSelectionItem(
    String contentId,
    long courseId,
    String courseName,
    String fileName
) {}
