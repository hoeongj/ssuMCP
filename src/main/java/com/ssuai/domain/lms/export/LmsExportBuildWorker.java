package com.ssuai.domain.lms.export;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.connector.LmsMaterialsConnector;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsExportSelectionItem;
import com.ssuai.domain.lms.dto.SelectionPayload;

@Component
public class LmsExportBuildWorker {

    private static final Logger log = LoggerFactory.getLogger(LmsExportBuildWorker.class);

    private final LmsExportJobRepository repository;
    private final LmsMaterialsConnector connector;
    private final LmsSessionStore sessionStore;
    private final LmsExportProperties properties;
    private final ObjectMapper objectMapper;
    private final LmsExportJobClaimer claimer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LmsExportBuildWorker(LmsExportJobRepository repository, LmsMaterialsConnector connector,
                                LmsSessionStore sessionStore, LmsExportProperties properties,
                                ObjectMapper objectMapper, LmsExportJobClaimer claimer) {
        this.repository = repository;
        this.connector = connector;
        this.sessionStore = sessionStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.claimer = claimer;
    }

    @Scheduled(fixedDelayString = "#{@lmsExportProperties.pollInterval.toMillis()}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            buildQueuedJobs();
            sweepExpiredJobs();
        } finally {
            running.set(false);
        }
    }

    private void buildQueuedJobs() {
        Optional<LmsExportJob> jobOpt = claimer.claimNextJob();
        if (jobOpt.isEmpty()) {
            return;
        }

        LmsExportJob job = jobOpt.get();
        log.info("Claimed LMS material export job: jobId={} studentId={}", job.getId(), LmsSessionStore.fingerprint(job.getStudentId()));

        // Hoisted so the catch block can clean up a partially written ZIP on failure.
        File zipFile = null;
        try {
            LmsCookies cookies = sessionStore.cookies(job.getStudentId()).orElse(null);
            if (cookies == null) {
                job.markFailed("LMS 세션이 만료되어 내보내기를 완료할 수 없습니다.", Instant.now());
                claimer.saveJob(job);
                return;
            }

            SelectionPayload payload;
            try {
                payload = objectMapper.readValue(job.getPayload(), SelectionPayload.class);
            } catch (Exception e) {
                job.markFailed("내보내기 대상 파일 목록을 파싱하는 데 실패했습니다.", Instant.now());
                claimer.saveJob(job);
                return;
            }

            File tempDir = new File(properties.getTempDir());
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            zipFile = new File(tempDir, job.getId() + ".zip");
            int actualFileCount = 0;
            long actualBytes = 0;
            Set<String> addedPaths = new HashSet<>();

            try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile))) {
                for (LmsExportSelectionItem selection : payload.selections()) {
                    Optional<ContentDownloadInfo> downloadOpt = connector.resolveDownload(cookies, selection.contentId());
                    if (downloadOpt.isEmpty()) {
                        log.warn("Excluding file due to missing download URI: contentId={} fileName={}", selection.contentId(), selection.fileName());
                        continue;
                    }

                    ContentDownloadInfo downloadInfo = downloadOpt.get();
                    String uniquePath = getUniqueZipPath(addedPaths, selection.courseName(), selection.fileName());
                    addedPaths.add(uniquePath.toLowerCase());

                    File singleTemp = File.createTempFile("lms-dl-", ".tmp");
                    try {
                        try (FileOutputStream fileOut = new FileOutputStream(singleTemp)) {
                            connector.download(cookies, downloadInfo.absoluteDownloadUrl(), fileOut);
                        }

                        // Check limits during build defensively
                        long currentFileBytes = singleTemp.length();
                        if (actualFileCount + 1 > properties.getMaxFilesPerExport() ||
                                actualBytes + currentFileBytes > properties.getMaxBytesPerExport()) {
                            log.error("Job limits blown mid-build: jobId={} files={} bytes={}", job.getId(), actualFileCount + 1, actualBytes + currentFileBytes);
                            throw new IllegalStateException("내보내기 한도가 초과되었습니다.");
                        }

                        ZipEntry entry = new ZipEntry(uniquePath);
                        zipOut.putNextEntry(entry);
                        try (FileInputStream fileIn = new FileInputStream(singleTemp)) {
                            fileIn.transferTo(zipOut);
                        }
                        zipOut.closeEntry();
                        actualFileCount++;
                        actualBytes += currentFileBytes;
                    } finally {
                        singleTemp.delete();
                    }
                }
            }

            job.markReady(zipFile.getAbsolutePath(), actualFileCount, actualBytes, Instant.now());
            claimer.saveJob(job);
            log.info("LMS material export job completed successfully: jobId={} files={} bytes={}", job.getId(), actualFileCount, actualBytes);

        } catch (Exception e) {
            log.error("LMS material export job failed: jobId={}", job.getId(), e);
            job.markFailed("내보내기 생성 도중 오류가 발생했습니다: " + e.getMessage(), Instant.now());
            claimer.saveJob(job);
            // A failed job never gets a filePath set, so sweepExpiredJobs cannot reclaim it.
            // Delete the half-written ZIP here to avoid leaking partial files on the export disk.
            deletePartialFile(zipFile, job.getId());
        }
    }

    private void deletePartialFile(File zipFile, String jobId) {
        if (zipFile == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(zipFile.toPath())) {
                log.info("Deleted partial ZIP after failed export: jobId={}", jobId);
            }
        } catch (IOException io) {
            log.warn("Failed to delete partial ZIP after failed export: jobId={} path={}", jobId, zipFile.getAbsolutePath(), io);
        }
    }

    private void sweepExpiredJobs() {
        Instant now = Instant.now();
        List<LmsExportJob> expired = repository.findAllByExpiresAtBefore(now);
        for (LmsExportJob job : expired) {
            if (job.getStatus() == LmsExportStatus.EXPIRED) {
                continue;
            }
            try {
                if (job.getFilePath() != null) {
                    File file = new File(job.getFilePath());
                    Files.deleteIfExists(file.toPath());
                }
            } catch (IOException e) {
                log.warn("Failed to delete expired ZIP file: path={} jobId={}", job.getFilePath(), job.getId(), e);
            }
            job.markExpired(now);
            repository.save(job);
            log.info("Expired LMS material export job: jobId={}", job.getId());
        }
    }

    private String getUniqueZipPath(Set<String> existingPaths, String courseName, String fileName) {
        String cleanCourse = courseName.replaceAll("[/\\\\\\x00-\\x1F\\x7F]", "_").trim();
        String cleanFile = fileName.replaceAll("\\.\\.", "")
                .replaceAll("[/\\\\\\x00-\\x1F\\x7F]", "_")
                .trim();
        if (cleanFile.isEmpty()) {
            cleanFile = "unnamed_file";
        }

        String baseName = cleanFile;
        String ext = "";
        int dot = cleanFile.lastIndexOf('.');
        if (dot != -1) {
            baseName = cleanFile.substring(0, dot);
            ext = cleanFile.substring(dot);
        }

        String path = cleanCourse + "/" + baseName + ext;
        int count = 1;
        while (existingPaths.contains(path.toLowerCase())) {
            count++;
            path = cleanCourse + "/" + baseName + "(" + count + ")" + ext;
        }
        return path;
    }
}

@Component
class LmsExportJobClaimer {
    private final LmsExportJobRepository repository;

    public LmsExportJobClaimer(LmsExportJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Optional<LmsExportJob> claimNextJob() {
        Optional<LmsExportJob> jobOpt = repository.findFirstByStatusOrderByCreatedAtAsc(LmsExportStatus.QUEUED);
        if (jobOpt.isPresent()) {
            LmsExportJob job = jobOpt.get();
            job.markBuilding();
            return Optional.of(repository.save(job));
        }
        return Optional.empty();
    }

    @Transactional
    public void saveJob(LmsExportJob job) {
        repository.save(job);
    }
}
