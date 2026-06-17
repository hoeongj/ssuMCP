package com.ssuai.domain.lms.controller;

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

        mockMvc.perform(get("/api/lms/exports/" + job.getId() + "/download")
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lms-materials-" + job.getId() + ".zip\""))
                .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
                .andExpect(content().string("mock-zip-content"));
    }
}
