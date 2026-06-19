package com.ssuai.domain.lms.connector;

import java.io.IOException;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsMaterial;
import com.ssuai.domain.lms.util.CommonsXmlParser;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.lms-materials", havingValue = "real")
public class RealLmsMaterialsConnector implements LmsMaterialsConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLmsMaterialsConnector.class);

    private final LmsSsoProperties properties;
    private final ObjectMapper objectMapper;
    private final LmsMaterialSizeResolver sizeResolver;

    public RealLmsMaterialsConnector(
            LmsSsoProperties properties,
            ObjectMapper objectMapper,
            LmsMaterialSizeResolver sizeResolver) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sizeResolver = sizeResolver;
    }

    private CookieManager createCookieManager(String rawCookieHeader, String targetUrl) {
        CookieManager cookieManager = new CookieManager();
        if (rawCookieHeader == null || rawCookieHeader.isBlank()) {
            return cookieManager;
        }
        URI targetUri = URI.create(targetUrl);
        String host = targetUri.getHost();

        for (String pair : rawCookieHeader.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (!name.isEmpty()) {
                    HttpCookie cookie = new HttpCookie(name, value);
                    cookie.setPath("/");
                    cookie.setVersion(0);
                    if (host != null && host.endsWith("ssu.ac.kr")) {
                        if ("xn_api_token".equals(name) || "_normandy_session".equals(name)
                                || "_legacy_normandy_session".equals(name) || "canvas_session".equals(name)) {
                            cookie.setDomain("canvas.ssu.ac.kr");
                            cookieManager.getCookieStore().add(URI.create("https://canvas.ssu.ac.kr"), cookie);
                        } else {
                            cookie.setDomain("lms.ssu.ac.kr");
                            cookieManager.getCookieStore().add(URI.create("https://lms.ssu.ac.kr"), cookie);
                        }
                    } else {
                        cookie.setDomain(host);
                        cookieManager.getCookieStore().add(targetUri, cookie);
                    }
                }
            }
        }
        return cookieManager;
    }

    @Override
    public List<LmsCourse> fetchCourses(String studentId, LmsCookies cookies, long termId) {
        randomDelay();
        String bearer = extractXnApiToken(cookies.rawCookieHeader());
        if (bearer == null || bearer.isBlank()) {
            throw new LmsSessionExpiredException("xn_api_token missing from stored cookies");
        }

        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/learn_activities/courses?term_ids[]=" + termId;
        CookieManager cookieManager = createCookieManager(cookies.rawCookieHeader(), url);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("Cookie", cookies.rawCookieHeader())
                .timeout(properties.getTimeout())
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new LmsSessionExpiredException("LearningX returned " + response.statusCode() + " — session expired or forbidden");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Truncate body to avoid logging sensitive data
                String bodyExcerpt = response.body() != null && response.body().length() > 200
                        ? response.body().substring(0, 200) + "..."
                        : response.body();
                throw new LmsApiException(
                        "LearningX API error: status=" + response.statusCode() + " body=" + bodyExcerpt,
                        response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            List<LmsCourse> list = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    long id = node.path("id").asLong();
                    String name = node.path("name").asText("");
                    String courseCode = node.path("course_code").asText("");
                    if (id > 0) {
                        // endpoint already filters by term_ids[] — use termId param directly
                        list.add(new LmsCourse(id, name, courseCode, termId));
                    }
                }
            }
            return list;
        } catch (IOException exception) {
            throw new LmsSessionExpiredException("LearningX API IO error: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LmsSessionExpiredException("LearningX API interrupted");
        }
    }

    @Override
    public List<LmsMaterial> fetchMaterials(String studentId, LmsCookies cookies, LmsCourse course) {
        randomDelay();
        String url = properties.getCanvasBaseUrl() + "/learningx/api/v1/courses/" + course.id() + "/modules?include_detail=true";
        CookieManager cookieManager = createCookieManager(cookies.rawCookieHeader(), url);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String bearer = extractXnApiToken(cookies.rawCookieHeader());
        if (bearer == null || bearer.isBlank()) {
            throw new LmsSessionExpiredException("xn_api_token missing from stored cookies");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("Cookie", cookies.rawCookieHeader())
                .timeout(properties.getTimeout())
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new LmsSessionExpiredException("LearningX returned " + response.statusCode() + " — session expired or forbidden");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Truncate body to avoid logging sensitive data
                String bodyExcerpt = response.body() != null && response.body().length() > 200
                        ? response.body().substring(0, 200) + "..."
                        : response.body();
                throw new LmsApiException(
                        "LearningX API error: status=" + response.statusCode() + " body=" + bodyExcerpt,
                        response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            List<LmsMaterial> list = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode moduleNode : root) {
                    String weekTitle = moduleNode.path("title").asText("");
                    JsonNode items = moduleNode.path("module_items");
                    if (items.isArray()) {
                        for (JsonNode itemNode : items) {
                            JsonNode contentData = itemNode.path("content_data");
                            if (contentData.isMissingNode() || contentData.isNull()) {
                                continue;
                            }
                            JsonNode itemContentData = contentData.path("item_content_data");
                            if (itemContentData.isMissingNode() || itemContentData.isNull()) {
                                continue;
                            }

                            String contentId = itemContentData.path("content_id").asText("");
                            if (contentId.isBlank()) {
                                continue;
                            }

                            String contentType = itemContentData.path("content_type").asText(null);
                            String fileName = itemContentData.path("file_name").isTextual() ? itemContentData.path("file_name").asText() : null;
                            String itemTitle = itemContentData.path("title").asText(itemNode.path("title").asText(""));
                            JsonNode sizeNode = itemContentData.path("total_file_size");
                            Long sizeBytes = sizeNode.isNumber() ? sizeNode.asLong() : null;

                            String extension = null;
                            if (fileName != null) {
                                int lastDot = fileName.lastIndexOf('.');
                                if (lastDot != -1 && lastDot < fileName.length() - 1) {
                                    extension = fileName.substring(lastDot + 1).toLowerCase().trim();
                                }
                            }

                            list.add(new LmsMaterial(contentId, course.id(), course.name(), fileName, extension, sizeBytes, weekTitle, itemTitle, contentType));
                        }
                    }
                }
            }
            return correctUnreliableSizes(cookies, client, list);
        } catch (IOException exception) {
            throw new LmsSessionExpiredException("Canvas API IO error: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LmsSessionExpiredException("Canvas API interrupted");
        }
    }

    @Override
    public Optional<ContentDownloadInfo> resolveDownload(LmsCookies cookies, String contentId) {
        randomDelay();
        String url = properties.getCommonsBaseUrl() + "/viewer/ssplayer/uniplayer_support/content.php?content_id=" + URLEncoder.encode(contentId, StandardCharsets.UTF_8);
        CookieManager cookieManager = createCookieManager(cookies.rawCookieHeader(), url);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        return resolveDownload(cookies, contentId, client);
    }

    private Optional<ContentDownloadInfo> resolveDownload(
            LmsCookies cookies,
            String contentId,
            HttpClient authenticatedClient) {
        String url = properties.getCommonsBaseUrl() + "/viewer/ssplayer/uniplayer_support/content.php?content_id=" + URLEncoder.encode(contentId, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Cookie", cookies.rawCookieHeader())
                .timeout(properties.getTimeout())
                .GET()
                .build();

        try {
            HttpResponse<String> response = authenticatedClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            CommonsXmlParser.ParsedContent parsed = CommonsXmlParser.parse(response.body());
            if (parsed == null || parsed.downloadUri() == null || parsed.downloadUri().isBlank()) {
                return Optional.empty();
            }

            String absoluteUrl = properties.getCommonsBaseUrl() + parsed.downloadUri();
            return Optional.of(new ContentDownloadInfo(contentId, parsed.title(), absoluteUrl));
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private List<LmsMaterial> correctUnreliableSizes(
            LmsCookies cookies,
            HttpClient authenticatedClient,
            List<LmsMaterial> materials) {
        List<LmsMaterial> correctedMaterials = new ArrayList<>(materials.size());
        int attempted = 0;
        int corrected = 0;

        for (LmsMaterial material : materials) {
            if (hasReliableReportedSize(material)) {
                correctedMaterials.add(material);
                continue;
            }

            attempted++;
            Long resolvedSize = null;
            Optional<ContentDownloadInfo> download = resolveDownload(
                    cookies, material.contentId(), authenticatedClient);
            if (download.isPresent()) {
                OptionalLong contentLength = sizeResolver.resolve(
                        authenticatedClient,
                        cookies,
                        download.get().absoluteDownloadUrl(),
                        properties.getTimeout());
                if (contentLength.isPresent()) {
                    resolvedSize = contentLength.getAsLong();
                    corrected++;
                }
            }

            correctedMaterials.add(new LmsMaterial(
                    material.contentId(),
                    material.courseId(),
                    material.courseName(),
                    material.fileName(),
                    material.extension(),
                    resolvedSize,
                    material.weekTitle(),
                    material.title(),
                    material.contentType()));
        }

        if (attempted > 0) {
            log.info("LMS material size HEAD correction completed: attempted={}, corrected={}, unknown={}",
                    attempted, corrected, attempted - corrected);
        }
        return correctedMaterials;
    }

    private static boolean hasReliableReportedSize(LmsMaterial material) {
        return "pdf".equalsIgnoreCase(material.contentType())
                && material.sizeBytes() != null
                && material.sizeBytes() > 0;
    }

    @Override
    public void download(LmsCookies cookies, String absoluteDownloadUrl, OutputStream destination) {
        randomDelay();
        CookieManager cookieManager = createCookieManager(cookies.rawCookieHeader(), absoluteDownloadUrl);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(absoluteDownloadUrl))
                .header("Cookie", cookies.rawCookieHeader())
                .timeout(properties.getTimeout())
                .GET()
                .build();

        try {
            HttpResponse<java.io.InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LmsSessionExpiredException("Download failed with HTTP status " + response.statusCode());
            }
            try (java.io.InputStream in = response.body()) {
                in.transferTo(destination);
            }
        } catch (IOException exception) {
            throw new LmsSessionExpiredException("LMS download IO error: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LmsSessionExpiredException("LMS download interrupted");
        }
    }

    private static void randomDelay() {
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 1200));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String extractXnApiToken(String rawCookieHeader) {
        if (rawCookieHeader == null) {
            return null;
        }
        for (String pair : rawCookieHeader.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0 && "xn_api_token".equals(trimmed.substring(0, eq).trim())) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
