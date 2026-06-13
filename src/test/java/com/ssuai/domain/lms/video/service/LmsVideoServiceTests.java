package com.ssuai.domain.lms.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.video.connector.LmsVideoConnector;
import com.ssuai.domain.lms.video.dto.ContentInfo;
import com.ssuai.domain.lms.video.dto.LectureTranscriptResponse;
import com.ssuai.domain.lms.video.properties.LmsVideoProperties;
import com.ssuai.domain.lms.video.util.CaptionXmlParser;
import com.ssuai.domain.lms.video.util.CommonsContentClient;
import com.ssuai.domain.lms.video.util.FfmpegAudioExtractor;
import com.ssuai.domain.lms.video.util.GroqSttClient;
import com.ssuai.global.exception.LmsSessionExpiredException;

class LmsVideoServiceTests {

    private static final String STUDENT_ID = "20240000";
    private static final String CONTENT_ID = "content-1";
    private static final LmsCookies COOKIES = new LmsCookies("xn_api_token=token");
    private static final ContentInfo CONTENT = new ContentInfo(
            CONTENT_ID,
            "강의",
            "story-guid",
            "main_(story-guid).mp4",
            "https://cdn.example/[MEDIA_FILE]",
            "https://commons.example/web_files",
            60);

    private Path videoFile;
    private Path audioFile;

    @AfterEach
    void tearDown() throws IOException {
        if (videoFile != null) {
            Files.deleteIfExists(videoFile);
        }
        if (audioFile != null) {
            Files.deleteIfExists(audioFile);
        }
    }

    @Test
    void returnsCaptionTranscriptFirst() {
        Fixture fixture = fixture();
        when(fixture.connector.fetchCaptionXml(COOKIES, CONTENT.webFilesUrl(), CONTENT.storyGuid()))
                .thenReturn(Optional.of("""
                        <caption_list><caption><text>자막 텍스트입니다.</text></caption></caption_list>
                        """));

        LectureTranscriptResponse response = fixture.service.getTranscript(STUDENT_ID, CONTENT_ID);

        assertThat(response.source()).isEqualTo("CAPTION");
        assertThat(response.transcript()).isEqualTo("자막 텍스트입니다.");
    }

    @Test
    void fallsBackToSttWhenCaptionIsMissing() throws IOException {
        Fixture fixture = fixture();
        videoFile = Files.createTempFile("video-", ".mp4");
        audioFile = Files.createTempFile("audio-", ".mp3");
        when(fixture.connector.fetchCaptionXml(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(fixture.sttClient.isUsable()).thenReturn(true);
        when(fixture.connector.downloadVideoToFile(any(), anyString(), anyInt()))
                .thenReturn(videoFile);
        when(fixture.audioExtractor.extractChunks(videoFile, CONTENT.durationSeconds()))
                .thenReturn(List.of(audioFile));
        when(fixture.sttClient.transcribe(audioFile)).thenReturn("STT 텍스트입니다");

        LectureTranscriptResponse response = fixture.service.getTranscript(STUDENT_ID, CONTENT_ID);

        assertThat(response.source()).isEqualTo("STT");
        assertThat(response.transcript()).isEqualTo("STT 텍스트입니다");
        assertThat(Files.exists(videoFile)).isFalse();
        assertThat(Files.exists(audioFile)).isFalse();
    }

    @Test
    void returnsEmptyWhenNoCaptionAndSttIsNotConfigured() {
        Fixture fixture = fixture();
        when(fixture.connector.fetchCaptionXml(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(fixture.sttClient.isUsable()).thenReturn(false);

        LectureTranscriptResponse response = fixture.service.getTranscript(STUDENT_ID, CONTENT_ID);

        assertThat(response.source()).isEqualTo("EMPTY");
        assertThat(response.transcript()).contains("STT API 키");
    }

    @Test
    void throwsWhenLmsSessionExpired() {
        Fixture fixture = fixture(false);

        assertThatThrownBy(() -> fixture.service.getTranscript(STUDENT_ID, CONTENT_ID))
                .isInstanceOf(LmsSessionExpiredException.class);
    }

    @Test
    void deletesTempFilesWhenSttFails() throws IOException {
        Fixture fixture = fixture();
        videoFile = Files.createTempFile("video-", ".mp4");
        audioFile = Files.createTempFile("audio-", ".mp3");
        when(fixture.connector.fetchCaptionXml(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(fixture.sttClient.isUsable()).thenReturn(true);
        when(fixture.connector.downloadVideoToFile(any(), anyString(), anyInt()))
                .thenReturn(videoFile);
        when(fixture.audioExtractor.extractChunks(videoFile, CONTENT.durationSeconds()))
                .thenReturn(List.of(audioFile));
        when(fixture.sttClient.transcribe(audioFile)).thenReturn("");

        LectureTranscriptResponse response = fixture.service.getTranscript(STUDENT_ID, CONTENT_ID);

        assertThat(response.source()).isEqualTo("EMPTY");
        assertThat(Files.exists(videoFile)).isFalse();
        assertThat(Files.exists(audioFile)).isFalse();
    }

    @Test
    void lectureListUsesStoredSessionCookies() {
        Fixture fixture = fixture();

        fixture.service.getLectureList(STUDENT_ID);

        verify(fixture.connector).fetchLectureList(STUDENT_ID, COOKIES);
    }

    private Fixture fixture() {
        return fixture(true);
    }

    private Fixture fixture(boolean hasSession) {
        LmsVideoConnector connector = mock(LmsVideoConnector.class);
        CommonsContentClient contentClient = mock(CommonsContentClient.class);
        FfmpegAudioExtractor audioExtractor = mock(FfmpegAudioExtractor.class);
        GroqSttClient sttClient = mock(GroqSttClient.class);
        LmsSessionStore sessionStore = mock(LmsSessionStore.class);
        LmsVideoProperties properties = new LmsVideoProperties();
        CaptionXmlParser captionParser = new CaptionXmlParser();

        when(sessionStore.cookies(STUDENT_ID)).thenReturn(hasSession ? Optional.of(COOKIES) : Optional.empty());
        when(contentClient.fetchContentInfo(CONTENT_ID)).thenReturn(CONTENT);
        when(connector.fetchLectureList(STUDENT_ID, COOKIES)).thenReturn(List.of());

        LmsVideoService service = new LmsVideoService(
                connector, contentClient, captionParser, audioExtractor,
                sttClient, sessionStore, properties);
        return new Fixture(service, connector, audioExtractor, sttClient);
    }

    private record Fixture(
            LmsVideoService service,
            LmsVideoConnector connector,
            FfmpegAudioExtractor audioExtractor,
            GroqSttClient sttClient) {
    }
}
