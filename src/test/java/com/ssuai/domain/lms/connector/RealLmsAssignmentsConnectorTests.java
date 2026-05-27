package com.ssuai.domain.lms.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.global.exception.LmsSessionExpiredException;

class RealLmsAssignmentsConnectorTests {

    private MockWebServer canvasServer;
    private RealLmsAssignmentsConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        canvasServer = new MockWebServer();
        canvasServer.start();

        LmsSsoProperties properties = new LmsSsoProperties();
        String canvasBaseUrl = canvasServer.url("/").toString();
        properties.setCanvasBaseUrl(canvasBaseUrl.substring(0, canvasBaseUrl.length() - 1));
        properties.setTimeout(Duration.ofSeconds(2));

        connector = new RealLmsAssignmentsConnector(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        canvasServer.shutdown();
    }

    @Test
    void getJsonSendsAuthorizationBearerFromCookies() throws Exception {
        canvasServer.enqueue(jsonOk("""
                {
                  "enrollment_terms": [
                    {"id": 41, "name": "Old", "default": false},
                    {"id": 42, "name": "Current", "default": true}
                  ]
                }
                """));
        canvasServer.enqueue(jsonOk("[{\"id\":10001,\"name\":\"Data Structures\"}]"));
        canvasServer.enqueue(jsonOk("""
                {
                  "to_dos": [
                    {
                      "course_id": 10001,
                      "todo_list": [
                        {
                          "component_type": "assignment",
                          "title": "Project 1",
                          "due_date": null
                        }
                      ]
                    }
                  ]
                }
                """));

        AssignmentsResponse response = connector.fetchAssignments("20221528",
                new LmsCookies("WAF=w; xn_api_token=jwt-xyz; _normandy_session=ns"));

        assertThat(response.termId()).isEqualTo(42L);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).courseName()).isEqualTo("Data Structures");
        assertThat(response.items().get(0).title()).isEqualTo("Project 1");

        RecordedRequest terms = canvasServer.takeRequest();
        RecordedRequest courses = canvasServer.takeRequest();
        RecordedRequest todos = canvasServer.takeRequest();
        assertThat(terms.getHeader("Authorization")).isEqualTo("Bearer jwt-xyz");
        assertThat(courses.getHeader("Authorization")).isEqualTo("Bearer jwt-xyz");
        assertThat(todos.getHeader("Authorization")).isEqualTo("Bearer jwt-xyz");
        assertThat(terms.getHeader("Referer")).isEqualTo(canvasBaseUrl() + "/");
    }

    @Test
    void fetchAssignmentsFallsBackToFirstTermWhenNoDefaultTerm() {
        canvasServer.enqueue(jsonOk("""
                {
                  "enrollment_terms": [
                    {"id": 43, "name": "First", "default": false},
                    {"id": 42, "name": "Previous", "default": false}
                  ]
                }
                """));
        canvasServer.enqueue(jsonOk("[]"));
        canvasServer.enqueue(jsonOk("{\"to_dos\":[]}"));

        AssignmentsResponse response = connector.fetchAssignments("20221528",
                new LmsCookies("WAF=w; xn_api_token=jwt-xyz; _normandy_session=ns"));

        assertThat(response.termId()).isEqualTo(43L);
        assertThat(response.items()).isEmpty();
    }

    @Test
    void missingXnApiTokenThrowsSessionExpired() {
        LmsCookies cookies = new LmsCookies("WAF=w; xn_coursecatalog_api_token=cc");

        assertThatThrownBy(() -> connector.fetchAssignments("20221528", cookies))
                .isInstanceOf(LmsSessionExpiredException.class)
                .hasMessageContaining("xn_api_token");
        assertThat(canvasServer.getRequestCount()).isZero();
    }

    private static MockResponse jsonOk(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private String canvasBaseUrl() {
        String url = canvasServer.url("/").toString();
        return url.substring(0, url.length() - 1);
    }
}
