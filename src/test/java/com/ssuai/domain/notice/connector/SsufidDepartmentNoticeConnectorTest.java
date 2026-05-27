package com.ssuai.domain.notice.connector;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.notice.dto.NoticeListResponse;

class SsufidDepartmentNoticeConnectorTest {

    private WireMockServer wireMockServer;
    private SsufidDepartmentNoticeConnector connector;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());

        String baseUrl = "http://localhost:" + wireMockServer.port();
        connector = new SsufidDepartmentNoticeConnector(baseUrl, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void fetchByDepartmentSuccessfullyFetchesAndMaps() throws IOException {
        String json = loadFixture("fixtures/notice/ssufid_cse_bachelor.json");

        // stubbing for three slugs of 컴퓨터학부
        stubFor(get(urlEqualTo("/cse.ssu.ac.kr/bachelor/data.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        stubFor(get(urlEqualTo("/cse.ssu.ac.kr/graduate/data.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"title\": \"대학원\", \"items\": []}")));

        stubFor(get(urlEqualTo("/cse.ssu.ac.kr/employment/data.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"title\": \"취업\", \"items\": []}")));

        NoticeListResponse response = connector.fetchByDepartment("컴퓨터학부", 1);

        // We added 3 items in the fixture
        assertThat(response.items()).hasSize(3);
        // Sorted by created_at DESC, so ID: 124 is first, then 123, then 122
        assertThat(response.items().get(0).title()).contains("장학금 신청 안내");
        assertThat(response.items().get(0).date()).isEqualTo("2026.05.12");
        assertThat(response.items().get(0).category()).isEqualTo("장학");

        assertThat(response.items().get(1).title()).contains("수강신청 안내");
        assertThat(response.items().get(1).date()).isEqualTo("2026.05.10");

        assertThat(response.items().get(2).title()).contains("예비군 훈련 안내");
        assertThat(response.items().get(2).date()).isEqualTo("2026.05.08");
    }

    @Test
    void listDepartmentsReturnsSortedList() {
        assertThat(connector.listDepartments()).contains("컴퓨터학부", "소프트웨어학부");
    }

    @Test
    void fetchByDepartmentWithUnsupportedNameReturnsEmpty() {
        NoticeListResponse response = connector.fetchByDepartment("미지원학과", 1);
        assertThat(response.items()).isEmpty();
        assertThat(response.totalPages()).isEqualTo(1);
    }

    private String loadFixture(String resourcePath) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("fixture not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
