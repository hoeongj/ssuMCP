package com.ssuai.domain.lms.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.lms.connector.LmsMaterialsConnector;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsExportSelectionItem;
import com.ssuai.domain.lms.dto.SelectionPayload;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorRateLimitedException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LmsExportBuildWorkerTests {

    private LmsExportJobRepository repository;
    private LmsMaterialsConnector connector;
    private LmsSessionStore sessionStore;
    private LmsExportProperties properties;
    private ObjectMapper objectMapper;
    private LmsExportJobClaimer claimer;
    private SimpleMeterRegistry meterRegistry;
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
        meterRegistry = new SimpleMeterRegistry();

        worker = new LmsExportBuildWorker(
                repository,
                connector,
                sessionStore,
                properties,
                objectMapper,
                claimer,
                null,
                new LmsExportMetrics(meterRegistry)
        );

        when(sessionStore.cookies(STUDENT_ID)).thenReturn(Optional.of(COOKIES));
        when(repository.findAllByExpiresAtBefore(any())).thenReturn(List.of());
        when(repository.findAllById(any())).thenReturn(List.of());
        when(claimer.saveJob(any())).thenReturn(true);
    }

    @Test
    void buildsZipWithCorrectFolderStructureAndDeduplication() throws Exception {
        // Given
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("c1", 1L, "Math Course", "a.pdf"),
                new LmsExportSelectionItem("c2", 1L, "Math Course", "a.pdf"), // Duplicate name
                new LmsExportSelectionItem("c3", 1L, "Math Course", "b.pdf")  // One with no download link
        ), 0L);
        String payloadJson = objectMapper.writeValueAsString(payload);
        LmsExportJob job = LmsExportJob.createQueued(STUDENT_ID, "hash", payloadJson, Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(repository.findAllById(any())).thenReturn(List.of(job));
        when(connector.resolveDownload(COOKIES, "c1")).thenReturn(Optional.of(new ContentDownloadInfo("c1", "a", "https://url/1")));
        when(connector.resolveDownload(COOKIES, "c2")).thenReturn(Optional.of(new ContentDownloadInfo("c2", "a", "https://url/2")));
        when(connector.resolveDownload(COOKIES, "c3")).thenReturn(Optional.empty()); // No download URI

        // Emulate download writing dummy data
        when(connector.resolveDownload(COOKIES, "c1")).thenReturn(Optional.of(new ContentDownloadInfo("c1", "a", "https://url/1")));
        when(connector.resolveDownload(COOKIES, "c2")).thenReturn(Optional.of(new ContentDownloadInfo("c2", "a", "https://url/2")));
        when(connector.resolveDownload(COOKIES, "c3")).thenReturn(Optional.empty()); // Missing download URL

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
                "Math Course/a(2).pdf",
                "_ssuAI_export_report.txt"
        );
    }

    @Test
    void revokedMcpOwnerIsRejectedBeforeCookieOrDownloadAccess() {
        McpAuthService authService = mock(McpAuthService.class);
        LmsExportBuildWorker securedWorker = new LmsExportBuildWorker(
                repository, connector, sessionStore, properties, objectMapper, claimer, authService);
        LmsExportJob job = LmsExportJob.createQueuedForMcp(
                "owner-session", 42L, "credential-generation", "0".repeat(64), "{}",
                Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(authService.ownsProviderCredential(
                "owner-session", McpProviderType.LMS, "credential-generation"))
                .thenReturn(false);

        securedWorker.poll();

        verify(sessionStore, never()).cookies(any());
        verify(connector, never()).resolveDownload(any(), any());
        verify(connector, never()).download(any(), any(), any());
    }

    @Test
    void exceedsMaxBytesPerExportAbortsToFailed() throws Exception {
        // Given
        properties.setMaxBytesPerExport(10L); // Set tiny limit
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("c1", 1L, "Math", "a.pdf")
        ), 0L);
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

        // The partial ZIP must be cleaned up on failure: FAILED jobs never get a filePath set,
        // so sweepExpiredJobs cannot reclaim it — the worker must delete it inline.
        File partialZip = new File(tempDir, job.getId() + ".zip");
        assertThat(partialZip.exists()).isFalse();
    }

    @Test
    void exceedsMaxBytesPerFileAbortsMidStreamAndCleansUp() throws Exception {
        // A single oversized remote file must be aborted MID-STREAM by the bounded stream,
        // before the whole file lands on disk — the hard per-file cap.
        properties.setMaxBytesPerFile(10L);   // per-file cap
        properties.setMaxBytesPerExport(1_000_000L); // total is generous; per-file is the limiter
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("c1", 1L, "Math", "big.pdf")
        ), 0L);
        String payloadJson = objectMapper.writeValueAsString(payload);
        LmsExportJob job = LmsExportJob.createQueued(STUDENT_ID, "hash", payloadJson, Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "c1")).thenReturn(Optional.of(new ContentDownloadInfo("c1", "a", "https://url/1")));

        org.mockito.Mockito.doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(2);
            // 50 bytes written one-by-one; the bounded stream must throw after 10.
            for (int i = 0; i < 50; i++) {
                out.write('x');
            }
            return null;
        }).when(connector).download(eq(COOKIES), eq("https://url/1"), any(OutputStream.class));

        worker.poll();

        verify(claimer).saveJob(job);
        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        // failureReason is the generic, user-safe limit message (NOT raw exception/stack text).
        assertThat(job.getFailureReason()).isEqualTo("내보내기 한도가 초과되었습니다.");

        File partialZip = new File(tempDir, job.getId() + ".zip");
        assertThat(partialZip.exists()).isFalse();
    }

    @Test
    void unexpectedFailureStoresGenericMessageNotRawException() throws Exception {
        // An unexpected exception (raw message would leak internals) must NOT reach failureReason.
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("c1", 1L, "Math", "a.pdf")
        ), 0L);
        String payloadJson = objectMapper.writeValueAsString(payload);
        LmsExportJob job = LmsExportJob.createQueued(STUDENT_ID, "hash", payloadJson, Instant.now(), Instant.now().plusSeconds(600));
        job.markBuilding();

        String leakyDetail = "secret upstream url https://lms.internal/secret?token=abc123";
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "c1")).thenReturn(Optional.of(new ContentDownloadInfo("c1", "a", "https://url/1")));
        org.mockito.Mockito.doThrow(new RuntimeException(leakyDetail))
                .when(connector).download(eq(COOKIES), eq("https://url/1"), any(OutputStream.class));

        worker.poll();

        verify(claimer).saveJob(job);
        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).doesNotContain(leakyDetail);
        assertThat(job.getFailureReason()).isEqualTo("내보내기 생성 도중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

        File partialZip = new File(tempDir, job.getId() + ".zip");
        assertThat(partialZip.exists()).isFalse();
    }

    @Test
    void oneProtocolFailureProducesPartialZipWithSafeReport() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("good", 1L, "Network", "week-1.pdf"),
                new LmsExportSelectionItem("bad", 1L, "Network", "lab.zip")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "good")).thenReturn(Optional.of(
                new ContentDownloadInfo("good", "Week 1", "https://url/good")));
        when(connector.resolveDownload(COOKIES, "bad"))
                .thenThrow(new ConnectorParseException(new IllegalArgumentException("secret-url")));
        org.mockito.Mockito.doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(2);
            output.write("pdf-data".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(connector).download(eq(COOKIES), eq("https://url/good"), any(OutputStream.class));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.READY);
        assertThat(job.getFileCount()).isEqualTo(1);
        Map<String, String> entries = readTextEntries(Path.of(job.getFilePath()));
        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "Network/week-1.pdf", "_ssuAI_export_report.txt");
        assertThat(entries.get("_ssuAI_export_report.txt"))
                .contains("Network", "lab.zip", "다운로드 정보 응답 오류")
                .doesNotContain("secret-url", "contentId", "https://");
        assertThat(meterRegistry.get("lms.export.jobs")
                .tags("outcome", "partial", "reason", "items_skipped")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("lms.export.files")
                .tags("outcome", "included", "reason", "none")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("lms.export.files")
                .tags("outcome", "skipped", "reason", "metadata_unavailable")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void downloadParseFailureProducesPartialZipWithSafeReport() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("good", 1L, "Network", "week-1.pdf"),
                new LmsExportSelectionItem("bad", 1L, "Network", "lab.zip")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "good")).thenReturn(Optional.of(
                new ContentDownloadInfo("good", "Week 1", "https://url/good")));
        when(connector.resolveDownload(COOKIES, "bad")).thenReturn(Optional.of(
                new ContentDownloadInfo("bad", "Lab", "https://url/bad")));
        org.mockito.Mockito.doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(2);
            output.write("pdf-data".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(connector).download(eq(COOKIES), eq("https://url/good"), any(OutputStream.class));
        org.mockito.Mockito.doThrow(
                        new ConnectorParseException(new IllegalArgumentException("secret-url")))
                .when(connector)
                .download(eq(COOKIES), eq("https://url/bad"), any(OutputStream.class));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.READY);
        assertThat(job.getFileCount()).isEqualTo(1);
        Map<String, String> entries = readTextEntries(Path.of(job.getFilePath()));
        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "Network/week-1.pdf", "_ssuAI_export_report.txt");
        assertThat(entries.get("_ssuAI_export_report.txt"))
                .contains("Network", "lab.zip", "파일 다운로드 응답 오류")
                .doesNotContain("secret-url", "contentId", "https://");
    }

    @Test
    void missingMetadataAndDownloadItemsProducePartialZip() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("missing-metadata", 1L, "Network", "old.zip"),
                new LmsExportSelectionItem("missing-download", 1L, "Network", "gone.zip"),
                new LmsExportSelectionItem("good", 1L, "Network", "week-1.pdf")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "missing-metadata"))
                .thenThrow(new LmsApiException("not found", 404));
        when(connector.resolveDownload(COOKIES, "missing-download")).thenReturn(Optional.of(
                new ContentDownloadInfo("missing-download", "Gone", "https://url/gone")));
        when(connector.resolveDownload(COOKIES, "good")).thenReturn(Optional.of(
                new ContentDownloadInfo("good", "Week 1", "https://url/good")));
        org.mockito.Mockito.doThrow(new LmsApiException("gone", 410))
                .when(connector)
                .download(eq(COOKIES), eq("https://url/gone"), any(OutputStream.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(2);
            output.write("pdf-data".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(connector).download(eq(COOKIES), eq("https://url/good"), any(OutputStream.class));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.READY);
        assertThat(job.getFileCount()).isEqualTo(1);
        String report = readTextEntries(Path.of(job.getFilePath()))
                .get("_ssuAI_export_report.txt");
        assertThat(report)
                .contains("old.zip", "다운로드 정보 응답 오류")
                .contains("gone.zip", "파일 다운로드 응답 오류")
                .doesNotContain("not found", "contentId", "https://");
    }

    @Test
    void nonMissingClientErrorFailsWholeJobAndStopsFollowingItems() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("bad-request", 1L, "Network", "lab.zip"),
                new LmsExportSelectionItem("later", 1L, "Network", "later.pdf")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "bad-request"))
                .thenThrow(new LmsApiException("bad request", 400));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).contains("LMS 자료 응답을 처리하지 못했습니다");
        verify(connector, never()).resolveDownload(COOKIES, "later");
    }

    @Test
    void exhaustedServerErrorAtDownloadFailsWholeJobAndStopsFollowingItems() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("unavailable", 1L, "Network", "lab.zip"),
                new LmsExportSelectionItem("later", 1L, "Network", "later.pdf")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "unavailable")).thenReturn(Optional.of(
                new ContentDownloadInfo("unavailable", "Lab", "https://url/unavailable")));
        org.mockito.Mockito.doThrow(new LmsApiException("service unavailable", 503))
                .when(connector)
                .download(eq(COOKIES), eq("https://url/unavailable"), any(OutputStream.class));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).contains("LMS 자료 응답을 처리하지 못했습니다");
        verify(connector, times(2)).download(
                eq(COOKIES), eq("https://url/unavailable"), any(OutputStream.class));
        verify(connector, never()).resolveDownload(COOKIES, "later");
    }

    @Test
    void allProtocolFailuresFailInsteadOfPublishingEmptyArchive() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("bad", 1L, "Network", "lab.zip")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "bad"))
                .thenThrow(new ConnectorParseException());

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).isEqualTo(
                "선택한 자료를 LMS에서 내려받지 못했습니다. 잠시 후 다시 시도해 주세요.");
        assertThat(new File(tempDir, job.getId() + ".zip")).doesNotExist();
    }

    @Test
    void transientDownloadFailureRetriesIntoFreshTempFile() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("retry", 1L, "Network", "lab.zip")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "retry")).thenReturn(Optional.of(
                new ContentDownloadInfo("retry", "Lab", "https://url/retry")));
        AtomicInteger attempts = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(2);
            if (attempts.getAndIncrement() == 0) {
                output.write("partial".getBytes(StandardCharsets.UTF_8));
                throw new ConnectorUnavailableException();
            }
            output.write("complete".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(connector).download(eq(COOKIES), eq("https://url/retry"), any(OutputStream.class));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.READY);
        assertThat(readTextEntries(Path.of(job.getFilePath())).get("Network/lab.zip"))
                .isEqualTo("complete");
        verify(connector, times(2)).download(
                eq(COOKIES), eq("https://url/retry"), any(OutputStream.class));
    }

    @Test
    void authenticationLossFailsWholeJobAndStopsFollowingItems() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("expired", 1L, "Network", "lab.zip"),
                new LmsExportSelectionItem("later", 1L, "Network", "later.pdf")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "expired"))
                .thenThrow(new LmsSessionExpiredException());

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).contains("LMS 세션이 만료");
        verify(connector, never()).resolveDownload(COOKIES, "later");
    }

    @Test
    void exhaustedUpstreamFailureStopsJobWithoutFanningOutAcrossItems() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("unavailable", 1L, "Network", "lab.zip"),
                new LmsExportSelectionItem("later", 1L, "Network", "later.pdf")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "unavailable"))
                .thenThrow(new ConnectorUnavailableException());

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).contains("LMS 자료 서버 연결이 불안정");
        verify(connector, times(1)).resolveDownload(COOKIES, "unavailable");
        verify(connector, never()).resolveDownload(COOKIES, "later");
    }

    @Test
    void rateLimitAtMetadataStopsFollowingItems() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("limited", 1L, "Network", "lab.zip"),
                new LmsExportSelectionItem("later", 1L, "Network", "later.pdf")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "limited"))
                .thenThrow(new ConnectorRateLimitedException(Duration.ofSeconds(30), null));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).contains("일시적으로 제한");
        verify(connector, never()).resolveDownload(COOKIES, "later");
    }

    @Test
    void rateLimitAtDownloadStopsFollowingItems() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("limited", 1L, "Network", "lab.zip"),
                new LmsExportSelectionItem("later", 1L, "Network", "later.pdf")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(connector.resolveDownload(COOKIES, "limited")).thenReturn(Optional.of(
                new ContentDownloadInfo("limited", "Lab", "https://url/limited")));
        org.mockito.Mockito.doThrow(
                        new ConnectorRateLimitedException(Duration.ofSeconds(30), null))
                .when(connector)
                .download(eq(COOKIES), eq("https://url/limited"), any(OutputStream.class));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.FAILED);
        assertThat(job.getFailureReason()).contains("일시적으로 제한");
        verify(connector, never()).resolveDownload(COOKIES, "later");
    }

    @Test
    void staleCompletionDoesNotEmitCommittedOutcomeMetrics() throws Exception {
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("good", 1L, "Network", "week-1.pdf")
        ), 0L);
        LmsExportJob job = queuedBuildingJob(payload);
        when(claimer.claimNextJob()).thenReturn(Optional.of(job));
        when(claimer.saveJob(job)).thenReturn(false);
        when(connector.resolveDownload(COOKIES, "good")).thenReturn(Optional.of(
                new ContentDownloadInfo("good", "Week 1", "https://url/good")));

        worker.poll();

        assertThat(meterRegistry.find("lms.export.jobs").counter()).isNull();
        assertThat(meterRegistry.find("lms.export.files").counter()).isNull();
    }

    @Test
    void sweepDeletesManagedZipWithoutJobRow() throws Exception {
        String jobId = UUID.randomUUID().toString();
        Path orphanZip = writeManagedZip(jobId);

        when(claimer.claimNextJob()).thenReturn(Optional.empty());
        when(repository.findAllById(any())).thenReturn(List.of());

        worker.poll();

        assertThat(orphanZip).doesNotExist();
    }

    @Test
    void sweepKeepsZipForActivelyClaimedBuildingJob() throws Exception {
        LmsExportJob job = LmsExportJob.createQueued(
                STUDENT_ID,
                "hash",
                "{\"selections\":[],\"totalBytes\":0}",
                Instant.now(),
                Instant.now().plusSeconds(600));
        job.claim("pod-b", Instant.now());
        Path activeZip = writeManagedZip(job.getId());

        when(claimer.claimNextJob()).thenReturn(Optional.empty());
        when(repository.findAllById(any())).thenReturn(List.of(job));

        worker.poll();

        assertThat(activeZip).exists();
    }

    @Test
    void sweepSkipsExpiredActiveBuildingJob() throws Exception {
        LmsExportJob job = LmsExportJob.createQueued(
                STUDENT_ID,
                "hash",
                "{\"selections\":[],\"totalBytes\":0}",
                Instant.now().minusSeconds(1200),
                Instant.now().minusSeconds(60));
        job.claim("pod-b", Instant.now());
        Path activeZip = writeManagedZip(job.getId());

        when(claimer.claimNextJob()).thenReturn(Optional.empty());
        when(repository.findAllByExpiresAtBefore(any())).thenReturn(List.of(job));
        when(repository.findAllById(any())).thenReturn(List.of(job));

        worker.poll();

        assertThat(activeZip).exists();
        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.BUILDING);
        verify(repository, never()).save(job);
    }

    @Test
    void sweepRetriesDeletionForAlreadyExpiredJobRows() throws Exception {
        LmsExportJob job = LmsExportJob.createQueued(
                STUDENT_ID,
                "hash",
                "{\"selections\":[],\"totalBytes\":0}",
                Instant.now().minusSeconds(1200),
                Instant.now().minusSeconds(60));
        Path expiredZip = writeManagedZip(job.getId());
        job.markBuilding();
        job.markReady(expiredZip.toString(), 1, 3L, Instant.now().minusSeconds(300));
        job.markExpired(Instant.now().minusSeconds(60));

        when(claimer.claimNextJob()).thenReturn(Optional.empty());
        when(repository.findAllByExpiresAtBefore(any())).thenReturn(List.of(job));
        when(repository.findAllById(any())).thenReturn(List.of(job));

        worker.poll();

        assertThat(expiredZip).doesNotExist();
        assertThat(job.getStatus()).isEqualTo(LmsExportStatus.EXPIRED);
        verify(repository, never()).save(job);
    }

    private Path writeManagedZip(String jobId) throws IOException {
        Path path = tempDir.toPath().resolve(jobId + ".zip");
        Files.writeString(path, "zip", StandardCharsets.UTF_8);
        return path;
    }

    private LmsExportJob queuedBuildingJob(SelectionPayload payload) throws Exception {
        LmsExportJob job = LmsExportJob.createQueued(
                STUDENT_ID,
                "hash",
                objectMapper.writeValueAsString(payload),
                Instant.now(),
                Instant.now().plusSeconds(600));
        job.markBuilding();
        when(repository.findAllById(any())).thenReturn(List.of(job));
        return job;
    }

    private Map<String, String> readTextEntries(Path zip) throws IOException {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
                input.closeEntry();
            }
        }
        return entries;
    }
}
