package com.ssuai.domain.lms.connector;

import java.io.IOException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.dto.AssignmentItem;
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.global.exception.LmsSessionExpiredException;
import com.ssuai.domain.lms.service.LmsTermResolver;

/**
 * Calls the canvas.ssu.ac.kr LearningX API with the student's session cookies.
 *
 * <p>Three-step flow per call:
 * <ol>
 *   <li>GET {@code /learningx/api/v1/users/{studentId}/terms} — pick the first
 *       (most-recent) term id returned by canvas.</li>
 *   <li>GET {@code /learningx/api/v1/learn_activities/courses?term_ids[]={termId}}
 *       — build a map from course id → course name.</li>
 *   <li>GET {@code /learningx/api/v1/learn_activities/to_dos?term_ids[]={termId}}
 *       — flatten the {@code todo_list} arrays into {@link AssignmentItem}s,
 *       joining course names from step 2.</li>
 * </ol>
 *
 * <p>A 401 from canvas is treated as a session expiry
 * ({@link LmsSessionExpiredException}). Any other non-2xx is an I/O error.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.lms-assignments", havingValue = "real")
class RealLmsAssignmentsConnector implements LmsAssignmentsConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLmsAssignmentsConnector.class);

    private final LmsSsoProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    RealLmsAssignmentsConnector(LmsSsoProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
    public List<LmsTermItem> fetchTerms(String studentId, LmsCookies cookies) {
        randomDelay();
        CookieManager cookieManager = createCookieManager(cookies.rawCookieHeader(), properties.getCanvasBaseUrl());
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String bearer = extractXnApiToken(cookies.rawCookieHeader());
        if (bearer == null || bearer.isBlank()) {
            throw new LmsSessionExpiredException("xn_api_token missing from stored cookies");
        }
        return fetchTermItems(client, bearer, studentId);
    }

    @Override
    public AssignmentsResponse fetchAssignments(String studentId, LmsCookies cookies, Long termId) {
        randomDelay();
        CookieManager cookieManager = createCookieManager(cookies.rawCookieHeader(), properties.getCanvasBaseUrl());
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String bearer = extractXnApiToken(cookies.rawCookieHeader());
        if (bearer == null || bearer.isBlank()) {
            throw new LmsSessionExpiredException("xn_api_token missing from stored cookies");
        }
        log.info("lms canvas bearer extracted: lengthBytes={} prefix='{}...'",
                bearer.length(), bearer.length() > 8 ? bearer.substring(0, 8) : "(short)");

        long resolvedTermId = (termId != null) ? termId : resolveDefaultTermId(client, bearer, studentId);
        Map<Long, String> courseNames = fetchCourseNames(client, bearer, resolvedTermId);
        List<AssignmentItem> items = fetchTodoItems(client, bearer, resolvedTermId, courseNames);
        return new AssignmentsResponse(resolvedTermId, items);
    }

    private List<LmsTermItem> fetchTermItems(HttpClient client, String bearer, String studentId) {
        String encoded = URLEncoder.encode(studentId, StandardCharsets.UTF_8);
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/users/" + encoded
                + "/terms?include_invited_course_contained=true";
        JsonNode body = getJson(client, bearer, url);
        JsonNode terms = body.path("enrollment_terms");
        List<LmsTermItem> result = new ArrayList<>();
        if (terms.isArray()) {
            for (JsonNode term : terms) {
                long id = term.path("id").asLong();
                String name = term.path("name").asText("");
                String startAt = term.path("start_at").isNull() ? null : term.path("start_at").asText(null);
                String endAt = term.path("end_at").isNull() ? null : term.path("end_at").asText(null);
                boolean isDefault = term.path("default").asBoolean(false);
                if (id > 0) {
                    result.add(new LmsTermItem(id, term.path("name").asText(""), startAt, endAt, isDefault));
                }
            }
        }
        if (result.isEmpty()) {
            String bodyStr = body.toString();
            String snippet = bodyStr.length() > 1500 ? bodyStr.substring(0, 1500) + "...(truncated)" : bodyStr;
            log.warn("lms canvas terms empty: url={} responseBody='{}'", url, snippet);
            throw new LmsSessionExpiredException("no terms returned for student");
        }
        log.info("lms canvas terms: count={} names={}", result.size(),
                result.stream().map(LmsTermItem::name).toList());
        return result;
    }

    private long resolveDefaultTermId(HttpClient client, String bearer, String studentId) {
        List<LmsTermItem> terms = fetchTermItems(client, bearer, studentId);
        return LmsTermResolver.resolveCurrentTermId(terms);
    }

    private Map<Long, String> fetchCourseNames(HttpClient client, String bearer, long termId) {
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/learn_activities/courses?term_ids[]=" + termId;
        JsonNode body = getJson(client, bearer, url);
        Map<Long, String> map = new HashMap<>();
        if (body.isArray()) {
            for (JsonNode course : body) {
                long id = course.path("id").asLong();
                String name = course.path("name").asText("");
                if (id > 0) {
                    map.put(id, name);
                }
            }
        }
        return map;
    }

    private List<AssignmentItem> fetchTodoItems(
            HttpClient client, String bearer, long termId, Map<Long, String> courseNames) {
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/learn_activities/to_dos?term_ids[]=" + termId;
        JsonNode body = getJson(client, bearer, url);
        JsonNode todos = body.path("to_dos");
        List<AssignmentItem> items = new ArrayList<>();
        if (todos.isArray()) {
            for (JsonNode courseNode : todos) {
                long courseId = courseNode.path("course_id").asLong();
                String courseName = courseNames.getOrDefault(courseId, "Unknown Course");
                JsonNode todoList = courseNode.path("todo_list");
                if (todoList.isArray()) {
                    for (JsonNode todo : todoList) {
                        String title = todo.path("title").asText("");
                        String type = todo.path("component_type").asText("assignment");
                        String dueDate = todo.path("due_date").isNull()
                                ? null : todo.path("due_date").asText(null);
                        items.add(new AssignmentItem(courseName, title, type, dueDate));
                    }
                }
            }
        }
        return items;
    }

    private JsonNode getJson(HttpClient client, String bearer, String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("Referer", properties.getCanvasBaseUrl() + "/")
                .timeout(properties.getTimeout())
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.info("lms canvas response: url={} status={} bodyLengthBytes={}",
                    url, response.statusCode(), response.body() == null ? 0 : response.body().length());
            if (response.statusCode() == 401) {
                String bodySnippet = response.body() == null ? "(null)"
                        : response.body().substring(0, Math.min(400, response.body().length()))
                                .replaceAll("\\s+", " ");
                log.warn("lms canvas 401: url={} body='{}'", url, bodySnippet);
                throw new LmsSessionExpiredException("canvas returned 401 — session expired");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LmsSessionExpiredException(
                        "canvas API error: status=" + response.statusCode() + " url=" + url);
            }
            return objectMapper.readTree(response.body());
        } catch (LmsSessionExpiredException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new LmsSessionExpiredException("canvas API io error: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LmsSessionExpiredException("canvas API interrupted");
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
