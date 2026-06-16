package com.ssuai.domain.lms.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsMaterial;

class RealLmsMaterialsConnectorTests {

    private MockWebServer server;
    private LmsSsoProperties properties;
    private RealLmsMaterialsConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        properties = new LmsSsoProperties();
        String baseUrl = server.url("/").toString();
        String baseWithoutSlash = baseUrl.substring(0, baseUrl.length() - 1);
        properties.setCanvasBaseUrl(baseWithoutSlash);
        properties.setCommonsBaseUrl(baseWithoutSlash);
        properties.setTimeout(Duration.ofSeconds(2));

        connector = new RealLmsMaterialsConnector(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchCourses_returnsCoursesFromLearningXEndpoint() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":1,\"name\":\"Course 1\",\"course_code\":\"C1\"}]"));

        List<LmsCourse> courses = connector.fetchCourses("student1", new LmsCookies("xn_api_token=tok;"), 10L);

        assertThat(courses).hasSize(1);
        assertThat(courses.get(0).id()).isEqualTo(1L);
        assertThat(courses.get(0).name()).isEqualTo("Course 1");

        okhttp3.mockwebserver.RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).startsWith("/learningx/api/v1/learn_activities/courses");
        assertThat(request.getPath()).contains("term_ids");
        assertThat(request.getPath()).contains("10");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok");
    }

    @Test
    void fetchMaterialsParsesCorrectly() throws Exception {
        // Enqueue modules list JSON
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        [
                          {
                            "title": "Week 1",
                            "module_items": [
                              {
                                "title": "Item 1",
                                "content_data": {
                                  "item_content_data": {
                                    "content_id": "c123",
                                    "content_type": "pdf",
                                    "file_name": "note.pdf",
                                    "title": "Note Title",
                                    "total_file_size": 500
                                  }
                                }
                              }
                            ]
                          }
                        ]
                        """));

        LmsCourse course = new LmsCourse(1L, "Course 1", "C1", 10L);
        List<LmsMaterial> materials = connector.fetchMaterials("student1", new LmsCookies("xn_api_token=tok;"), course);

        assertThat(materials).hasSize(1);
        assertThat(materials.get(0).contentId()).isEqualTo("c123");
        assertThat(materials.get(0).fileName()).isEqualTo("note.pdf");
        assertThat(materials.get(0).extension()).isEqualTo("pdf");
    }

    @Test
    void resolveDownloadUnescapesXmlAndPrefixesBaseUrl() throws Exception {
        // Enqueue XML with unescaped download URI
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("""
                        <content version="1.0">
                          <content_playing_info version="1.0">
                            <content_id>c123</content_id>
                            <content_type>sharedocs</content_type>
                            <content_download_uri>/index.php?module=download&amp;content_id=c123</content_download_uri>
                          </content_playing_info>
                          <content_metadata version="1.0">
                            <title><![CDATA[Slide PDF]]></title>
                          </content_metadata>
                        </content>
                        """));

        // Now the connector uses the configured base URL for prefixing
        Optional<ContentDownloadInfo> downloadOpt = connector.resolveDownload(new LmsCookies("xn_api_token=tok;"), "c123");

        assertThat(downloadOpt).isPresent();
        assertThat(downloadOpt.get().absoluteDownloadUrl())
                .isEqualTo(properties.getCommonsBaseUrl() + "/index.php?module=download&content_id=c123");
        assertThat(downloadOpt.get().title()).isEqualTo("Slide PDF");
    }

    @Test
    void downloadStreamsCorrectBytes() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("test-download-bytes"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String downloadUrl = server.url("/download").toString();

        connector.download(new LmsCookies("xn_api_token=tok;"), downloadUrl, out);

        assertThat(out.toString()).isEqualTo("test-download-bytes");
    }

    @Test
    void fetchCourses_throwsLmsApiExceptionOnServerError() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        assertThatThrownBy(() ->
                connector.fetchCourses("student1", new LmsCookies("xn_api_token=tok;"), 10L)
        ).isInstanceOf(LmsApiException.class)
         .hasMessageContaining("LearningX API error: status=500")
         .satisfies(e -> {
             LmsApiException ex = (LmsApiException) e;
             assertThat(ex.getStatusCode()).isEqualTo(500);
         });
    }

    @Test
    void fetchCourses_throwsLmsSessionExpiredOn401() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized"));

        assertThatThrownBy(() ->
                connector.fetchCourses("student1", new LmsCookies("xn_api_token=tok;"), 10L)
        ).isInstanceOf(LmsSessionExpiredException.class)
         .hasMessageContaining("LearningX returned 401");
    }

    @Test
    void fetchCourses_throwsLmsSessionExpiredOn403() {
        server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden"));

        assertThatThrownBy(() ->
                connector.fetchCourses("student1", new LmsCookies("xn_api_token=tok;"), 10L)
        ).isInstanceOf(LmsSessionExpiredException.class)
         .hasMessageContaining("LearningX returned 403");
    }

    @Test
    void fetchMaterials_throwsLmsApiExceptionOnServerError() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        LmsCourse course = new LmsCourse(1L, "Course 1", "C1", 10L);
        assertThatThrownBy(() ->
                connector.fetchMaterials("student1", new LmsCookies("xn_api_token=tok;"), course)
        ).isInstanceOf(LmsApiException.class)
         .hasMessageContaining("LearningX API error: status=500")
         .satisfies(e -> {
             LmsApiException ex = (LmsApiException) e;
             assertThat(ex.getStatusCode()).isEqualTo(500);
         });
    }

    @Test
    void fetchMaterials_throwsLmsSessionExpiredOn401() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized"));

        LmsCourse course = new LmsCourse(1L, "Course 1", "C1", 10L);
        assertThatThrownBy(() ->
                connector.fetchMaterials("student1", new LmsCookies("xn_api_token=tok;"), course)
        ).isInstanceOf(LmsSessionExpiredException.class)
         .hasMessageContaining("LearningX returned 401");
    }

    @Test
    void fetchMaterials_throwsLmsSessionExpiredOn403() {
        server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden"));

        LmsCourse course = new LmsCourse(1L, "Course 1", "C1", 10L);
        assertThatThrownBy(() ->
                connector.fetchMaterials("student1", new LmsCookies("xn_api_token=tok;"), course)
        ).isInstanceOf(LmsSessionExpiredException.class)
         .hasMessageContaining("LearningX returned 403");
    }
}
