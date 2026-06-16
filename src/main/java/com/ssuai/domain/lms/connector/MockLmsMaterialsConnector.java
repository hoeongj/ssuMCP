package com.ssuai.domain.lms.connector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsMaterial;

@Component
@ConditionalOnProperty(name = "ssuai.connector.lms-materials", havingValue = "mock", matchIfMissing = true)
public class MockLmsMaterialsConnector implements LmsMaterialsConnector {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<LmsCourse> fetchCourses(String studentId, LmsCookies cookies, long termId) {
        return List.of(
                new LmsCourse(1L, "빅데이터 분석", "CSE101", termId),
                new LmsCourse(2L, "기초 통계학", "MATH201", termId)
        );
    }

    @Override
    public List<LmsMaterial> fetchMaterials(String studentId, LmsCookies cookies, LmsCourse course) {
        JsonNode root = loadFixture("fixtures/lms/materials.json");
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
                        Long sizeBytes = itemContentData.path("total_file_size").isNull() ? null : itemContentData.path("total_file_size").asLong();

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
        return list;
    }

    @Override
    public Optional<ContentDownloadInfo> resolveDownload(LmsCookies cookies, String contentId) {
        return Optional.of(new ContentDownloadInfo(
                contentId,
                "Mock Title " + contentId,
                "https://mock.example/download/" + contentId
        ));
    }

    @Override
    public void download(LmsCookies cookies, String absoluteDownloadUrl, OutputStream destination) {
        try {
            String content = "mock content for " + absoluteDownloadUrl;
            destination.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Mock download failed", e);
        }
    }

    private JsonNode loadFixture(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found on classpath: " + path);
            }
            return objectMapper.readTree(in);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load fixture: " + path, exception);
        }
    }
}
