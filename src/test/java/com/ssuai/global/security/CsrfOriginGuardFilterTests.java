package com.ssuai.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CsrfOriginGuardFilterTests {

    private static final String ALLOWED = "https://ssuai.vercel.app";

    private final CsrfOriginGuardFilter filter =
            new CsrfOriginGuardFilter(Set.of(ALLOWED), new ObjectMapper());

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    // --- covered endpoint: Origin decision ---------------------------------

    @Test
    void allowedOriginOnCoveredPostPasses() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/refresh");
        request.addHeader(HttpHeaders.ORIGIN, ALLOWED);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void foreignOriginOnCoveredPostIsForbidden() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/refresh");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull(); // chain not invoked
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains("CSRF_ORIGIN_NOT_ALLOWED");
    }

    // --- covered endpoint: neither Origin nor Referer ----------------------

    @Test
    void noOriginNoRefererOnCoveredPostPasses() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // --- covered endpoint: Referer fallback (no Origin) --------------------

    @Test
    void matchingRefererWithoutOriginPasses() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/chat");
        // Path + no explicit port must normalize to the allowed origin.
        request.addHeader(HttpHeaders.REFERER, ALLOWED + "/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void foreignRefererWithoutOriginIsForbidden() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/chat");
        request.addHeader(HttpHeaders.REFERER, "https://evil.example/attack");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void originTakesPrecedenceOverReferer() throws Exception {
        // Allowed Origin + foreign Referer → Origin wins → pass.
        MockHttpServletRequest request = request("POST", "/api/chat");
        request.addHeader(HttpHeaders.ORIGIN, ALLOWED);
        request.addHeader(HttpHeaders.REFERER, "https://evil.example/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // --- method exclusion ---------------------------------------------------

    @Test
    void getRequestIsNotFiltered() {
        MockHttpServletRequest request = request("GET", "/api/auth/me");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    // --- path exclusion: identity-provider callbacks ------------------------

    @Test
    void mcpAuthLibraryCallbackIsExcludedEvenWithForeignOrigin() {
        MockHttpServletRequest request = request("POST", "/api/mcp/auth/library/callback");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void mcpAuthWebSessionIsExcluded() {
        MockHttpServletRequest request = request("POST", "/api/mcp/auth/web-session");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void coveredApiPathIsNotExcluded() {
        MockHttpServletRequest request = request("POST", "/api/library/reservations/confirm");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    // --- origin normalization ----------------------------------------------

    @Test
    void toOriginStripsPathAndKeepsNoPortWhenAbsent() {
        assertThat(CsrfOriginGuardFilter.toOrigin("https://ssuai.vercel.app/dashboard"))
                .isEqualTo("https://ssuai.vercel.app");
    }

    @Test
    void toOriginKeepsExplicitPort() {
        assertThat(CsrfOriginGuardFilter.toOrigin("http://localhost:3000/auth/return"))
                .isEqualTo("http://localhost:3000");
    }

    @Test
    void toOriginReturnsNullForMalformedOrSchemeless() {
        assertThat(CsrfOriginGuardFilter.toOrigin("not a url")).isNull();
        assertThat(CsrfOriginGuardFilter.toOrigin("/relative/path")).isNull();
    }
}
