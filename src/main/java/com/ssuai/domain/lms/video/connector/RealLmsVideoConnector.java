package com.ssuai.domain.lms.video.connector;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.video.dto.CourseWithLectures;
import com.ssuai.domain.lms.video.dto.LectureItem;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LmsSessionExpiredException;

@Component
@ConditionalOnProperty(name = "ssuai.lms-video.connector", havingValue = "real")
class RealLmsVideoConnector implements LmsVideoConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLmsVideoConnector.class);

    private static final String COMMONS_BASE_URL = "https://commons.ssu.ac.kr";
    private static final Pattern CONTENT_ID_IN_URL = Pattern.compile("[?&]content_id=([A-Za-z0-9_-]+)");
    private static final Pattern WEEK_KOREAN = Pattern.compile("(\\d{1,2})\\s*주차");
    private static final Pattern WEEK_ENGLISH = Pattern.compile("(?i)week\\s*(\\d{1,2})");

    private final LmsSsoProperties ssoProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    RealLmsVideoConnector(LmsSsoProperties ssoProperties, ObjectMapper objectMapper) {
        this.ssoProperties = ssoProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public List<CourseWithLectures> fetchLectureList(String studentId, LmsCookies cookies) {
        String bearer = extractXnApiToken(cookies.rawCookieHeader());
        if (bearer == null || bearer.isBlank()) {
            throw new LmsSessionExpiredException("xn_api_token missing from stored cookies");
        }

        long termId = fetchCurrentTermId(bearer, cookies, studentId);
        List<CourseSummary> courses = fetchCourses(bearer, cookies, termId);
        List<CourseWithLectures> result = new ArrayList<>(courses.size());
        for (CourseSummary course : courses) {
            result.add(new CourseWithLectures(course.courseId(), course.courseName(),
                    fetchLecturesForCourse(bearer, cookies, course.courseId())));
        }
        return result;
    }

    @Override
    public Path downloadVideoToFile(LmsCookies cookies, String mp4Url, int timeoutSeconds) {
        Path tempFile;
        try {
            tempFile = Files.createTempFile("ssuai-lms-video-", ".mp4");
        } catch (IOException exception) {
            throw unavailable("video temp file creation failed: " + exception.getMessage(), exception);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mp4Url))
                .header("Cookie", cookies.rawCookieHeader())
                .header("Referer", COMMONS_BASE_URL + "/")
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .GET()
                .build();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (response.statusCode() == 403) {
                Files.deleteIfExists(tempFile);
                throw unavailable("video CDN returned 403 - LMS auth may need refresh");
            }
            if (response.statusCode() != 200) {
                Files.deleteIfExists(tempFile);
                throw unavailable("video download failed: status=" + response.statusCode());
            }
            return tempFile;
        } catch (ConnectorUnavailableException exception) {
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(tempFile);
            throw unavailable("video download IO error: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteQuietly(tempFile);
            throw unavailable("video download interrupted", exception);
        }
    }

    @Override
    public Optional<String> fetchCaptionXml(LmsCookies cookies, String webFilesUrl, String storyGuid) {
        List<String> urls = List.of(
                webFilesUrl + "/caption_list_(" + storyGuid + ").xml",
                webFilesUrl + "/scripts/caption_list_(" + storyGuid + ").xml",
                webFilesUrl + "/data/caption_list_(" + storyGuid + ").xml");
        for (String url : urls) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/xml,text/xml,*/*")
                    .header("Cookie", cookies.rawCookieHeader())
                    .header("Referer", COMMONS_BASE_URL + "/")
                    .timeout(ssoProperties.getTimeout())
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    return Optional.ofNullable(response.body());
                }
                if (response.statusCode() != 404) {
                    log.warn("lms-video caption fetch failed: url={} status={}", url, response.statusCode());
                }
            } catch (IOException exception) {
                log.warn("lms-video caption fetch IO failure: url={} error={}", url, exception.getClass().getSimpleName());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("lms-video caption fetch interrupted: url={}", url);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private long fetchCurrentTermId(String bearer, LmsCookies cookies, String studentId) {
        String encoded = URLEncoder.encode(studentId, StandardCharsets.UTF_8);
        String url = ssoProperties.getCanvasBaseUrl()
                + "/learningx/api/v1/users/" + encoded
                + "/terms?include_invited_course_contained=true";
        JsonNode body = getJsonStrict(bearer, cookies, url);
        JsonNode terms = body.path("enrollment_terms");
        if (terms.isArray() && !terms.isEmpty()) {
            for (JsonNode term : terms) {
                if (term.path("default").asBoolean(false)) {
                    return term.path("id").asLong();
                }
            }
            return terms.get(0).path("id").asLong();
        }
        throw new LmsSessionExpiredException("no terms returned for student");
    }

    private List<CourseSummary> fetchCourses(String bearer, LmsCookies cookies, long termId) {
        String url = ssoProperties.getCanvasBaseUrl()
                + "/learningx/api/v1/learn_activities/courses?term_ids[]=" + termId;
        JsonNode body = getJsonStrict(bearer, cookies, url);
        List<CourseSummary> courses = new ArrayList<>();
        if (body.isArray()) {
            for (JsonNode course : body) {
                String id = firstText(course, "id", "course_id");
                String name = firstText(course, "name", "course_name", "title");
                if (!id.isBlank()) {
                    courses.add(new CourseSummary(id, name.isBlank() ? "Unknown Course" : name));
                }
            }
        }
        return courses;
    }

    private List<LectureItem> fetchLecturesForCourse(String bearer, LmsCookies cookies, String courseId) {
        List<String> urls = List.of(
                ssoProperties.getCanvasBaseUrl() + "/learningx/api/v1/courses/" + courseId + "/modules",
                ssoProperties.getCanvasBaseUrl() + "/learningx/api/v1/courses/" + courseId + "/xncontent_list",
                COMMONS_BASE_URL + "/learningx/api/v1/courses/" + courseId + "/xncontent_list");
        for (String url : urls) {
            Optional<JsonNode> body = getJsonIfOk(bearer, cookies, url);
            if (body.isEmpty()) {
                continue;
            }
            List<LectureItem> lectures = extractLectures(body.get());
            if (!lectures.isEmpty()) {
                return lectures;
            }
        }
        log.warn("lms-video lecture list: no video content endpoint worked for course={}", courseId);
        return List.of();
    }

    private JsonNode getJsonStrict(String bearer, LmsCookies cookies, String url) {
        HttpResponse<String> response = sendJsonRequest(bearer, cookies, url);
        if (response.statusCode() == 401) {
            throw new LmsSessionExpiredException("canvas returned 401 - session expired");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LmsSessionExpiredException("canvas API error: status=" + response.statusCode() + " url=" + url);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new LmsSessionExpiredException("canvas API JSON parse error: " + exception.getMessage());
        }
    }

    private Optional<JsonNode> getJsonIfOk(String bearer, LmsCookies cookies, String url) {
        HttpResponse<String> response = sendJsonRequest(bearer, cookies, url);
        String snippet = snippet(response.body(), 2000);
        log.info("lms-video endpoint probe: url={} status={} body='{}'",
                url, response.statusCode(), snippet);
        if (response.statusCode() == 401) {
            throw new LmsSessionExpiredException("canvas returned 401 - session expired");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (IOException exception) {
            log.warn("lms-video endpoint JSON parse failed: url={} error={}",
                    url, exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private HttpResponse<String> sendJsonRequest(String bearer, LmsCookies cookies, String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("Referer", ssoProperties.getCanvasBaseUrl() + "/")
                .timeout(ssoProperties.getTimeout())
                .GET();
        if (url.startsWith(COMMONS_BASE_URL)) {
            builder.header("Cookie", cookies.rawCookieHeader());
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new LmsSessionExpiredException("canvas API IO error: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LmsSessionExpiredException("canvas API interrupted");
        }
    }

    private static List<LectureItem> extractLectures(JsonNode body) {
        List<LectureItem> lectures = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        traverse(body, null, null, lectures, seen);
        return lectures;
    }

    private static void traverse(JsonNode node, String inheritedTitle, String inheritedWeek,
            List<LectureItem> lectures, Set<String> seen) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                traverse(child, inheritedTitle, inheritedWeek, lectures, seen);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String title = firstNonBlank(firstText(node, "title", "name", "item_name", "content_title",
                "xn_content_name", "module_name", "moduleName"), inheritedTitle);
        String week = firstNonBlank(weekFromNode(node), inheritedWeek, weekFrom(title));
        String contentId = contentIdFromNode(node);
        if (!contentId.isBlank() && seen.add(contentId)) {
            lectures.add(new LectureItem(contentId,
                    title == null || title.isBlank() ? contentId : title,
                    week,
                    durationSecondsFromNode(node)));
        }

        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            traverse(children.next(), title, week, lectures, seen);
        }
    }

    private static String contentIdFromNode(JsonNode node) {
        String exact = firstText(node, "xn_content_id", "content_id", "contentId",
                "commons_content_id", "commonsContentId");
        if (!exact.isBlank()) {
            return exact;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String lower = name.toLowerCase();
            if (lower.contains("content") && lower.contains("id") && !lower.contains("context")) {
                String value = textValue(node.path(name));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        String url = firstText(node, "url", "href", "html_url", "external_url");
        Matcher matcher = CONTENT_ID_IN_URL.matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int durationSecondsFromNode(JsonNode node) {
        String exact = firstText(node, "duration_seconds", "durationSeconds", "duration",
                "playtime", "play_time", "learning_time", "content_duration");
        if (!exact.isBlank()) {
            return parseDurationSeconds(exact);
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String lower = name.toLowerCase();
            if (lower.contains("duration") || lower.contains("playtime") || lower.contains("learning_time")) {
                int parsed = parseDurationSeconds(textValue(node.path(name)));
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return 0;
    }

    private static String weekFromNode(JsonNode node) {
        String exact = firstText(node, "week", "week_name", "weekName", "week_title", "weekTitle");
        if (!exact.isBlank()) {
            String week = weekFrom(exact);
            return week == null ? exact : week;
        }
        return weekFrom(firstText(node, "module_name", "moduleName", "title", "name"));
    }

    private static String weekFrom(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher korean = WEEK_KOREAN.matcher(text);
        if (korean.find()) {
            return korean.group(1) + "주차";
        }
        Matcher english = WEEK_ENGLISH.matcher(text);
        if (english.find()) {
            return english.group(1) + "주차";
        }
        if (text.matches("\\d{1,2}")) {
            return text + "주차";
        }
        return null;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            String text = textValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String textValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || value.isContainerNode()) {
            return "";
        }
        return value.asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int parseDurationSeconds(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":");
            double seconds = 0;
            for (String part : parts) {
                try {
                    seconds = seconds * 60 + Double.parseDouble(part);
                } catch (NumberFormatException exception) {
                    return 0;
                }
            }
            return (int) Math.round(seconds);
        }
        try {
            return (int) Math.round(Double.parseDouble(trimmed));
        } catch (NumberFormatException exception) {
            return 0;
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

    private static String snippet(String body, int max) {
        if (body == null) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() > max ? compact.substring(0, max) + "...(truncated)" : compact;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static ConnectorUnavailableException unavailable(String message) {
        return unavailable(message, null);
    }

    private static ConnectorUnavailableException unavailable(String message, Throwable cause) {
        return new ConnectorUnavailableException(new IllegalStateException(message, cause));
    }

    private record CourseSummary(String courseId, String courseName) {
    }
}
