package com.ssuai.domain.auth.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.global.auth.AuthAttributes;

@ActiveProfiles("test")
@WebMvcTest(McpWebSessionController.class)
class McpWebSessionControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private McpAuthService mcpAuthService;

    @MockitoBean
    private LibrarySessionStore librarySessionStore;

    @Autowired
    McpWebSessionControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void create_withValidJwt_returnsCreatedAndSession() throws Exception {
        McpAuthSessionId sessionId = new McpAuthSessionId("test-session-id");
        Instant expiresAt = Instant.parse("2026-06-14T12:00:00Z");
        McpAuthSession session = new McpAuthSession(sessionId, Instant.now(), expiresAt, Map.of());
        
        when(mcpAuthService.createSession()).thenReturn(session);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mcpSessionId").value("test-session-id"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-06-14T12:00:00Z"));

        verify(mcpAuthService).linkProvider(sessionId, McpProviderType.SAINT, "20241234");
        verify(mcpAuthService).linkProvider(sessionId, McpProviderType.LMS, "20241234");
    }

    @Test
    void create_withLibrarySession_linksLibrary() throws Exception {
        McpAuthSessionId sessionId = new McpAuthSessionId("test-session-id");
        Instant expiresAt = Instant.parse("2026-06-14T12:00:00Z");
        McpAuthSession session = new McpAuthSession(sessionId, Instant.now(), expiresAt, Map.of());
        
        when(mcpAuthService.createSession()).thenReturn(session);

        MockHttpSession mockSession = new MockHttpSession();
        String sessionKey = mockSession.getId();
        when(librarySessionStore.has(sessionKey)).thenReturn(true);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234")
                        .session(mockSession))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mcpSessionId").value("test-session-id"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-06-14T12:00:00Z"));

        verify(mcpAuthService).linkProvider(sessionId, McpProviderType.SAINT, "20241234");
        verify(mcpAuthService).linkProvider(sessionId, McpProviderType.LMS, "20241234");
        verify(mcpAuthService).linkProvider(sessionId, McpProviderType.LIBRARY, sessionKey);
    }

    @Test
    void create_withoutJwt_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/mcp/auth/web-session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
