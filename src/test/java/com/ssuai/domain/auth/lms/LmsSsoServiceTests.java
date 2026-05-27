package com.ssuai.domain.auth.lms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.global.exception.LmsAuthFailedException;

class LmsSsoServiceTests {

    private MockWebServer lmsServer;
    private MockWebServer canvasServer;
    private LmsSessionStore sessionStore;
    private LmsSsoService service;

    @BeforeEach
    void setUp() throws IOException {
        lmsServer = new MockWebServer();
        lmsServer.start();
        canvasServer = new MockWebServer();
        canvasServer.start();

        LmsSsoProperties properties = new LmsSsoProperties();
        properties.setGwCallbackUrl(lmsServer.url("/xn-sso/gw-cb.php").toString());
        String canvasBaseUrl = canvasServer.url("/").toString();
        properties.setCanvasBaseUrl(canvasBaseUrl.substring(0, canvasBaseUrl.length() - 1));
        properties.setTimeout(Duration.ofSeconds(2));

        LmsSessionProperties sessionProperties = new LmsSessionProperties();
        sessionProperties.setEncryptionKey("");
        sessionStore = new LmsSessionStore(
                sessionProperties,
                Clock.fixed(Instant.parse("2026-05-19T00:00:00Z"), ZoneOffset.UTC),
                new SecureRandom());
        service = new LmsSsoService(properties, sessionStore);
    }

    @AfterEach
    void tearDown() throws IOException {
        lmsServer.shutdown();
        canvasServer.shutdown();
    }

    @Test
    void canvasDashboardRedirectChainAccumulatesIntermediateSetCookies() throws Exception {
        lmsServer.enqueue(new MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "LMSSESSION=lms-one; Path=/; HttpOnly"));
        canvasServer.enqueue(new MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/canvas/session")
                .addHeader("Set-Cookie", "_normandy_session=normandy-one; Path=/; HttpOnly"));
        canvasServer.enqueue(new MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/canvas/final")
                .addHeader("Set-Cookie", "xn_api_token=token-one; Path=/; HttpOnly"));
        canvasServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "_legacy_normandy_session=legacy-one; Path=/; HttpOnly"));

        service.authenticate("sso-token", "20231234");

        RecordedRequest dashboard = canvasServer.takeRequest();
        assertThat(dashboard.getPath()).startsWith("/learningx/dashboard?user_login=20231234");
        assertThat(dashboard.getHeader("Cookie")).contains("LMSSESSION=lms-one");

        RecordedRequest secondHop = canvasServer.takeRequest();
        assertThat(secondHop.getPath()).isEqualTo("/canvas/session");
        assertThat(secondHop.getHeader("Cookie"))
                .contains("LMSSESSION=lms-one")
                .contains("_normandy_session=normandy-one");

        RecordedRequest finalHop = canvasServer.takeRequest();
        assertThat(finalHop.getPath()).isEqualTo("/canvas/final");
        assertThat(finalHop.getHeader("Cookie"))
                .contains("_normandy_session=normandy-one")
                .contains("xn_api_token=token-one");

        assertThat(sessionStore.cookies("20231234"))
                .hasValueSatisfying(cookies -> assertThat(cookies.rawCookieHeader())
                        .contains("LMSSESSION=lms-one")
                        .contains("_normandy_session=normandy-one")
                        .contains("xn_api_token=token-one")
                        .contains("_legacy_normandy_session=legacy-one"));
    }

    @Test
    void gwCbLocationIsFollowedAsCanvasAuthStartUrl() throws Exception {
        String authCallbackPath = "/learningx/auth/callback?token=one-time-auth-token";
        lmsServer.enqueue(new MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "WAF=waf-val; Path=/; HttpOnly")
                .addHeader("Location", canvasServer.url(authCallbackPath).toString()));

        canvasServer.enqueue(new MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/learningx/dashboard")
                .addHeader("Set-Cookie", "xn_api_token=api-token-val; Path=/; HttpOnly"));
        canvasServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "_normandy_session=normandy-val; Path=/; HttpOnly"));

        service.authenticate("sso-token", "20231234");

        lmsServer.takeRequest();

        RecordedRequest authCallbackReq = canvasServer.takeRequest();
        assertThat(authCallbackReq.getPath()).startsWith(authCallbackPath);
        assertThat(authCallbackReq.getHeader("Cookie")).contains("WAF=waf-val");

        assertThat(sessionStore.cookies("20231234"))
                .hasValueSatisfying(cookies -> assertThat(cookies.rawCookieHeader())
                        .contains("WAF=waf-val")
                        .contains("xn_api_token=api-token-val")
                        .contains("_normandy_session=normandy-val"));
    }

    @Test
    void phase3FromCcIssuesXnApiToken() throws Exception {
        lmsServer.enqueue(new MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "WAF=waf-val; Path=/; HttpOnly")
                .addHeader("Location", lmsServer.url("/login/callback?result=FAKE").toString()));
        lmsServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "xn_coursecatalog_api_token=lms-token; Path=/; HttpOnly"));

        canvasServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "xn_api_token=canvas-token; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "_normandy_session=norm-val; Path=/; HttpOnly"));

        service.authenticate("sso-token", "20231234");

        lmsServer.takeRequest();
        lmsServer.takeRequest();

        RecordedRequest fromCc = canvasServer.takeRequest();
        assertThat(fromCc.getPath()).isEqualTo("/learningx/login/from_cc?result=FAKE");
        assertThat(fromCc.getHeader("Cookie"))
                .contains("WAF=waf-val")
                .contains("xn_coursecatalog_api_token=lms-token");

        assertThat(sessionStore.cookies("20231234"))
                .hasValueSatisfying(cookies -> assertThat(cookies.rawCookieHeader())
                        .contains("WAF=waf-val")
                        .contains("xn_coursecatalog_api_token=lms-token")
                        .contains("xn_api_token=canvas-token")
                        .contains("_normandy_session=norm-val"));
    }

    @Test
    void missingXnApiTokenThrowsLmsAuthFailed() throws Exception {
        lmsServer.enqueue(new MockResponse()
                .setResponseCode(302)
                .addHeader("Location", lmsServer.url("/login/callback").toString()));
        lmsServer.enqueue(new MockResponse().setResponseCode(200));
        canvasServer.enqueue(new MockResponse().setResponseCode(200));

        assertThatThrownBy(() -> service.authenticate("sso-token", "20231234"))
                .isInstanceOf(LmsAuthFailedException.class)
                .hasMessageContaining("xn_api_token");
        assertThat(sessionStore.cookies("20231234")).isEmpty();
    }
}
