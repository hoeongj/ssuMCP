package com.ssuai.domain.lms.video.connector;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.lms.video.dto.CourseWithLectures;

public interface LmsVideoConnector {

    /**
     * Returns courses with their video lectures for the given student.
     * Each LectureItem has contentId, title, week (may be null), durationSeconds.
     */
    List<CourseWithLectures> fetchLectureList(String studentId, LmsCookies cookies);

    /**
     * Downloads the video to a temp file. Caller must delete the returned file.
     */
    Path downloadVideoToFile(LmsCookies cookies, String mp4Url, int timeoutSeconds);

    /**
     * Returns empty when a professor has not uploaded captions for this content.
     */
    Optional<String> fetchCaptionXml(LmsCookies cookies, String webFilesUrl, String storyGuid);
}
