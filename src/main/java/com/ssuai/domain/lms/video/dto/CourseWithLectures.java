package com.ssuai.domain.lms.video.dto;

import java.util.List;

public record CourseWithLectures(
        String courseId,
        String courseName,
        List<LectureItem> lectures) {
}
