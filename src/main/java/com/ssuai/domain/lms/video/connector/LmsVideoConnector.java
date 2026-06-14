package com.ssuai.domain.lms.video.connector;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.video.dto.CourseWithLectures;

public interface LmsVideoConnector {

    /**
     * Returns all enrollment terms available for the student.
     * Use the returned term IDs in {@link #fetchLectureList(String, LmsCookies, Long)}.
     */
    List<LmsTermItem> fetchTerms(String studentId, LmsCookies cookies);

    /**
     * Returns courses with their video lectures for the given student and term.
     * If termId is null the connector auto-selects the Canvas default term.
     * Each LectureItem has contentId, title, week (may be null), durationSeconds.
     */
    List<CourseWithLectures> fetchLectureList(String studentId, LmsCookies cookies, Long termId);

    /**
     * Downloads the video to a temp file. Caller must delete the returned file.
     */
    Path downloadVideoToFile(LmsCookies cookies, String mp4Url, int timeoutSeconds);

    /**
     * Returns empty when a professor has not uploaded captions for this content.
     */
    Optional<String> fetchCaptionXml(LmsCookies cookies, String webFilesUrl, String storyGuid);
}
