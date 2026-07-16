package com.ssuai.domain.lms.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsMaterial;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LmsSessionExpiredException;

class RealLmsMaterialsConnectorTests {

    private MockWebServer server;
    private LmsSsoProperties properties;
    private LmsMaterialSizeResolver sizeResolver;
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

        sizeResolver = mock(LmsMaterialSizeResolver.class);
        connector = new RealLmsMaterialsConnector(properties, new ObjectMapper(), sizeResolver);
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
        assertThat(materials.get(0).sizeBytes()).isEqualTo(500L);
        verifyNoInteractions(sizeResolver);
    }

    @Test
    void fetchMaterialsCorrectsZeroFileSizeThroughHeadResolver() throws Exception {
        server.enqueue(materialsResponse("file", "archive.zip", 0));
        server.enqueue(downloadInfoResponse("c-zero"));
        LmsCookies cookies = new LmsCookies("xn_api_token=tok;");
        String downloadUrl = properties.getCommonsBaseUrl() + "/download?content_id=c-zero";
        when(sizeResolver.resolve(any(HttpClient.class), same(cookies), eq(downloadUrl), eq(properties.getTimeout())))
                .thenReturn(OptionalLong.of(9_876_543L));

        List<LmsMaterial> materials = connector.fetchMaterials(
                "student1", cookies, new LmsCourse(1L, "Course 1", "C1", 10L));

        assertThat(materials).singleElement()
                .extracting(LmsMaterial::sizeBytes)
                .isEqualTo(9_876_543L);
        verify(sizeResolver).resolve(
                any(HttpClient.class), same(cookies), eq(downloadUrl), eq(properties.getTimeout()));
        assertThat(server.takeRequest().getPath()).startsWith("/learningx/api/v1/courses/1/modules");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/viewer/ssplayer/uniplayer_support/content.php?content_id=c-zero");
    }

    @Test
    void fetchMaterialsDiscardsSentinelWhenHeadResolutionFails() {
        server.enqueue(materialsResponse("file", "archive.zip", 64_238));
        server.enqueue(downloadInfoResponse("c-zero"));
        LmsCookies cookies = new LmsCookies("xn_api_token=tok;");
        String downloadUrl = properties.getCommonsBaseUrl() + "/download?content_id=c-zero";
        when(sizeResolver.resolve(any(HttpClient.class), same(cookies), eq(downloadUrl), eq(properties.getTimeout())))
                .thenReturn(OptionalLong.empty());

        List<LmsMaterial> materials = connector.fetchMaterials(
                "student1", cookies, new LmsCourse(1L, "Course 1", "C1", 10L));

        assertThat(materials).singleElement()
                .extracting(LmsMaterial::sizeBytes)
                .isNull();
    }

    @Test
    void fetchMaterialsKeepsMaterialWhenOptionalCommonsMetadataIsHtml() {
        server.enqueue(materialsResponse("file", "archive.zip", 64_238));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>Unexpected upstream response</body></html>"));

        List<LmsMaterial> materials = connector.fetchMaterials(
                "student1",
                new LmsCookies("xn_api_token=tok;"),
                new LmsCourse(1L, "Course 1", "C1", 10L));

        assertThat(materials).singleElement().satisfies(material -> {
            assertThat(material.contentId()).isEqualTo("c-zero");
            assertThat(material.fileName()).isEqualTo("archive.zip");
            assertThat(material.sizeBytes()).isNull();
        });
        verifyNoInteractions(sizeResolver);
    }

    @Test
    void fetchMaterialsKeepsMaterialWhenOptionalCommonsXmlIsMalformed() {
        server.enqueue(materialsResponse("file", "archive.zip", 64_238));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("<content><broken></content>"));

        List<LmsMaterial> materials = connector.fetchMaterials(
                "student1",
                new LmsCookies("xn_api_token=tok;"),
                new LmsCourse(1L, "Course 1", "C1", 10L));

        assertThat(materials).singleElement().satisfies(material -> {
            assertThat(material.contentId()).isEqualTo("c-zero");
            assertThat(material.fileName()).isEqualTo("archive.zip");
            assertThat(material.sizeBytes()).isNull();
        });
        verifyNoInteractions(sizeResolver);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404})
    void fetchMaterialsKeepsMaterialWhenOptionalCommonsRequestIsRejected(int status) {
        server.enqueue(materialsResponse("file", "archive.zip", 64_238));
        server.enqueue(new MockResponse().setResponseCode(status));

        List<LmsMaterial> materials = connector.fetchMaterials(
                "student1",
                new LmsCookies("xn_api_token=tok;"),
                new LmsCourse(1L, "Course 1", "C1", 10L));

        assertThat(materials).singleElement().extracting(LmsMaterial::sizeBytes).isNull();
        verifyNoInteractions(sizeResolver);
    }

    @Test
    void fetchMaterialsStopsOptionalEnrichmentAfterFirstProviderFailure() {
        server.enqueue(twoUnreliableMaterialsResponse());
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("<error><message>Unexpected upstream response</message></error>"));

        List<LmsMaterial> materials = connector.fetchMaterials(
                "student1",
                new LmsCookies("xn_api_token=tok;"),
                new LmsCourse(1L, "Course 1", "C1", 10L));

        assertThat(materials).hasSize(2).allSatisfy(
                material -> assertThat(material.sizeBytes()).isNull());
        assertThat(server.getRequestCount()).isEqualTo(2);
        verifyNoInteractions(sizeResolver);
    }

    @Test
    void fetchMaterialsSharesProviderFailureBudgetAcrossCoursesInOneRequest() {
        LmsMaterialEnrichmentBudget budget = new LmsMaterialEnrichmentBudget();
        LmsCookies cookies = new LmsCookies("xn_api_token=tok;");
        server.enqueue(materialsResponse("file", "archive-1.zip", 64_238));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>Unexpected upstream response</body></html>"));

        List<LmsMaterial> first = connector.fetchMaterials(
                "student1",
                cookies,
                new LmsCourse(1L, "Course 1", "C1", 10L),
                budget);

        server.enqueue(materialsResponse("file", "archive-2.zip", 64_238));
        List<LmsMaterial> second = connector.fetchMaterials(
                "student1",
                cookies,
                new LmsCourse(2L, "Course 2", "C2", 10L),
                budget);

        assertThat(first).singleElement().extracting(LmsMaterial::sizeBytes).isNull();
        assertThat(second).singleElement().extracting(LmsMaterial::sizeBytes).isNull();
        assertThat(server.getRequestCount()).isEqualTo(3);
        verifyNoInteractions(sizeResolver);
    }

    private MockResponse materialsResponse(String contentType, String fileName, long sizeBytes) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        [{
                          "title": "Week 1",
                          "module_items": [{
                            "title": "Archive",
                            "content_data": {
                              "item_content_data": {
                                "content_id": "c-zero",
                                "content_type": "%s",
                                "file_name": "%s",
                                "title": "Archive",
                                "total_file_size": %d
                              }
                            }
                          }]
                        }]
                        """.formatted(contentType, fileName, sizeBytes));
    }

    private MockResponse downloadInfoResponse(String contentId) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("""
                        <content version="1.0">
                          <content_playing_info version="1.0">
                            <content_id>%s</content_id>
                            <content_download_uri>/download?content_id=%s</content_download_uri>
                          </content_playing_info>
                          <content_metadata version="1.0"><title>Archive</title></content_metadata>
                        </content>
                        """.formatted(contentId, contentId));
    }

    private MockResponse twoUnreliableMaterialsResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        [{
                          "title": "Week 1",
                          "module_items": [
                            {
                              "title": "Archive 1",
                              "content_data": {"item_content_data": {
                                "content_id": "c-zero",
                                "content_type": "file",
                                "file_name": "archive-1.zip",
                                "title": "Archive 1",
                                "total_file_size": 64238
                              }}
                            },
                            {
                              "title": "Archive 2",
                              "content_data": {"item_content_data": {
                                "content_id": "c-second",
                                "content_type": "file",
                                "file_name": "archive-2.zip",
                                "title": "Archive 2",
                                "total_file_size": 64238
                              }}
                            }
                          ]
                        }]
                        """);
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
    void resolveDownloadPreservesAbsoluteAttachmentUrl() {
        String absoluteAttachmentUrl = properties.getCanvasBaseUrl()
                + "/files/42/download?download_frd=1";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("""
                        <content version="1.0">
                          <content_playing_info version="1.0">
                            <content_id>archive-1</content_id>
                            <content_type>file</content_type>
                            <content_download_uri>%s</content_download_uri>
                          </content_playing_info>
                          <content_metadata version="1.0"><title>Lab archive</title></content_metadata>
                        </content>
                        """.formatted(absoluteAttachmentUrl.replace("&", "&amp;"))));

        Optional<ContentDownloadInfo> download = connector.resolveDownload(
                new LmsCookies("xn_api_token=tok;"), "archive-1");

        assertThat(download).isPresent();
        assertThat(download.orElseThrow().absoluteDownloadUrl())
                .isEqualTo(absoluteAttachmentUrl);
    }

    @Test
    void resolveDownloadRejectsAbsoluteUrlOutsideConfiguredOrigins() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("""
                        <content version="1.0">
                          <content_playing_info version="1.0">
                            <content_id>archive-1</content_id>
                            <content_type>file</content_type>
                            <content_download_uri>http://127.0.0.1:8080/internal</content_download_uri>
                          </content_playing_info>
                        </content>
                        """));

        assertThatThrownBy(() -> connector.resolveDownload(
                new LmsCookies("xn_api_token=tok;"), "archive-1"))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void resolveDownloadStillRejectsHtmlForActualDownloadFlow() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>Unexpected upstream response</body></html>"));

        assertThatThrownBy(() -> connector.resolveDownload(
                new LmsCookies("xn_api_token=tok;"), "c123"))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void resolveDownloadRejectsMalformedXmlForActualDownloadFlow() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("<content><broken></content>"));

        assertThatThrownBy(() -> connector.resolveDownload(
                new LmsCookies("xn_api_token=tok;"), "c123"))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void resolveDownloadRejectsWellFormedNonCommonsXmlForActualDownloadFlow() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("<error><message>Unexpected upstream response</message></error>"));

        assertThatThrownBy(() -> connector.resolveDownload(
                new LmsCookies("xn_api_token=tok;"), "c123"))
                .isInstanceOf(ConnectorParseException.class);
    }

    @Test
    void resolveDownloadTreatsValidXmlWithoutCapabilityAsUnavailable() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody("<content><content_metadata><title>Unsupported</title>"
                        + "</content_metadata></content>"));

        Optional<ContentDownloadInfo> result = connector.resolveDownload(
                new LmsCookies("xn_api_token=tok;"), "c123");

        assertThat(result).isEmpty();
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
    void fetchCoursesRetriesThenClassifiesServerErrorAsUnavailable() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setBody("Service Unavailable"));

        assertThatThrownBy(() ->
                connector.fetchCourses("student1", new LmsCookies("xn_api_token=tok;"), 10L)
        ).isInstanceOf(ConnectorUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void fetchCourses_throwsLmsSessionExpiredOn401() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized"));

        assertThatThrownBy(() ->
                connector.fetchCourses("student1", new LmsCookies("xn_api_token=tok;"), 10L)
        ).isInstanceOf(LmsSessionExpiredException.class)
         .hasMessageContaining("authentication was rejected");
    }

    @Test
    void fetchCourses_throwsLmsSessionExpiredOn403() {
        server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden"));

        assertThatThrownBy(() ->
                connector.fetchCourses("student1", new LmsCookies("xn_api_token=tok;"), 10L)
        ).isInstanceOf(LmsSessionExpiredException.class)
         .hasMessageContaining("authentication was rejected");
    }

    @Test
    void fetchMaterialsRetriesThenClassifiesServerErrorAsUnavailable() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setBody("Service Unavailable"));

        LmsCourse course = new LmsCourse(1L, "Course 1", "C1", 10L);
        assertThatThrownBy(() ->
                connector.fetchMaterials("student1", new LmsCookies("xn_api_token=tok;"), course)
        ).isInstanceOf(ConnectorUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(2);
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
         .hasMessageContaining("authentication was rejected");
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
         .hasMessageContaining("authentication was rejected");
    }
}
