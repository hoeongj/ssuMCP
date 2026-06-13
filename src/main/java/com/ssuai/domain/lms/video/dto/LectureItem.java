package com.ssuai.domain.lms.video.dto;

public record LectureItem(
        String contentId,
        String title,
        String week,
        int durationSeconds) {
}
