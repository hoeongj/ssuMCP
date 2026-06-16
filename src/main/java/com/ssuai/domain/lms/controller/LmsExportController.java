package com.ssuai.domain.lms.controller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.lms.export.LmsExportJob;
import com.ssuai.domain.lms.export.LmsExportJobRepository;
import com.ssuai.domain.lms.export.LmsExportStatus;

@RestController
@RequestMapping("/api/lms/exports")
public class LmsExportController {

    private static final Logger log = LoggerFactory.getLogger(LmsExportController.class);

    private final LmsExportJobRepository jobRepository;

    public LmsExportController(LmsExportJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @GetMapping("/{jobId}/download")
    public ResponseEntity<?> download(
            @PathVariable("jobId") String jobId,
            @RequestParam("token") String token
    ) {
        OptionalLmsExportJob jobOpt = OptionalLmsExportJob.from(jobRepository.findById(jobId));
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        LmsExportJob job = jobOpt.get();

        // SHA-256 hash the query token
        String tokenHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            tokenHash = HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm missing", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // Constant-time compare tokenHash
        if (!MessageDigest.isEqual(job.getTokenHash().getBytes(StandardCharsets.UTF_8), tokenHash.getBytes(StandardCharsets.UTF_8))) {
            log.warn("LMS export download token mismatch: jobId={}", jobId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Instant now = Instant.now();
        if (now.isAfter(job.getExpiresAt())) {
            log.info("LMS export download expired: jobId={} expiresAt={}", jobId, job.getExpiresAt());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("message", "다운로드 링크가 만료되었습니다. (유효시간 20분)"));
        }

        LmsExportStatus status = job.getStatus();
        if (status == LmsExportStatus.QUEUED || status == LmsExportStatus.BUILDING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("status", "BUILDING", "message", "압축 파일이 빌드 중입니다. 잠시 후 페이지를 새로고침해 주세요."));
        }

        if (status == LmsExportStatus.FAILED) {
            String reason = job.getFailureReason() != null ? job.getFailureReason() : "내보내기 생성 실패";
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("message", "파일 생성 실패: " + reason));
        }

        if (status == LmsExportStatus.EXPIRED) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("message", "다운로드 링크가 만료되었습니다."));
        }

        if (status == LmsExportStatus.READY) {
            if (job.getFilePath() == null) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(Map.of("message", "파일 경로를 찾을 수 없습니다."));
            }

            File file = new File(job.getFilePath());
            if (!file.exists()) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(Map.of("message", "파일이 삭제되었거나 존재하지 않습니다."));
            }

            Resource resource = new FileSystemResource(file);
            String contentDisposition = "attachment; filename=\"lms-materials-" + jobId + ".zip\"";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(file.length())
                    .body(resource);
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    // Helper static class to avoid Optional import conflicts
    private static class OptionalLmsExportJob {
        private final LmsExportJob value;

        private OptionalLmsExportJob(LmsExportJob value) {
            this.value = value;
        }

        public static OptionalLmsExportJob from(java.util.Optional<LmsExportJob> opt) {
            return new OptionalLmsExportJob(opt.orElse(null));
        }

        public boolean isEmpty() {
            return value == null;
        }

        public LmsExportJob get() {
            return value;
        }
    }
}
