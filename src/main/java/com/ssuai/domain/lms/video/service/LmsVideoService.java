package com.ssuai.domain.lms.video.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.video.connector.LmsVideoConnector;
import com.ssuai.domain.lms.video.dto.ContentInfo;
import com.ssuai.domain.lms.video.dto.CourseWithLectures;
import com.ssuai.domain.lms.video.dto.LectureTranscriptResponse;
import com.ssuai.domain.lms.video.properties.LmsVideoProperties;
import com.ssuai.domain.lms.video.util.CaptionXmlParser;
import com.ssuai.domain.lms.video.util.CommonsContentClient;
import com.ssuai.domain.lms.video.util.FfmpegAudioExtractor;
import com.ssuai.domain.lms.video.util.GroqSttClient;
import com.ssuai.global.exception.LmsSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class LmsVideoService {

    private static final Logger log = LoggerFactory.getLogger(LmsVideoService.class);

    private final LmsVideoConnector connector;
    private final CommonsContentClient contentClient;
    private final CaptionXmlParser captionParser;
    private final FfmpegAudioExtractor audioExtractor;
    private final GroqSttClient sttClient;
    private final LmsSessionStore sessionStore;
    private final LmsVideoProperties properties;

    public LmsVideoService(
            LmsVideoConnector connector,
            CommonsContentClient contentClient,
            CaptionXmlParser captionParser,
            FfmpegAudioExtractor audioExtractor,
            GroqSttClient sttClient,
            LmsSessionStore sessionStore,
            LmsVideoProperties properties) {
        this.connector = connector;
        this.contentClient = contentClient;
        this.captionParser = captionParser;
        this.audioExtractor = audioExtractor;
        this.sttClient = sttClient;
        this.sessionStore = sessionStore;
        this.properties = properties;
    }

    public List<LmsTermItem> getTerms(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        LmsCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(LmsSessionExpiredException::new);
        return connector.fetchTerms(studentId, cookies);
    }

    public List<CourseWithLectures> getLectureList(String studentId, Long termId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        LmsCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(LmsSessionExpiredException::new);
        return connector.fetchLectureList(studentId, cookies, termId);
    }

    public LectureTranscriptResponse getTranscript(String studentId, String contentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        LmsCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(LmsSessionExpiredException::new);

        ContentInfo content = contentClient.fetchContentInfo(contentId);
        Optional<String> captionXml = connector.fetchCaptionXml(cookies, content.webFilesUrl(), content.storyGuid());
        if (captionXml.isPresent()) {
            String text = captionParser.parse(captionXml.get());
            if (!text.isBlank()) {
                log.info("lms-video transcript: content={} source=CAPTION length={}", contentId, text.length());
                return new LectureTranscriptResponse(contentId, content.title(), text, "CAPTION");
            }
            log.info("lms-video transcript: content={} caption XML present but empty text, falling back to STT",
                    contentId);
        } else {
            log.info("lms-video transcript: content={} no captions, using STT fallback", contentId);
        }

        if (!sttClient.isUsable()) {
            return new LectureTranscriptResponse(contentId, content.title(),
                    "자막 없음. STT API 키가 설정되지 않았습니다.", "EMPTY");
        }

        Path videoFile = null;
        List<Path> audioChunks = List.of();
        try {
            videoFile = connector.downloadVideoToFile(
                    cookies, content.mp4Url(), properties.getDownloadTimeoutSeconds());
            audioChunks = audioExtractor.extractChunks(videoFile, content.durationSeconds());

            StringBuilder transcript = new StringBuilder();
            for (Path chunk : audioChunks) {
                String chunkText = sttClient.transcribe(chunk);
                if (!chunkText.isBlank()) {
                    transcript.append(chunkText).append(' ');
                }
            }
            String result = transcript.toString().trim();
            log.info("lms-video transcript: content={} source=STT chunks={} length={}",
                    contentId, audioChunks.size(), result.length());
            return new LectureTranscriptResponse(contentId, content.title(),
                    result.isEmpty() ? "STT 결과를 가져올 수 없었습니다." : result,
                    result.isEmpty() ? "EMPTY" : "STT");
        } finally {
            deleteQuietly(videoFile);
            for (Path chunk : audioChunks) {
                deleteQuietly(chunk);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
