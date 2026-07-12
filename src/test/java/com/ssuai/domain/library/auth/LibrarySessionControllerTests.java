package com.ssuai.domain.library.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@WebMvcTest(LibrarySessionController.class)
@Import({LibrarySessionProperties.class, LibrarySessionKeyResolver.class})
@TestPropertySource(properties = {
        "ssuai.library.session.ttl=7d",
        "ssuai.library.session.cookie.name=ssuai_library_session",
        "ssuai.library.session.cookie.path=/",
        "ssuai.library.session.cookie.secure=false",
        "ssuai.library.session.cookie.same-site=Lax"
})
class LibrarySessionControllerTests {

    private static final String COOKIE_NAME = "ssuai_library_session";

    @MockitoBean
    private LibraryCredentialLoginService credentialLoginService;

    @MockitoBean
    private LibrarySessionStore store;

    private final MockMvc mockMvc;

    @Autowired
    LibrarySessionControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void credentialLogin_setsPersistentCookieAndBindsTokenUnderGeneratedKey() throws Exception {
        // The store key is now a freshly generated, server-side random value (ADR 0096) —
        // it is not derived from (and does not rotate) any servlet session.
        when(credentialLoginService.authenticate(anyString(), anyString())).thenReturn("pyxis-token");

        MvcResult result = mockMvc.perform(post("/api/library/login")
                        .contentType("application/json")
                        .content("""
                                {"loginId":"20250001","password":"enc-pw"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andReturn();

        Cookie responseCookie = result.getResponse().getCookie(COOKIE_NAME);
        assertThat(responseCookie).isNotNull();
        assertThat(responseCookie.getValue()).isNotBlank();
        assertThat(responseCookie.getMaxAge()).isEqualTo((int) java.time.Duration.ofDays(7).getSeconds());

        ArgumentCaptor<String> boundKey = ArgumentCaptor.forClass(String.class);
        verify(credentialLoginService).bind(boundKey.capture(), eq("pyxis-token"));
        assertThat(boundKey.getValue()).isEqualTo(responseCookie.getValue());
    }

    @Test
    void credentialLogin_doesNotTouchAnyServletSession() throws Exception {
        // Session-fixation hardening is now inherent (the key is server-generated and never
        // client-supplied), so login must not create or rotate a servlet session at all.
        when(credentialLoginService.authenticate(anyString(), anyString())).thenReturn("pyxis-token");

        MvcResult result = mockMvc.perform(post("/api/library/login")
                        .contentType("application/json")
                        .content("""
                                {"loginId":"20250001","password":"enc-pw"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void credentialLogin_overMaxPasswordReturnsValidationError() throws Exception {
        // Input size cap: password is bounded at 2000 chars; an over-max
        // body is rejected by the existing 400 validation handler before authenticate runs.
        String oversized = "a".repeat(2001);
        mockMvc.perform(post("/api/library/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"20250001\",\"password\":\"" + oversized + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void credentialLogin_overMaxLoginIdReturnsValidationError() throws Exception {
        String oversized = "1".repeat(101);
        mockMvc.perform(post("/api/library/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + oversized + "\",\"password\":\"enc-pw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void clearSession_withCookie_invalidatesStoredTokenAndClearsCookie() throws Exception {
        mockMvc.perform(delete("/api/library/session")
                        .cookie(new Cookie(COOKIE_NAME, "cookie-session-key")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(header().string("Set-Cookie", containsString(COOKIE_NAME + "=")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")));

        verify(store).invalidate("cookie-session-key");
    }

    @Test
    void clearSession_legacyServletSessionOnly_invalidatesStoredTokenAndClearsCookie() throws Exception {
        // Legacy fallback: a library session bound before this cookie existed is still
        // resolvable (and clearable) via the servlet session id for one deploy generation.
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(delete("/api/library/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(store).invalidate(session.getId());
    }

    @Test
    void clearSession_withNoCookieAndNoSession_isNoOpButStillClearsCookie() throws Exception {
        mockMvc.perform(delete("/api/library/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(store, never()).invalidate(anyString());
    }
}
