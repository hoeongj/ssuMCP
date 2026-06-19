package com.ssuai.domain.lms.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.lms.LmsCookies;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class HeadLmsMaterialSizeResolverTests {

    private MockWebServer server;
    private HeadLmsMaterialSizeResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        resolver = new HeadLmsMaterialSizeResolver();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void resolveReturnsContentLengthFromAuthenticatedHead() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "9876543"));
        LmsCookies cookies = new LmsCookies("xn_api_token=tok; canvas_session=session;");

        OptionalLong size = resolver.resolve(
                HttpClient.newHttpClient(),
                cookies,
                server.url("/download").toString(),
                Duration.ofSeconds(2));

        assertThat(size).hasValue(9_876_543L);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("HEAD");
        assertThat(request.getHeader("Cookie")).isEqualTo(cookies.rawCookieHeader());
    }

    @Test
    void resolveReturnsEmptyOnHeadFailure() {
        server.enqueue(new MockResponse().setResponseCode(500));

        OptionalLong size = resolver.resolve(
                HttpClient.newHttpClient(),
                new LmsCookies("xn_api_token=tok;"),
                server.url("/download").toString(),
                Duration.ofSeconds(2));

        assertThat(size).isEmpty();
    }
}
