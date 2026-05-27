package com.ssuai.domain.auth.lms;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ssuai.global.exception.LmsAuthFailedException;

/**
 * LMS auth after SmartID SSO.
 *
 * <p>Phase 1 — GET {@code lms.ssu.ac.kr/xn-sso/gw-cb.php?sToken=&sIdno=}
 * with the one-shot SmartID tokens. gw-cb.php validates with SmartID,
 * issues LMS session cookies, and 302-redirects to the Canvas auth callback.
 * We do NOT auto-follow the redirect so the 302's Set-Cookie and Location
 * headers are captured.
 *
 * <p>Phase 2 — GET the gw-cb.php Location when present, with a dashboard URL
 * fallback for older flows.
 *
 * <p>Phase 3 — visit {@code canvas.ssu.ac.kr/learningx/login/from_cc?result=}
 * with the SmartID result value captured from phase 1. Canvas issues its own
 * session cookies including {@code xn_api_token} (JWT, 2h TTL),
 * {@code _legacy_normandy_session}, and {@code _normandy_session}. These are
 * the auth credentials the {@code RealLmsAssignmentsConnector} sends to the
 * Canvas API.
 *
 * <p>Both sets of cookies are merged and stored encrypted in
 * {@link LmsSessionStore} keyed by {@code sIdno} (= ssuAI studentId).
 * The TTL is bound by the shorter of the two cookie lifetimes; the
 * store default of 2h matches the {@code xn_api_token} JWT expiry.
 */
@Service
public class LmsSsoService {

    private static final Logger log = LoggerFactory.getLogger(LmsSsoService.class);

    private final LmsSsoProperties properties;
    private final LmsSessionStore sessionStore;

    public LmsSsoService(LmsSsoProperties properties, LmsSessionStore sessionStore) {
        this.properties = properties;
        this.sessionStore = sessionStore;
    }

    public void authenticate(String sToken, String sIdno) {
        if (sToken == null || sToken.isBlank()) {
            throw new LmsAuthFailedException("sToken is required");
        }
        if (sIdno == null || sIdno.isBlank()) {
            throw new LmsAuthFailedException("sIdno is required");
        }

        // Thread-safe isolated CookieManager & HttpClient for this session
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        HttpClient sessionClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getTimeout())
                .build();

        // Phase 1: gw-cb.php → lms session cookies
        String location = callGwCallback(sessionClient, sToken, sIdno);
        log.info("lms auth phase1 cookies: {}", getCookieNames(cookieManager));
        log.info("lms auth phase1 redirect: {}",
                location != null ? sanitizeRedirectLocation(location) : "(none)");

        String canvasStartUrl = location != null && !location.isBlank()
                ? location
                : properties.getCanvasBaseUrl() + "/learningx/dashboard?user_login="
                        + URLEncoder.encode(sIdno.trim(), StandardCharsets.UTF_8);

        // Phase 2: follow gw-cb.php redirect or fallback dashboard.
        fetchCanvasDashboard(sessionClient, canvasStartUrl);
        log.info("lms auth phase2 cookies: {}", getCookieNames(cookieManager));

        // Phase 3: the LearningX from_cc endpoint issues xn_api_token in the live browser flow.
        String resultParam = extractResultParam(location);
        if (resultParam != null && !resultParam.isBlank()) {
            String fromCcUrl = properties.getCanvasBaseUrl()
                    + "/learningx/login/from_cc?result="
                    + URLEncoder.encode(resultParam, StandardCharsets.UTF_8);
            fetchCanvasDashboard(sessionClient, fromCcUrl);
            log.info("lms auth phase3 from_cc cookies: {}", getCookieNames(cookieManager));
        } else {
            log.warn("lms auth phase1 missing result param; from_cc skipped");
        }

        // Phase 4: diagnostic fallback for older flows that still issue the token from dashboard.
        if (!hasCookie(cookieManager, "xn_api_token")) {
            String canvasDashboardUrl = properties.getCanvasBaseUrl()
                    + "/learningx/dashboard?user_login="
                    + URLEncoder.encode(sIdno.trim(), StandardCharsets.UTF_8);
            fetchCanvasDashboard(sessionClient, canvasDashboardUrl);
        }
        log.info("lms auth final cookies: {}", getCookieNames(cookieManager));

        if (!hasCookie(cookieManager, "xn_api_token")) {
            log.warn("lms auth missing xn_api_token: cookies={}", getCookieNames(cookieManager));
            throw new LmsAuthFailedException("xn_api_token not issued");
        }

        String allCookiesHeader = serializeCookies(cookieManager);
        sessionStore.put(sIdno.trim(), new LmsCookies(allCookiesHeader));
    }

    private String callGwCallback(HttpClient sessionClient, String sToken, String sIdno) {
        String url = properties.getGwCallbackUrl()
                + "?sToken=" + URLEncoder.encode(sToken, StandardCharsets.UTF_8)
                + "&sIdno=" + URLEncoder.encode(sIdno, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Cookie", "sToken=" + sToken + "; sIdno=" + sIdno)
                .timeout(properties.getTimeout())
                .GET()
                .build();
        try {
            HttpResponse<Void> response = sessionClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.headers().firstValue("location")
                    .filter(value -> !value.isBlank())
                    .map(value -> URI.create(url).resolve(value).toString())
                    .orElse(null);
        } catch (IOException exception) {
            throw new LmsAuthFailedException("gw-cb.php io error", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LmsAuthFailedException("gw-cb.php interrupted", exception);
        }
    }

    private void fetchCanvasDashboard(HttpClient sessionClient, String startUrl) {
        String url = startUrl;
        for (int hop = 0; hop <= 10; hop++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Referer", "https://lms.ssu.ac.kr/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .timeout(properties.getTimeout())
                    .GET()
                    .build();
            try {
                HttpResponse<Void> response = sessionClient.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return;
                }
                if (status / 100 == 3) {
                    String location = response.headers().firstValue("location").orElse(null);
                    if (location == null || location.isBlank()) {
                        return;
                    }
                    url = URI.create(url).resolve(location).toString();
                    continue;
                }
                return;
            } catch (IOException exception) {
                throw new LmsAuthFailedException("canvas dashboard io error", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LmsAuthFailedException("canvas dashboard interrupted", exception);
            }
        }
    }

    private static String getCookieNames(CookieManager cookieManager) {
        return cookieManager.getCookieStore().getCookies().stream()
                .map(HttpCookie::getName)
                .collect(Collectors.joining(","));
    }

    private static boolean hasCookie(CookieManager cookieManager, String name) {
        return cookieManager.getCookieStore().getCookies().stream()
                .anyMatch(cookie -> cookie.getName().equals(name));
    }

    private static String extractResultParam(String redirectLocation) {
        if (redirectLocation == null || redirectLocation.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(redirectLocation);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "result".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String serializeCookies(CookieManager cookieManager) {
        return cookieManager.getCookieStore().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
    }

    private static String sanitizeRedirectLocation(String location) {
        return location.replaceAll("(?i)(token|sToken|result)=[^&]*", "$1=REDACTED");
    }
}

