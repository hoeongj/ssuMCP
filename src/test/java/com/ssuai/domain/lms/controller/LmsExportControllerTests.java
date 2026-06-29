package com.ssuai.domain.lms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.ssuai.domain.lms.export.LmsExportJob;
import com.ssuai.domain.lms.export.LmsExportJobRepository;
import com.ssuai.domain.lms.export.LmsExportProperties;
import com.ssuai.domain.lms.export.LmsExportStatus;

@ActiveProfiles("test")
@WebMvcTest(LmsExportController.class)
class LmsExportControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private LmsExportJobRepository jobRepository;

    @MockitoBean
    private LmsExportProperties properties;

    @Autowired
    private LmsExportController controller;

    @TempDir
    File tempDir;

    @Autowired
    LmsExportControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    private String sha256(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hashed);
    }

    private void simulateDownloadedTransition(LmsExportJob job) {
        when(jobRepository.markDownloaded(eq(job.getId()), any(Instant.class))).thenAnswer(invocation -> {
            Instant completedAt = invocation.getArgument(1, Instant.class);
            ReflectionTestUtils.setField(job, "status", LmsExportStatus.DOWNLOADED);
            ReflectionTestUtils.setField(job, "completedAt", completedAt);
            return 1;
        });
    }

    private void markDownloadedForTest(LmsExportJob job) {
        ReflectionTestUtils.setField(job, "status", LmsExportStatus.DOWNLOADED);
        ReflectionTestUtils.setField(job, "completedAt", Instant.now());
    }

    private ResultActions performStreaming(MockHttpServletRequestBuilder requestBuilder) throws Exception {
        MvcResult result = mockMvc.perform(requestBuilder)
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(result));
    }

    @Test
    void wrongTokenReturnsNotFound() throws Exception {
        String token = "correct-token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", "wrong-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredJobReturnsGone() throws Exception {
        String token = "token";
        String tokenHash = sha256(token);
        // Created in past, expired in past
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now().minusSeconds(1000), Instant.now().minusSeconds(500));

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getDownloadTtl()).thenReturn(Duration.ofMinutes(20));

        performStreaming(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("다운로드 링크가 만료되었습니다. (유효시간 20분)"));
    }

    @Test
    void buildingJobReturnsAccepted() throws Exception {
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        performStreaming(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("BUILDING"));
    }

    @Test
    void firstBinaryDownloadStreamsFileAndMarksJobDownloaded() throws Exception {
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        File zipFile = new File(tempDir, "test.zip");
        Files.writeString(zipFile.toPath(), "mock-zip-content");

        job.markReady(zipFile.getAbsolutePath(), 1, 15L, Instant.now());

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getTempDir()).thenReturn(tempDir.getAbsolutePath());
        simulateDownloadedTransition(job);

        MvcResult result = mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lms-materials-" + job.getId() + ".zip\""))
                .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
                .andExpect(content().string("mock-zip-content"));

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.DOWNLOADED);
        assertThat(job.getCompletedAt()).isNotNull();
        verify(jobRepository).markDownloaded(eq(job.getId()), any(Instant.class));
    }

    @Test
    void downloadedJobReturnsGone() throws Exception {
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        File zipFile = new File(tempDir, "downloaded.zip");
        Files.writeString(zipFile.toPath(), "mock-zip-content");
        job.markReady(zipFile.getAbsolutePath(), 1, 16L, Instant.now());
        markDownloadedForTest(job);

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        performStreaming(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value("DOWNLOADED"))
                .andExpect(jsonPath("$.message").value("이미 다운로드된 1회용 링크입니다. 다시 내보내기 해주세요."));

        verify(jobRepository, never()).markDownloaded(eq(job.getId()), any(Instant.class));
    }

    @Test
    void readyDownloadCarriesNoLeakHeaders() throws Exception {
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        File zipFile = new File(tempDir, "headers.zip");
        Files.writeString(zipFile.toPath(), "mock-zip-content");
        job.markReady(zipFile.getAbsolutePath(), 1, 15L, Instant.now());

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getTempDir()).thenReturn(tempDir.getAbsolutePath());

        MvcResult result = mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"));
    }

    @Test
    void filePathOutsideExportBaseIsRefused() throws Exception {
        // A job whose READY filePath resolves OUTSIDE the configured export base dir (e.g. a crafted
        // "../" escape) must be refused with 404 and never streamed — even though the file exists.
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        // Export base = a subdir of tempDir; the served file sits OUTSIDE it (a sibling under tempDir).
        File exportBase = new File(tempDir, "export-base");
        exportBase.mkdirs();
        File outsideFile = new File(tempDir, "outside-secret.zip");
        Files.writeString(outsideFile.toPath(), "secret-content");
        job.markReady(outsideFile.getAbsolutePath(), 1, 14L, Instant.now());

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getTempDir()).thenReturn(exportBase.getAbsolutePath());

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void filePathInsideExportBaseIsServed() throws Exception {
        // The legitimate counterpart: a file genuinely inside the export base streams fine.
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        File exportBase = new File(tempDir, "export-base-ok");
        exportBase.mkdirs();
        File insideFile = new File(exportBase, "inside.zip");
        Files.writeString(insideFile.toPath(), "legit-content");
        job.markReady(insideFile.getAbsolutePath(), 1, 13L, Instant.now());

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getTempDir()).thenReturn(exportBase.getAbsolutePath());

        MvcResult result = mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("legit-content"));
    }

    @Test
    void browserAcceptServesHtmlPageRegardlessOfState() throws Exception {
        // A still-building job: a browser (Accept: text/html) gets the polling page,
        // not the BUILDING JSON — the page itself polls until READY.
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        performStreaming(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("LMS 강의자료 다운로드")))
                .andExpect(content().string(containsString("format=json")));
    }

    @Test
    void formatJsonOnReadyReturnsStatusWithoutConsumingStream() throws Exception {
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        File zipFile = new File(tempDir, "ready.zip");
        Files.writeString(zipFile.toPath(), "zip-bytes");
        job.markReady(zipFile.getAbsolutePath(), 1, 9L, Instant.now());

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getTempDir()).thenReturn(tempDir.getAbsolutePath());

        performStreaming(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token)
                        .param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.READY);
        verify(jobRepository, never()).markDownloaded(eq(job.getId()), any(Instant.class));

        simulateDownloadedTransition(job);

        MvcResult result = mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("zip-bytes"));

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.DOWNLOADED);
        verify(jobRepository).markDownloaded(eq(job.getId()), any(Instant.class));
    }

    @Test
    void dlParamForcesBinaryStreamEvenWithHtmlAccept() throws Exception {
        // The page's download trigger hits ?dl=1 with the browser's text/html Accept still
        // attached; dl must win so the file streams instead of the page being re-served.
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        File zipFile = new File(tempDir, "force.zip");
        Files.writeString(zipFile.toPath(), "mock-zip-content");
        job.markReady(zipFile.getAbsolutePath(), 1, 16L, Instant.now());

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getTempDir()).thenReturn(tempDir.getAbsolutePath());

        MvcResult result = mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token)
                        .param("dl", "1")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lms-materials-" + job.getId() + ".zip\""))
                .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
                .andExpect(content().string("mock-zip-content"));
    }

    @Test
    void failedStreamDoesNotMarkJobDownloaded() throws Exception {
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        File zipFile = new File(tempDir, "disconnect.zip");
        Files.writeString(zipFile.toPath(), "mock-zip-content");
        job.markReady(zipFile.getAbsolutePath(), 1, 16L, Instant.now());

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getTempDir()).thenReturn(tempDir.getAbsolutePath());

        ResponseEntity<?> response = controller.download(job.getId(), token, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(StreamingResponseBody.class);

        StreamingResponseBody body = (StreamingResponseBody) response.getBody();
        OutputStream failingOutputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client disconnected");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        assertThatThrownBy(() -> body.writeTo(failingOutputStream))
                .isInstanceOf(IOException.class)
                .hasMessage("client disconnected");
        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.READY);
        verify(jobRepository, never()).markDownloaded(eq(job.getId()), any(Instant.class));
    }
}
