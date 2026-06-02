package com.ssuai.domain.library.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(LibrarySessionController.class)
@Import({LibrarySessionStore.class, LibrarySessionProperties.class})
class LibrarySessionControllerTests {

    @MockitoBean
    @SuppressWarnings("unused")
    private LibraryCredentialLoginService credentialLoginService;

    private final MockMvc mockMvc;
    private final LibrarySessionStore store;

    @Autowired
    LibrarySessionControllerTests(MockMvc mockMvc, LibrarySessionStore store) {
        this.mockMvc = mockMvc;
        this.store = store;
    }

    @Test
    void clearSessionInvalidatesStoredToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        store.put(session.getId(), "ssotoken-aaaaaaaaaaaaaa");
        assertThat(store.has(session.getId())).isTrue();

        mockMvc.perform(delete("/api/library/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()));

        assertThat(store.has(session.getId())).isFalse();
    }
}
