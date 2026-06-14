package com.ssuai.domain.lms.video.connector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.video.dto.CourseWithLectures;
import com.ssuai.domain.lms.video.dto.LectureItem;
import com.ssuai.global.exception.ConnectorUnavailableException;

@Component
@ConditionalOnProperty(name = "ssuai.lms-video.connector", havingValue = "mock", matchIfMissing = true)
class MockLmsVideoConnector implements LmsVideoConnector {

    @Override
    public List<LmsTermItem> fetchTerms(String studentId, LmsCookies cookies) {
        return List.of(
                new LmsTermItem(1L, "2026 1학기", "2026-03-02T00:00:00Z", "2026-06-20T00:00:00Z", false),
                new LmsTermItem(2L, "2026 여름학기", "2026-06-23T00:00:00Z", "2026-08-15T00:00:00Z", true));
    }

    @Override
    public List<CourseWithLectures> fetchLectureList(String studentId, LmsCookies cookies, Long termId) {
        return List.of(new CourseWithLectures("mock-course-1", "컴퓨터네트워크",
                List.of(new LectureItem("mock-content-1", "11주차: The World Wide Web", "11주차", 3355))));
    }

    @Override
    public Path downloadVideoToFile(LmsCookies cookies, String mp4Url, int timeoutSeconds) {
        try {
            Path tmp = Files.createTempFile("ssuai-mock-video-", ".mp4");
            Files.write(tmp, new byte[0]);
            return tmp;
        } catch (IOException exception) {
            throw unavailable("mock video download failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public Optional<String> fetchCaptionXml(LmsCookies cookies, String webFilesUrl, String storyGuid) {
        return Optional.of("<?xml version=\"1.0\"?><caption_list><caption>"
                + "<starttime>0.000</starttime><endtime>5.000</endtime>"
                + "<text>샘플 자막 텍스트입니다.</text></caption></caption_list>");
    }

    private static ConnectorUnavailableException unavailable(String message, Throwable cause) {
        return new ConnectorUnavailableException(new IllegalStateException(message, cause));
    }
}
