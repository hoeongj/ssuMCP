package com.ssuai.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthFilterTests {

    private JwtProvider jwtProvider;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtProvider = mock(JwtProvider.class);
        filter = new JwtAuthFilter(jwtProvider);
    }

    @Test
    void validBearerTokenPopulatesStudentAttributesAndForwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer access.jwt.value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        JwtClaims claims = new JwtClaims(
                "20231234", "홍길동",
                JwtTokenType.ACCESS,
                Instant.now(), Instant.now().plusSeconds(900));
        when(jwtProvider.parse("access.jwt.value", JwtTokenType.ACCESS)).thenReturn(claims);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(AuthAttributes.STUDENT_ID)).isEqualTo("20231234");
        assertThat(request.getAttribute(AuthAttributes.STUDENT_NAME)).isEqualTo("홍길동");
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(chain.getResponse()).isSameAs(response);
    }

    @Test
    void missingAuthorizationHeaderLeavesAttributesUnsetAndForwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/meals/today");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(AuthAttributes.STUDENT_ID)).isNull();
        assertThat(request.getAttribute(AuthAttributes.STUDENT_NAME)).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
        verifyNoInteractions(jwtProvider);
    }

    @Test
    void nonBearerHeaderLeavesAttributesUnsetAndForwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(AuthAttributes.STUDENT_ID)).isNull();
        verifyNoInteractions(jwtProvider);
    }

    @Test
    void emptyBearerTokenLeavesAttributesUnset() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(AuthAttributes.STUDENT_ID)).isNull();
        verifyNoInteractions(jwtProvider);
    }

    @Test
    void invalidTokenLeavesAttributesUnsetAndForwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer expired.or.tampered");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(jwtProvider.parse(eq("expired.or.tampered"), eq(JwtTokenType.ACCESS)))
                .thenThrow(new InvalidJwtException("expired"));

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(AuthAttributes.STUDENT_ID)).isNull();
        assertThat(request.getAttribute(AuthAttributes.STUDENT_NAME)).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
