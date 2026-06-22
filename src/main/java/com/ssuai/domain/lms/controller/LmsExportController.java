package com.ssuai.domain.lms.controller;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.lms.export.LmsExportJob;
import com.ssuai.domain.lms.export.LmsExportJobRepository;
import com.ssuai.domain.lms.export.LmsExportProperties;
import com.ssuai.domain.lms.export.LmsExportStatus;

@RestController
@RequestMapping("/api/lms/exports")
public class LmsExportController {

    private static final Logger log = LoggerFactory.getLogger(LmsExportController.class);

    private final LmsExportJobRepository jobRepository;
    private final LmsExportProperties properties;

    public LmsExportController(LmsExportJobRepository jobRepository, LmsExportProperties properties) {
        this.jobRepository = jobRepository;
        this.properties = properties;
    }

    /**
     * Self-contained browser page for the download link. State-agnostic: it reads the
     * jobId+token from its own URL, polls {@code ?format=json} every 3s, shows a spinner
     * while building, and on READY offers a button plus a 5s auto-download countdown. The
     * actual file is fetched via {@code ?dl=1} in a hidden iframe so the page stays put and
     * the button remains re-clickable. No template engine — the page needs no server data.
     */
    private static final String DOWNLOAD_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="ko">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>LMS 강의자료 다운로드</title>
            <style>
              body { font-family: -apple-system, "Segoe UI", Roboto, "Malgun Gothic", sans-serif;
                     background: #f4f5f7; color: #1f2933; margin: 0;
                     display: flex; min-height: 100vh; align-items: center; justify-content: center; }
              .card { background: #fff; padding: 40px 48px; border-radius: 14px;
                      box-shadow: 0 6px 24px rgba(0,0,0,.08); text-align: center; max-width: 420px; width: 90%; }
              h1 { font-size: 20px; margin: 0 0 20px; }
              #status { font-size: 15px; color: #52606d; min-height: 22px; }
              #countdown { font-size: 13px; color: #7b8794; margin-top: 12px; min-height: 18px; }
              .spinner { width: 38px; height: 38px; margin: 22px auto 6px; border: 4px solid #e4e7eb;
                         border-top-color: #2563eb; border-radius: 50%; animation: spin 0.9s linear infinite; }
              @keyframes spin { to { transform: rotate(360deg); } }
              button { margin-top: 18px; padding: 12px 26px; font-size: 15px; font-weight: 600;
                       color: #fff; background: #2563eb; border: none; border-radius: 8px; cursor: pointer; }
              button:hover { background: #1d4ed8; }
            </style>
            </head>
            <body>
            <div class="card">
              <h1>LMS 강의자료 다운로드</h1>
              <div id="status">상태를 확인하고 있어요…</div>
              <div id="spinner" class="spinner"></div>
              <button id="downloadBtn" hidden>지금 다운로드</button>
              <div id="countdown"></div>
            </div>
            <script>
            (function () {
              var params = new URLSearchParams(location.search);
              var token = params.get('token') || '';
              var base = location.pathname;
              var pollTimer = null, countdownTimer = null, downloaded = false;
              var statusEl = document.getElementById('status');
              var spinnerEl = document.getElementById('spinner');
              var btnEl = document.getElementById('downloadBtn');
              var cdEl = document.getElementById('countdown');

              function withToken(extra) {
                return base + '?token=' + encodeURIComponent(token) + extra;
              }
              function triggerDownload() {
                var f = document.getElementById('dlframe');
                if (!f) { f = document.createElement('iframe'); f.id = 'dlframe'; f.style.display = 'none'; document.body.appendChild(f); }
                f.src = withToken('&dl=1');
                downloaded = true;
              }
              function startCountdown(n) {
                cdEl.textContent = n + '초 후 자동으로 다운로드됩니다…';
                countdownTimer = setInterval(function () {
                  if (downloaded) { clearInterval(countdownTimer); cdEl.textContent = ''; return; }
                  n -= 1;
                  if (n <= 0) { clearInterval(countdownTimer); cdEl.textContent = '다운로드를 시작합니다…'; triggerDownload(); return; }
                  cdEl.textContent = n + '초 후 자동으로 다운로드됩니다…';
                }, 1000);
              }
              function render(status, message) {
                if (status === 'READY') {
                  if (pollTimer) clearInterval(pollTimer);
                  statusEl.textContent = '압축이 완료되었습니다.';
                  spinnerEl.style.display = 'none';
                  btnEl.hidden = false;
                  if (!countdownTimer && !downloaded) startCountdown(5);
                } else if (status === 'QUEUED' || status === 'BUILDING') {
                  statusEl.textContent = message || '압축 파일을 만들고 있어요. 약 5분 정도 걸릴 수 있어요…';
                  spinnerEl.style.display = 'block';
                } else {
                  if (pollTimer) clearInterval(pollTimer);
                  spinnerEl.style.display = 'none';
                  statusEl.textContent = message || '다운로드할 수 없습니다.';
                }
              }
              function poll() {
                fetch(withToken('&format=json'), { headers: { 'Accept': 'application/json' } })
                  .then(function (r) { return r.json().catch(function () { return {}; }); })
                  .then(function (data) { render(data.status, data.message); })
                  .catch(function () { /* transient — keep polling */ });
              }
              btnEl.addEventListener('click', function () {
                if (countdownTimer) clearInterval(countdownTimer);
                cdEl.textContent = '';
                triggerDownload();
              });
              poll();
              pollTimer = setInterval(poll, 3000);
            })();
            </script>
            </body>
            </html>
            """;

    @GetMapping("/{jobId}/download")
    public ResponseEntity<?> download(
            @PathVariable("jobId") String jobId,
            @RequestParam("token") String token,
            @RequestParam(value = "format", required = false) String format,
            @RequestParam(value = "dl", required = false) String dl,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept
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

        // Content negotiation (token already validated above).
        //  - dl present       → always stream the binary (the page's download trigger);
        //                       checked first so a browser's text/html Accept can't shadow it.
        //  - Accept text/html → serve the self-contained status/download page (real browsers).
        //                       Matched as a literal substring, NOT via produces=, because the
        //                       default Accept "*/*" matches text/html under MediaType negotiation
        //                       and would route API/curl callers (and the existing JSON tests) here.
        //  - format=json      → JSON status for every state incl. READY (the page polls this).
        //  - otherwise        → legacy behaviour: JSON for non-ready states, binary stream on READY.
        boolean forceDownload = dl != null && !dl.isBlank();
        boolean wantsHtml = !forceDownload && accept != null && accept.contains("text/html");
        boolean wantsJsonStatus = "json".equalsIgnoreCase(format);

        if (wantsHtml) {
            // charset=UTF-8 in the header (not just the <meta> tag) so the Korean copy
            // renders correctly — without it the body is decoded as ISO-8859-1 → mojibake.
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(DOWNLOAD_PAGE_HTML);
        }

        Instant now = Instant.now();
        if (now.isAfter(job.getExpiresAt())) {
            log.info("LMS export download expired: jobId={} expiresAt={}", jobId, job.getExpiresAt());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("status", "EXPIRED", "message", "다운로드 링크가 만료되었습니다. (유효시간 " + properties.getDownloadTtl().toMinutes() + "분)"));
        }

        LmsExportStatus status = job.getStatus();
        if (status == LmsExportStatus.QUEUED || status == LmsExportStatus.BUILDING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("status", "BUILDING", "message", "압축 파일을 만들고 있어요. 약 5분 정도 걸릴 수 있어요. 이 페이지를 열어두면 완료되는 대로 자동으로 다운로드됩니다."));
        }

        if (status == LmsExportStatus.FAILED) {
            String reason = job.getFailureReason() != null ? job.getFailureReason() : "내보내기 생성 실패";
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("status", "FAILED", "message", "파일 생성 실패: " + reason));
        }

        if (status == LmsExportStatus.EXPIRED) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("status", "EXPIRED", "message", "다운로드 링크가 만료되었습니다."));
        }

        if (status == LmsExportStatus.READY) {
            if (job.getFilePath() == null) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(Map.of("status", "FAILED", "message", "파일 경로를 찾을 수 없습니다."));
            }

            File file = new File(job.getFilePath());
            if (!file.exists()) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(Map.of("status", "FAILED", "message", "파일이 삭제되었거나 존재하지 않습니다."));
            }

            // Path-traversal guard: resolve the canonical real paths of the served file and the
            // configured export base dir, then require the file to live inside the base (component-wise
            // Path.startsWith — NOT String.startsWith, which a sibling like "<base>-evil" would pass).
            // A crafted job.filePath containing "../" cannot escape the export dir and serve arbitrary
            // files. toRealPath() also resolves symlinks, so a symlinked path can't tunnel out either.
            if (!isInsideExportBase(file)) {
                log.warn("LMS export download path escapes export base dir: jobId={} filePath={}", jobId, job.getFilePath());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // Page poll (format=json): report readiness without consuming the one-shot stream.
            if (wantsJsonStatus) {
                return ResponseEntity.ok(Map.of("status", "READY", "message", "다운로드 준비가 완료되었습니다."));
            }

            Resource resource = new FileSystemResource(file);
            String contentDisposition = "attachment; filename=\"lms-materials-" + jobId + ".zip\"";

            // The capability token rides in the URL query string (browser-download constraint).
            //  - Referrer-Policy: no-referrer → the token URL is never sent as a Referer to any
            //    third-party resource the download might touch.
            //  - Cache-Control: no-store + Pragma: no-cache → the tokenised response is never cached
            //    by browsers/proxies, so the token can't be replayed from a cache.
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .header("Referrer-Policy", "no-referrer")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(file.length())
                    .body(resource);
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    /**
     * True iff {@code file}'s canonical real path is contained within the configured export base
     * dir. Both sides are canonicalized via {@link Path#toRealPath()} (resolving symlinks and
     * "../"), then compared component-wise with {@link Path#startsWith}. Any I/O failure (missing
     * base dir, unreadable path) is treated as "not inside" — fail closed.
     */
    private boolean isInsideExportBase(File file) {
        try {
            Path base = new File(properties.getTempDir()).toPath().toRealPath();
            Path target = file.toPath().toRealPath();
            return target.startsWith(base);
        } catch (IOException e) {
            log.warn("LMS export download path containment check failed", e);
            return false;
        }
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
