package com.ssuai.domain.lms.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
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

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("BUILDING"));
    }

    @Test
    void readyJobStreamsFileContent() throws Exception {
        String token = "token";
        String tokenHash = sha256(token);
        LmsExportJob job = LmsExportJob.createQueued("student1", tokenHash, "[]", Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        File zipFile = new File(tempDir, "test.zip");
        Files.writeString(zipFile.toPath(), "mock-zip-content");

        job.markReady(zipFile.getAbsolutePath(), 1, 15L, Instant.now());

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(properties.getTempDir()).thenReturn(tempDir.getAbsolutePath());

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lms-materials-" + job.getId() + ".zip\""))
                .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
                .andExpect(content().string("mock-zip-content"));
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

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
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

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
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

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
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

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token)
                        .param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
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

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token)
                        .param("dl", "1")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lms-materials-" + job.getId() + ".zip\""))
                .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
                .andExpect(content().string("mock-zip-content"));
    }
}
