package com.ssuai.domain.lms.export;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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

                    // Per-file cap enforced ahead of the total to keep file-count headroom in the message.
                    if (actualFileCount + 1 > properties.getMaxFilesPerExport()) {
                        log.error("Export file count limit blown mid-build: jobId={} files={}", job.getId(), actualFileCount + 1);
                        throw new ExportLimitExceededException();
                    }

                    File singleTemp = File.createTempFile("lms-dl-", ".tmp", tempDir);
                    try {
                        // Hard per-file byte cap: a BoundedOutputStream aborts the transfer the moment
                        // the remote file crosses the limit, so a hostile/oversized upstream can never
                        // fill the export disk (the old post-download length() check only fired after
                        // the whole file had already landed). The remaining total cap stays below.
                        long remainingTotal = properties.getMaxBytesPerExport() - actualBytes;
                        long perFileCap = Math.min(properties.getMaxBytesPerFile(), Math.max(remainingTotal, 0));
                        try (FileOutputStream fileOut = new FileOutputStream(singleTemp);
                             BoundedOutputStream boundedOut = new BoundedOutputStream(fileOut, perFileCap)) {
                            connector.download(cookies, downloadInfo.absoluteDownloadUrl(), boundedOut);
                        } catch (ExportLimitExceededException limit) {
                            log.error("Export byte limit blown mid-stream: jobId={} cap={}", job.getId(), perFileCap);
                            throw limit;
                        }

                        long currentFileBytes = singleTemp.length();

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

        } catch (ExportLimitExceededException limit) {
            // Controlled, user-safe message — no internal detail to hide.
            log.warn("LMS material export job hit a configured limit: jobId={}", job.getId());
            job.markFailed(limit.getMessage(), Instant.now());
            claimer.saveJob(job);
            deletePartialFile(zipFile, job.getId());
        } catch (Exception e) {
            // Unexpected failure: log the real exception server-side, but NEVER surface
            // e.getMessage() to the user — it can leak upstream URLs, stack/IO detail, etc.
            log.error("LMS material export job failed: jobId={}", job.getId(), e);
            job.markFailed("내보내기 생성 도중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Instant.now());
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

/**
 * Signals that a configured export limit (per-file bytes, total bytes, or file count) was hit.
 * Its message is intentionally user-safe and carries NO internal detail, so the worker can store
 * it directly into {@code failureReason} without leaking upstream URLs or stack/IO specifics.
 */
class ExportLimitExceededException extends RuntimeException {
    ExportLimitExceededException() {
        super("내보내기 한도가 초과되었습니다.");
    }
}

/**
 * Caps the number of bytes that may be written through it. As soon as a write would push the
 * running total past {@code maxBytes}, it throws {@link ExportLimitExceededException} instead of
 * forwarding the bytes — turning the streaming download into a hard, mid-flight byte cap rather
 * than an after-the-fact size check.
 */
class BoundedOutputStream extends FilterOutputStream {
    private final long maxBytes;
    private long written;

    BoundedOutputStream(OutputStream out, long maxBytes) {
        super(out);
        this.maxBytes = maxBytes;
    }

    @Override
    public void write(int b) throws IOException {
        ensureCapacity(1);
        out.write(b);
        written++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureCapacity(len);
        out.write(b, off, len);
        written += len;
    }

    private void ensureCapacity(long incoming) {
        if (written + incoming > maxBytes) {
            throw new ExportLimitExceededException();
        }
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
