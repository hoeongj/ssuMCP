package com.ssuai.domain.lms.dto;

public record LmsMaterial(
    String contentId,
    long courseId,
    String courseName,
    String fileName,
    String extension,
    Long sizeBytes,
    String weekTitle,
    String title,
    String contentType // we need contentType for filtering if we want belt-and-suspenders
) {
    // Overloaded constructor to support creating without contentType or mapping cleanly
    public LmsMaterial(
        String contentId, long courseId, String courseName,
        String fileName, String extension, Long sizeBytes,
        String weekTitle, String title
    ) {
        this(contentId, courseId, courseName, fileName, extension, sizeBytes, weekTitle, title, null);
    }
}
