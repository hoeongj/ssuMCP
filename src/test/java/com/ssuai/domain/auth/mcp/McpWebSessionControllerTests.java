package com.ssuai.domain.auth.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.library.auth.LibrarySessionKeyResolver;
import com.ssuai.domain.library.auth.LibrarySessionProperties;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.global.auth.AuthAttributes;

@ActiveProfiles("test")
@WebMvcTest(McpWebSessionController.class)
@Import({LibrarySessionProperties.class, LibrarySessionKeyResolver.class})
class McpWebSessionControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private McpAuthService mcpAuthService;

    @MockitoBean
    private LibrarySessionStore librarySessionStore;

    @MockitoBean
    private SaintSessionStore saintSessionStore;

    @MockitoBean
    private LmsSessionStore lmsSessionStore;

    @MockitoBean
    private McpProviderCredentialService credentialService;

    @Autowired
    McpWebSessionControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @BeforeEach
    void credentialCopiesSucceed() {
        when(mcpAuthService.bindOrVerifyOauthSubject(any(), anyString())).thenReturn(true);
        when(saintSessionStore.copyForSession(anyString(), anyString())).thenReturn(true);
        when(lmsSessionStore.copyForSession(anyString(), anyString())).thenReturn(true);
        when(librarySessionStore.copy(anyString(), anyString())).thenReturn(true);
    }

    @Test
    void create_withJwtAndNoLibrarySession_linksSaintAndLmsOnly() throws Exception {
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mcpSessionId").value("test-session-id"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-06-14T12:00:00Z"))
                .andExpect(jsonPath("$.data.linkedProviders", containsInAnyOrder("SAINT", "LMS")));

        verify(mcpAuthService).createSession();
        verify(mcpAuthService).linkProvider(eq(sessionId), eq(McpProviderType.SAINT), anyString());
        verify(mcpAuthService).linkProvider(eq(sessionId), eq(McpProviderType.LMS), anyString());
        verify(mcpAuthService, never()).linkProvider(eq(sessionId), eq(McpProviderType.LIBRARY), any());
    }

    @Test
    void create_withJwtAndLibrarySession_linksAllProviders() throws Exception {
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);

        MockHttpSession mockSession = new MockHttpSession();
        String sessionKey = mockSession.getId();
        when(librarySessionStore.has(sessionKey)).thenReturn(true);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234")
                        .session(mockSession))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mcpSessionId").value("test-session-id"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-06-14T12:00:00Z"))
                .andExpect(jsonPath(
                        "$.data.linkedProviders",
                        containsInAnyOrder("SAINT", "LMS", "LIBRARY")));

        verify(mcpAuthService).createSession();
        verify(mcpAuthService).linkProvider(eq(sessionId), eq(McpProviderType.SAINT), anyString());
        verify(mcpAuthService).linkProvider(eq(sessionId), eq(McpProviderType.LMS), anyString());
        verify(mcpAuthService).linkProvider(eq(sessionId), eq(McpProviderType.LIBRARY), anyString());
    }

    @Test
    void create_withoutJwtAndWithLibrarySession_linksLibraryOnly() throws Exception {
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);

        MockHttpSession mockSession = new MockHttpSession();
        String sessionKey = mockSession.getId();
        when(librarySessionStore.has(sessionKey)).thenReturn(true);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .session(mockSession))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mcpSessionId").value("test-session-id"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-06-14T12:00:00Z"))
                .andExpect(jsonPath("$.data.linkedProviders[0]").value("LIBRARY"));

        verify(mcpAuthService).createSession();
        verify(mcpAuthService, never()).linkProvider(eq(sessionId), eq(McpProviderType.SAINT), any());
        verify(mcpAuthService, never()).linkProvider(eq(sessionId), eq(McpProviderType.LMS), any());
        verify(mcpAuthService).linkProvider(eq(sessionId), eq(McpProviderType.LIBRARY), anyString());
    }

    @Test
    void create_withLibraryCookieOnlyAndNoServletSession_linksLibraryOnly() throws Exception {
        // Simulates a redeploy/pod-switch: only the persistent library-session cookie is
        // present, no servlet session (ADR 0096).
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);
        when(librarySessionStore.has("cookie-session-key")).thenReturn(true);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .cookie(new Cookie("ssuai_library_session", "cookie-session-key")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mcpSessionId").value("test-session-id"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-06-14T12:00:00Z"))
                .andExpect(jsonPath("$.data.linkedProviders[0]").value("LIBRARY"));

        verify(mcpAuthService).createSession();
        verify(mcpAuthService, never()).linkProvider(eq(sessionId), eq(McpProviderType.SAINT), any());
        verify(mcpAuthService, never()).linkProvider(eq(sessionId), eq(McpProviderType.LMS), any());
        verify(mcpAuthService).linkProvider(eq(sessionId), eq(McpProviderType.LIBRARY), anyString());
    }

    @Test
    void create_withoutJwtAndWithoutLibrarySession_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/mcp/auth/web-session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(mcpAuthService);
    }

    @Test
    void create_reportsActualProviderCopiesInsteadOfInferringThemFromJwt() throws Exception {
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);
        when(saintSessionStore.copyForSession(anyString(), anyString())).thenReturn(false);
        when(lmsSessionStore.copyForSession(anyString(), anyString())).thenReturn(true);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.linkedProviders.length()").value(1))
                .andExpect(jsonPath("$.data.linkedProviders[0]").value("LMS"));

        verify(mcpAuthService, never()).linkProvider(
                eq(sessionId), eq(McpProviderType.SAINT), anyString());
        verify(mcpAuthService).linkProvider(
                eq(sessionId), eq(McpProviderType.LMS), anyString());
    }

    @Test
    void create_withJwtButNoCanonicalCredentials_reportsEmptyProviderSet() throws Exception {
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);
        when(saintSessionStore.copyForSession(anyString(), anyString())).thenReturn(false);
        when(lmsSessionStore.copyForSession(anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.linkedProviders").isEmpty());

        verify(mcpAuthService, never()).linkProvider(
                eq(sessionId), eq(McpProviderType.SAINT), anyString());
        verify(mcpAuthService, never()).linkProvider(
                eq(sessionId), eq(McpProviderType.LMS), anyString());
    }

    @Test
    void create_whenLaterCredentialCopyFails_compensatesSessionAndCopies() throws Exception {
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);
        when(lmsSessionStore.copyForSession(anyString(), anyString()))
                .thenThrow(new IllegalStateException("persistent copy failed"));

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isInternalServerError());

        verify(mcpAuthService).invalidateSession(sessionId);
        verify(saintSessionStore).invalidate(anyString());
        verify(lmsSessionStore).invalidate(anyString());
        verify(librarySessionStore, never()).invalidate(anyString());
    }

    @Test
    void create_whenOauthSubjectBindingIsRejected_compensatesCreatedSession() throws Exception {
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);
        when(mcpAuthService.bindOrVerifyOauthSubject(sessionId, "20241234")).thenReturn(false);

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isUnauthorized());

        verify(mcpAuthService).invalidateSession(sessionId);
        verifyNoInteractions(saintSessionStore, lmsSessionStore);
    }

    @Test
    void create_whenOauthSubjectBindingFails_compensatesCreatedSession() throws Exception {
        McpAuthSession session = session("test-session-id");
        McpAuthSessionId sessionId = session.id();
        when(mcpAuthService.createSession()).thenReturn(session);
        when(mcpAuthService.bindOrVerifyOauthSubject(sessionId, "20241234"))
                .thenThrow(new IllegalStateException("subject store unavailable"));

        mockMvc.perform(post("/api/mcp/auth/web-session")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isInternalServerError());

        verify(mcpAuthService).invalidateSession(sessionId);
        verifyNoInteractions(saintSessionStore, lmsSessionStore);
    }

    @Test
    void status_withJwtReportsOnlyCurrentlyAvailableProviderLinks() throws Exception {
        McpAuthSession session = sessionWithProviders(
                "test-session-id", McpProviderType.SAINT, McpProviderType.LMS);
        when(mcpAuthService.find("test-session-id")).thenReturn(java.util.Optional.of(session));
        when(mcpAuthService.verifyOauthSubject(session.id(), "20241234")).thenReturn(true);
        when(credentialService.isAvailable(session.provider(McpProviderType.SAINT).orElseThrow()))
                .thenReturn(true);
        when(credentialService.isAvailable(session.provider(McpProviderType.LMS).orElseThrow()))
                .thenReturn(false);

        mockMvc.perform(post("/api/mcp/auth/web-session/status")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234")
                        .contentType("application/json")
                        .content("{\"mcpSessionId\":\"test-session-id\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mcpSessionId").value("test-session-id"))
                .andExpect(jsonPath("$.data.linkedProviders[0]").value("SAINT"));
    }

    @Test
    void status_withDifferentJwtSubjectIsRejected() throws Exception {
        McpAuthSession session = session("test-session-id");
        when(mcpAuthService.find("test-session-id")).thenReturn(java.util.Optional.of(session));
        when(mcpAuthService.verifyOauthSubject(session.id(), "20249999")).thenReturn(false);

        mockMvc.perform(post("/api/mcp/auth/web-session/status")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20249999")
                        .contentType("application/json")
                        .content("{\"mcpSessionId\":\"test-session-id\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(credentialService);
    }

    @Test
    void status_withActiveLibraryCookieSupportsLibraryOnlySession() throws Exception {
        McpAuthSession session = sessionWithProviders(
                "test-session-id", McpProviderType.LIBRARY);
        when(librarySessionStore.has("cookie-session-key")).thenReturn(true);
        when(mcpAuthService.find("test-session-id")).thenReturn(java.util.Optional.of(session));
        when(credentialService.isAvailable(session.provider(McpProviderType.LIBRARY).orElseThrow()))
                .thenReturn(true);

        mockMvc.perform(post("/api/mcp/auth/web-session/status")
                        .cookie(new Cookie("ssuai_library_session", "cookie-session-key"))
                        .contentType("application/json")
                        .content("{\"mcpSessionId\":\"test-session-id\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedProviders[0]").value("LIBRARY"));

        verify(mcpAuthService, never()).verifyOauthSubject(any(), anyString());
    }

    @Test
    void status_withoutWebIdentityIsRejectedBeforeSessionLookup() throws Exception {
        mockMvc.perform(post("/api/mcp/auth/web-session/status")
                        .contentType("application/json")
                        .content("{\"mcpSessionId\":\"test-session-id\"}"))
                .andExpect(status().isUnauthorized());

        verify(mcpAuthService, never()).find(anyString());
    }

    private McpAuthSession session(String id) {
        McpAuthSessionId sessionId = new McpAuthSessionId(id);
        Instant expiresAt = Instant.parse("2026-06-14T12:00:00Z");
        return new McpAuthSession(sessionId, Instant.now(), expiresAt, Map.of());
    }

    private McpAuthSession sessionWithProviders(
            String id, McpProviderType... providers) {
        EnumMap<McpProviderType, McpProviderLink> links = new EnumMap<>(McpProviderType.class);
        for (McpProviderType provider : providers) {
            links.put(provider, new McpProviderLink(
                    provider, provider.name().toLowerCase() + "-owner", Instant.now()));
        }
        McpAuthSessionId sessionId = new McpAuthSessionId(id);
        return new McpAuthSession(
                sessionId,
                Instant.now(),
                Instant.parse("2026-06-14T12:00:00Z"),
                links);
    }
}
