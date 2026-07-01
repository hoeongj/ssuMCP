package com.ssuai.domain.notice.connector;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;

/**
 * Redirect re-validation of the SSRF host allowlist (security follow-up #13 residual):
 * an allowlisted host must not be able to 302 the fetch to an off-allowlist target.
 * The connector is constructed with a test allowlist that only allows the local
 * WireMock host, so the same gate logic is exercised against local stubs.
 */
class RealNoticeConnectorRedirectTests {

    private static final String DETAIL_HTML = """
            <html><body>
            <div class="bg-white p-4 mb-5"><h1>장학금 신청 안내</h1></div>
            <div class="bg-white"><hr><div>본문 내용입니다.</div></div>
            </body></html>
            """;

    private WireMockServer wireMockServer;
    private RealNoticeConnector connector;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());

        baseUrl = "http://localhost:" + wireMockServer.port();
        connector = new RealNoticeConnector(
                new NoticeConnectorProperties(), new NoticeHostAllowlist("localhost"));
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void fetchDetailRejectsRedirectToOffAllowlistHost() {
        stubFor(get(urlEqualTo("/notice"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "https://evil.example.com/pwn")));

        assertThatThrownBy(() -> connector.fetchDetail(baseUrl + "/notice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않은 공지 출처");
        // the off-allowlist target was rejected BEFORE any fetch — only the first hop was served
        assertThat(wireMockServer.getServeEvents().getRequests()).hasSize(1);
    }

    @Test
    void fetchDetailFollowsRedirectChainWithinAllowlist() {
        // relative Location exercises resolution against the current hop URL
        stubFor(get(urlEqualTo("/moved"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "/final")));
        stubFor(get(urlEqualTo("/final"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html; charset=utf-8")
                        .withBody(DETAIL_HTML)));

        NoticeDetailResponse response = connector.fetchDetail(baseUrl + "/moved");

        assertThat(response.title()).contains("장학금 신청 안내");
        assertThat(response.bodyText()).contains("본문 내용입니다");
    }

    @Test
    void fetchDetailRejectsRedirectLoopBeyondMaxHops() {
        stubFor(get(urlEqualTo("/loop"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "/loop")));

        assertThatThrownBy(() -> connector.fetchDetail(baseUrl + "/loop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("리다이렉트가 너무 많습니다");
        // initial request + at most 5 followed hops
        assertThat(wireMockServer.getServeEvents().getRequests()).hasSize(6);
    }
}
