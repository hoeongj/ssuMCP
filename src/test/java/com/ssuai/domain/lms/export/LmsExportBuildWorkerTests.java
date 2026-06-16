package com.ssuai.domain.lms.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.connector.LmsMaterialsConnector;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsExportSelectionItem;
import com.ssuai.domain.lms.dto.SelectionPayload;

class LmsExportBuildWorkerTests {

    private LmsExportJobRepository repository;
    private LmsMaterialsConnector connector;
    private LmsSessionStore sessionStore;
    private LmsExportProperties properties;
    private ObjectMapper objectMapper;
    private LmsExportJobClaimer claimer;
    private LmsExportBuildWorker worker;

    private static final String STUDENT_ID = "20221528";
    private static final LmsCookies COOKIES = new LmsCookies("xn_api_token=xyz;");

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        repository = mock(LmsExportJobRepository.class);
        connector = mock(LmsMaterialsConnector.class);
        sessionStore = mock(LmsSessionStore.class);
        properties = new LmsExportProperties();
        properties.setTempDir(tempDir.getAbsolutePath());
        properties.setMaxFilesPerExport(5);
        properties.setMaxBytesPerExport(1000L);
        objectMapper = new ObjectMapper();
        claimer = mock(LmsExportJobClaimer.class);

        worker = new LmsExportBuildWorker(
                repository, connector, sessionStore, properties, objectMapper, claimer
        );

        when(sessionStore.cookies(STUDENT_ID)).thenReturn(Optional.of(COOKIES));
    }

    @Test
    void buildsZipWithCorrectFolderStructureAndDeduplication() throws Exception {
        // Given
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("c1", 1L, "Math Course", "a.pdf"),
                new LmsExportSelectionItem("c2", 1L, "Math Course", "a.pdf"), // Duplicate name
                new LmsExportSelectionItem("c3", 1L, "Math Course", "b.pdf")  // One with no download link
        ));
        String payloadJson = objectMapper.writeValueAsString(payload);
        LmsExportJob job = LmsExportJob.createQueued(STUDENT_ID, "hash", payloadJson, Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "c1")).thenReturn(Optional.of(new ContentDownloadInfo("c1", "a", "https://url/1")));
        when(connector.resolveDownload(COOKIES, "c2")).thenReturn(Optional.of(new ContentDownloadInfo("c2", "a", "https://url/2")));
        when(connector.resolveDownload(COOKIES, "c3")).thenReturn(Optional.empty()); // No download URI

        // Emulate download writing dummy data
        when(connector.resolveDownload(COOKIES, "c1")).thenReturn(Optional.of(new ContentDownloadInfo("c1", "a", "https://url/1")));
        when(connector.resolveDownload(COOKIES, "c2")).thenReturn(Optional.of(new ContentDownloadInfo("c2", "a", "https://url/2")));
        when(connector.resolveDownload(COOKIES, "c3")).thenReturn(Optional.empty()); // Missing download URL

        // Mock download calls
        ArgumentCaptor<OutputStream> outCaptor1 = ArgumentCaptor.forClass(OutputStream.class);
        ArgumentCaptor<OutputStream> outCaptor2 = ArgumentCaptor.forClass(OutputStream.class);

        // When
        worker.poll();

        // Then
        verify(claimer).saveJob(job);
        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.READY);
        assertThat(job.getFileCount()).isEqualTo(2); // c3 excluded because of missing download

        File zipFile = new File(job.getFilePath());
        assertThat(zipFile.exists()).isTrue();

        // Verify contents of the ZIP
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                zipIn.closeEntry();
            }
        }

        assertThat(entryNames).containsExactlyInAnyOrder(
                "Math Course/a.pdf",
                "Math Course/a(2).pdf"
        );
    }

    @Test
    void exceedsMaxBytesPerExportAbortsToFailed() throws Exception {
        // Given
        properties.setMaxBytesPerExport(10L); // Set tiny limit
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("c1", 1L, "Math", "a.pdf")
        ));
        String payloadJson = objectMapper.writeValueAsString(payload);
        LmsExportJob job = LmsExportJob.createQueued(STUDENT_ID, "hash", payloadJson, Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "c1")).thenReturn(Optional.of(new ContentDownloadInfo("c1", "a", "https://url/1")));
        
        // Emulate writing 20 bytes (exceeds 10 limit)
        org.mockito.Mockito.doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(2);
            out.write("12345678901234567890".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(connector).download(eq(COOKIES), eq("https://url/1"), any(OutputStream.class));

        // When
        worker.poll();

        // Then
        verify(claimer).saveJob(job);
        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).contains("내보내기 한도가 초과되었습니다");
    }
}
