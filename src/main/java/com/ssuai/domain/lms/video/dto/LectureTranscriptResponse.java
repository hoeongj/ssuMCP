package com.ssuai.domain.lms.video.dto;

public record LectureTranscriptResponse(
        String contentId,
        String title,
        String transcript,
        String source) {
}
