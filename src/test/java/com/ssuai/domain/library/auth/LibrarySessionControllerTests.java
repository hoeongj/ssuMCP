package com.ssuai.domain.library.auth;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
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
    @SuppressWarnings("unused")
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
}
