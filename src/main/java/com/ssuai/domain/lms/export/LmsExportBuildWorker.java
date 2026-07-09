package com.ssuai.domain.lms.export;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
    private static final Pattern MANAGED_ZIP_NAME = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.zip$");

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
        runOnce(true);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        runOnce(false);
    }

    private void runOnce(boolean buildJobs) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (buildJobs) {
                buildQueuedJobs();
            }
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
            if (isActiveBuild(job, now)) {
                continue;
            }
            if (job.getFilePath() != null) {
                deleteExportFile(Path.of(job.getFilePath()), job.getId(), "expired ZIP");
            }
            if (job.getStatus() != LmsExportStatus.EXPIRED) {
                job.markExpired(now);
                repository.save(job);
                log.info("Expired LMS material export job: jobId={}", job.getId());
            }
        }
        sweepOrphanZipFiles(now);
    }

    private void sweepOrphanZipFiles(Instant now) {
        Path exportDir = exportDir();
        if (!Files.isDirectory(exportDir)) {
            return;
        }

        List<Path> zipFiles;
        try (Stream<Path> stream = Files.list(exportDir)) {
            zipFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isManagedZip)
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list LMS export directory for orphan ZIP sweep: path={}", exportDir, e);
            return;
        }

        if (zipFiles.isEmpty()) {
            return;
        }

        List<String> jobIds = zipFiles.stream()
                .map(this::jobIdFromZip)
                .toList();
        Map<String, LmsExportJob> jobsById = new HashMap<>();
        for (LmsExportJob job : repository.findAllById(jobIds)) {
            jobsById.put(job.getId(), job);
        }

        for (Path zipFile : zipFiles) {
            String jobId = jobIdFromZip(zipFile);
            LmsExportJob job = jobsById.get(jobId);
            if (shouldRetainZip(zipFile, job, now)) {
                continue;
            }
            deleteExportFile(zipFile, jobId, job == null ? "orphan ZIP without DB row" : "orphan ZIP for inactive job");
        }
    }

    private boolean shouldRetainZip(Path zipFile, LmsExportJob job, Instant now) {
        if (job == null) {
            return false;
        }
        if (isActiveBuild(job, now)) {
            return true;
        }
        if ((job.getStatus() == LmsExportStatus.READY || job.getStatus() == LmsExportStatus.DOWNLOADED)
                && !now.isAfter(job.getExpiresAt())
                && job.getFilePath() != null) {
            return sameNormalizedPath(zipFile, Path.of(job.getFilePath()));
        }
        return false;
    }

    private boolean isActiveBuild(LmsExportJob job, Instant now) {
        if (job.getStatus() != LmsExportStatus.BUILDING || job.getClaimedAt() == null) {
            return false;
        }
        Instant leaseCutoff = now.minus(properties.getLeaseDuration());
        return job.getClaimedAt().isAfter(leaseCutoff);
    }

    private boolean isManagedZip(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && MANAGED_ZIP_NAME.matcher(fileName.toString()).matches();
    }

    private String jobIdFromZip(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".zip".length());
    }

    private boolean sameNormalizedPath(Path left, Path right) {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    private Path exportDir() {
        return Path.of(properties.getTempDir()).toAbsolutePath().normalize();
    }

    private void deleteExportFile(Path path, String jobId, String reason) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(exportDir())) {
            log.warn("Refusing to delete LMS export file outside export directory: jobId={} path={}", jobId, normalized);
            return;
        }
        try {
            if (Files.deleteIfExists(normalized)) {
                log.info("Deleted LMS export {}: jobId={} path={}", reason, jobId, normalized);
            }
        } catch (IOException e) {
            log.warn("Failed to delete LMS export {}: jobId={} path={}", reason, jobId, normalized, e);
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
    private static final Logger log = LoggerFactory.getLogger(LmsExportJobClaimer.class);

    private final LmsExportJobRepository repository;
    private final LmsExportProperties properties;
    private final Clock clock;
    private final String ownerId;

    public LmsExportJobClaimer(
            LmsExportJobRepository repository,
            LmsExportProperties properties,
            Clock clock,
            @Value("${HOSTNAME:${random.uuid}}") String ownerId) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.ownerId = ownerId;
    }

    @Transactional
    public Optional<LmsExportJob> claimNextJob() {
        Instant now = clock.instant();
        Optional<LmsExportJob> jobOpt =
                repository.findClaimableForUpdate(now.minus(properties.getLeaseDuration()));
        if (jobOpt.isPresent()) {
            LmsExportJob job = jobOpt.get();
            job.claim(ownerId, now);
            return Optional.of(repository.save(job));
        }
        return Optional.empty();
    }

    @Transactional
    public void saveJob(LmsExportJob job) {
        Optional<LmsExportJob> current = repository.findByIdForUpdate(job.getId());
        if (current.isEmpty() || !ownerId.equals(current.get().getClaimedBy())) {
            log.warn("Ignoring stale LMS export completion: jobId={} owner={}", job.getId(), ownerId);
            return;
        }
        repository.save(job);
    }
}
