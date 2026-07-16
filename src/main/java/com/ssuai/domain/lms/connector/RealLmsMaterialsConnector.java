package com.ssuai.domain.lms.connector;

import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsMaterial;
import com.ssuai.domain.lms.util.CommonsXmlParser;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

/** LearningX material connector backed by the canonical session cookie jar. */
@Component
@ConditionalOnProperty(name = "ssuai.connector.lms-materials", havingValue = "real")
public class RealLmsMaterialsConnector implements LmsMaterialsConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLmsMaterialsConnector.class);

    private final LmsSsoProperties properties;
    private final ObjectMapper objectMapper;
    private final LmsMaterialSizeResolver sizeResolver;
    private final LmsSessionStore sessionStore;

    @Autowired
    public RealLmsMaterialsConnector(
            LmsSsoProperties properties,
            ObjectMapper objectMapper,
            LmsMaterialSizeResolver sizeResolver,
            LmsSessionStore sessionStore) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sizeResolver = sizeResolver;
        this.sessionStore = sessionStore;
    }

    /** Compatibility constructor used by isolated connector tests. */
    public RealLmsMaterialsConnector(
            LmsSsoProperties properties,
            ObjectMapper objectMapper,
            LmsMaterialSizeResolver sizeResolver) {
        this(properties, objectMapper, sizeResolver, null);
    }

    @Override
    public List<LmsCourse> fetchCourses(String studentId, LmsCookies cookies, long termId) {
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/learn_activities/courses?term_ids[]=" + termId;
        JsonNode root = http(cookies, url).getJson(url, true);
        List<LmsCourse> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                long id = node.path("id").asLong();
                if (id > 0) {
                    result.add(new LmsCourse(
                            id,
                            node.path("name").asText(""),
                            node.path("course_code").asText(""),
                            termId));
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<LmsMaterial> fetchMaterials(
            String studentId, LmsCookies cookies, LmsCourse course) {
        return fetchMaterials(
                studentId, cookies, course, new LmsMaterialEnrichmentBudget());
    }

    @Override
    public List<LmsMaterial> fetchMaterials(
            String studentId,
            LmsCookies cookies,
            LmsCourse course,
            LmsMaterialEnrichmentBudget enrichmentBudget) {
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/courses/" + course.id()
                + "/modules?include_detail=true";
        LmsHttpSession http = http(cookies, url);
        JsonNode root = http.getJson(url, true);
        List<LmsMaterial> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode moduleNode : root) {
                appendModuleMaterials(result, moduleNode, course);
            }
        }
        return correctUnreliableSizes(cookies, http, result, enrichmentBudget);
    }

    private static void appendModuleMaterials(
            List<LmsMaterial> destination, JsonNode moduleNode, LmsCourse course) {
        String weekTitle = moduleNode.path("title").asText("");
        JsonNode items = moduleNode.path("module_items");
        if (!items.isArray()) {
            return;
        }
        for (JsonNode itemNode : items) {
            JsonNode item = itemNode.path("content_data").path("item_content_data");
            if (item.isMissingNode() || item.isNull()) {
                continue;
            }
            String contentId = item.path("content_id").asText("");
            if (contentId.isBlank()) {
                continue;
            }
            String fileName = item.path("file_name").isTextual()
                    ? item.path("file_name").asText() : null;
            String extension = extension(fileName);
            JsonNode sizeNode = item.path("total_file_size");
            Long sizeBytes = sizeNode.isNumber() ? sizeNode.asLong() : null;
            destination.add(new LmsMaterial(
                    contentId,
                    course.id(),
                    course.name(),
                    fileName,
                    extension,
                    sizeBytes,
                    weekTitle,
                    item.path("title").asText(itemNode.path("title").asText("")),
                    item.path("content_type").asText(null)));
        }
    }

    @Override
    public Optional<ContentDownloadInfo> resolveDownload(
            LmsCookies cookies, String contentId) {
        String url = contentUrl(contentId);
        return resolveDownload(cookies, contentId, http(cookies, url));
    }

    private Optional<ContentDownloadInfo> resolveDownload(
            LmsCookies cookies,
            String contentId,
            LmsHttpSession http) {
        String body = http.getText(contentUrl(contentId), false);
        CommonsXmlParser.ParsedContent parsed = CommonsXmlParser.parse(body);
        if (parsed.downloadUri() == null || parsed.downloadUri().isBlank()) {
            return Optional.empty();
        }
        String absoluteUrl = absoluteDownloadUrl(parsed.downloadUri());
        return Optional.of(new ContentDownloadInfo(contentId, parsed.title(), absoluteUrl));
    }

    private String absoluteDownloadUrl(String downloadUri) {
        try {
            URI candidate = URI.create(downloadUri);
            URI base = URI.create(properties.getCommonsBaseUrl() + "/");
            URI resolved = candidate.isAbsolute() ? candidate : base.resolve(candidate);
            String scheme = resolved.getScheme();
            if (resolved.getHost() == null
                    || resolved.getUserInfo() != null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || !isConfiguredDownloadOrigin(resolved)) {
                throw new IllegalArgumentException("absolute HTTP(S) download URI is required");
            }
            return resolved.toString();
        } catch (IllegalArgumentException exception) {
            throw new ConnectorParseException(exception);
        }
    }

    private boolean isConfiguredDownloadOrigin(URI candidate) {
        return sameOrigin(candidate, URI.create(properties.getCommonsBaseUrl()))
                || sameOrigin(candidate, URI.create(properties.getCanvasBaseUrl()));
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private List<LmsMaterial> correctUnreliableSizes(
            LmsCookies cookies,
            LmsHttpSession http,
            List<LmsMaterial> materials,
            LmsMaterialEnrichmentBudget enrichmentBudget) {
        List<LmsMaterial> corrected = new ArrayList<>(materials.size());
        int candidates = 0;
        int metadataRequests = 0;
        int resolved = 0;
        int metadataFailures = 0;
        int metadataSkipped = 0;
        boolean enrichmentAvailable = enrichmentBudget.isAvailable();
        for (LmsMaterial material : materials) {
            if (hasReliableReportedSize(material)) {
                corrected.add(material);
                continue;
            }
            candidates++;
            Long resolvedSize = null;
            if (enrichmentAvailable) {
                metadataRequests++;
                try {
                    Optional<ContentDownloadInfo> download = resolveDownload(
                            cookies, material.contentId(), http);
                    if (download.isPresent()) {
                        OptionalLong contentLength = sizeResolver.resolve(
                                http.client(),
                                http.cookies(),
                                download.get().absoluteDownloadUrl(),
                                properties.getTimeout());
                        if (contentLength.isPresent()) {
                            resolvedSize = contentLength.getAsLong();
                            resolved++;
                        }
                    }
                } catch (ConnectorException | LmsApiException | LmsSessionExpiredException ignored) {
                    metadataFailures++;
                    enrichmentAvailable = false;
                    enrichmentBudget.disable();
                }
            } else {
                metadataSkipped++;
            }
            corrected.add(new LmsMaterial(
                    material.contentId(), material.courseId(), material.courseName(),
                    material.fileName(), material.extension(), resolvedSize,
                    material.weekTitle(), material.title(), material.contentType()));
        }
        if (metadataFailures > 0) {
            log.warn(
                    "LMS material size enrichment unavailable; preserving materials with unknown sizes "
                            + "requested={} resolved={} failures={} skipped={}",
                    metadataRequests,
                    resolved,
                    metadataFailures,
                    metadataSkipped);
        } else if (candidates > 0) {
            log.debug(
                    "LMS material size enrichment candidates={} requested={} resolved={} unknown={}",
                    candidates,
                    metadataRequests,
                    resolved,
                    candidates - resolved);
        }
        return List.copyOf(corrected);
    }

    @Override
    public void download(
            LmsCookies cookies, String absoluteDownloadUrl, OutputStream destination) {
        http(cookies, absoluteDownloadUrl).download(absoluteDownloadUrl, destination);
    }

    private LmsHttpSession http(LmsCookies cookies, String initialUrl) {
        LmsCookies latest = latest(cookies);
        return new LmsHttpSession(
                objectMapper, sessionStore, latest, properties.getTimeout(), initialUrl);
    }

    private LmsCookies latest(LmsCookies cookies) {
        if (sessionStore == null || cookies.sessionKey() == null || cookies.sessionKey().isBlank()) {
            return cookies;
        }
        return sessionStore.cookies(cookies.sessionKey())
                .orElseThrow(com.ssuai.global.exception.LmsSessionExpiredException::new);
    }

    private String contentUrl(String contentId) {
        return properties.getCommonsBaseUrl()
                + "/viewer/ssplayer/uniplayer_support/content.php?content_id="
                + URLEncoder.encode(contentId, StandardCharsets.UTF_8);
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 && lastDot < fileName.length() - 1
                ? fileName.substring(lastDot + 1).toLowerCase().trim()
                : null;
    }

    private static boolean hasReliableReportedSize(LmsMaterial material) {
        return "pdf".equalsIgnoreCase(material.contentType())
                && material.sizeBytes() != null
                && material.sizeBytes() > 0;
    }
}
