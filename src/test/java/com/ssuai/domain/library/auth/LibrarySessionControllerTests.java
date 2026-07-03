package com.ssuai.domain.library.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(LibrarySessionController.class)
class LibrarySessionControllerTests {

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
    void clearSessionInvalidatesStoredToken() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(delete("/api/library/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()));

        verify(store).invalidate(session.getId());
    }

    @Test
    void credentialLogin_rotatesSessionIdAndBindsTokenToNewId() throws Exception {
        // Session-fixation hardening: after a successful login the servlet session id
        // is rotated (changeSessionId) and the library token is bound to the NEW id, so a
        // pre-auth fixed JSESSIONID cannot be reused post-auth.
        MockHttpSession session = new MockHttpSession();
        String originalId = session.getId();
        when(credentialLoginService.authenticate(anyString(), anyString())).thenReturn("pyxis-token");

        mockMvc.perform(post("/api/library/login")
                        .session(session)
                        .contentType("application/json")
                        .content("""
                                {"loginId":"20250001","password":"enc-pw"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.error").value(nullValue()));

        ArgumentCaptor<String> boundKey = ArgumentCaptor.forClass(String.class);
        verify(credentialLoginService).bind(boundKey.capture(), org.mockito.ArgumentMatchers.eq("pyxis-token"));
        assertThat(boundKey.getValue()).isNotEqualTo(originalId);
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
}
