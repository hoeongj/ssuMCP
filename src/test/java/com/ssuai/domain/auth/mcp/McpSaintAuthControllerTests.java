package com.ssuai.domain.auth.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.domain.auth.lms.LmsSsoService;
import com.ssuai.domain.auth.saint.SaintSsoService;
import com.ssuai.domain.auth.saint.UsaintAuthResult;
import com.ssuai.global.exception.SaintAuthFailedException;
import com.ssuai.global.exception.SaintPortalUnavailableException;

@ActiveProfiles("test")
@WebMvcTest(McpSaintAuthController.class)
@Import({AuthProperties.class, McpAuthUrlFactory.class})
@TestPropertySource(properties = {
        "ssuai.auth.api-base-url=https://api.ssuai.test",
        "ssuai.auth.smartid-sso-url=https://smartid.example/sso",
})
class McpSaintAuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SaintSsoService saintSsoService;

    @MockitoBean
    private LmsSsoService lmsSsoService;

    @MockitoBean
    private McpAuthService mcpAuthService;

    private static final McpAuthSessionId SESSION_ID = new McpAuthSessionId("test-session-id");
    private static final Instant EXPIRES = Instant.parse("2026-05-18T11:00:00Z");

    @Test
    void startRedirectsToSmartId() throws Exception {
        mockMvc.perform(get("/api/mcp/auth/saint/start").param("state", "somestate"))
                .andExpect(status().isFound())
                .andExpect(result -> {
                    String location = result.getResponse().getHeader("Location");
                    assert location != null;
                    assert location.startsWith("https://smartid.example/sso?apiReturnUrl=");
                    assert location.contains("saint%2Fcallback");
                });
    }

    @Test
    void callbackWithMissingStateReturnsErrorPage() throws Exception {
        when(mcpAuthService.consumeState(null)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/mcp/auth/saint/callback"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));

        verify(saintSsoService, never()).authenticate(any(), any());
    }

    @Test
    void callbackWithExpiredStateReturnsErrorPage() throws Exception {
        when(mcpAuthService.consumeState("expired-state")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sToken", "t").param("sIdno", "i").param("state", "expired-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));

        verify(saintSsoService, never()).authenticate(any(), any());
    }

    @Test
    void doubleQuestionMarkStateIsHandled() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));
        when(saintSsoService.authenticate("tok", "20231234"))
                .thenReturn(new UsaintAuthResult("20231234", "홍길동", "CS", "재학"));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sIdno", "20231234")
                        .param("state", "valid-state?sToken=tok"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(mcpAuthService).consumeState(stateCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(stateCaptor.getValue()).isEqualTo("valid-state");
        verify(saintSsoService).authenticate("tok", "20231234");
        verify(mcpAuthService).linkProvider(SESSION_ID, McpProviderType.SAINT, "20231234");
    }

    @Test
    void doubleQuestionMarkStateWithIdnoAndTokenIsHandled() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));
        when(saintSsoService.authenticate("tok", "20231234"))
                .thenReturn(new UsaintAuthResult("20231234", "홍길동", "CS", "재학"));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("state", "valid-state?sIdno=20231234&sToken=tok"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(mcpAuthService).consumeState(stateCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(stateCaptor.getValue()).isEqualTo("valid-state");
        verify(saintSsoService).authenticate("tok", "20231234");
        verify(mcpAuthService).linkProvider(SESSION_ID, McpProviderType.SAINT, "20231234");
    }

    @Test
    void normalStateIsHandled() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("normal-state", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.consumeState("normal-state")).thenReturn(Optional.of(entry));
        when(saintSsoService.authenticate("tok", "20231234"))
                .thenReturn(new UsaintAuthResult("20231234", "홍길동", "CS", "재학"));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sToken", "tok").param("sIdno", "20231234").param("state", "normal-state"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(mcpAuthService).consumeState(stateCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(stateCaptor.getValue()).isEqualTo("normal-state");
        verify(saintSsoService).authenticate("tok", "20231234");
    }

    @Test
    void callbackWithProviderMismatchReturnsErrorPage() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.LMS, EXPIRES);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sToken", "t").param("sIdno", "i").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));

        verify(saintSsoService, never()).authenticate(any(), any());
    }

    @Test
    void callbackSuccessLinksProviderAndReturnsSuccessPage() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));
        when(saintSsoService.authenticate("tok", "20231234"))
                .thenReturn(new UsaintAuthResult("20231234", "홍길동", "CS", "재학"));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sToken", "tok").param("sIdno", "20231234").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 완료")));

        verify(mcpAuthService).linkProvider(SESSION_ID, McpProviderType.SAINT, "20231234");
    }

    @Test
    void callbackSaintSuccessAlsoTriesLmsLink() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));
        when(saintSsoService.authenticate("tok", "20231234"))
                .thenReturn(new UsaintAuthResult("20231234", "홍길동", "CS", "재학"));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sToken", "tok").param("sIdno", "20231234").param("state", "valid-state"))
                .andExpect(status().isOk());

        verify(lmsSsoService).authenticate("tok", "20231234");
        verify(mcpAuthService).linkProvider(SESSION_ID, McpProviderType.LMS, "20231234");
    }

    @Test
    void callbackSaintAuthFailureReturnsErrorPageWithoutLinking() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));
        when(saintSsoService.authenticate(any(), any()))
                .thenThrow(new SaintAuthFailedException("invalid tokens"));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sToken", "bad").param("sIdno", "bad").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("invalid tokens"))));

        verify(mcpAuthService, never()).linkProvider(any(), any(), any());
    }

    @Test
    void callbackPortalUnavailableReturnsErrorPage() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));
        when(saintSsoService.authenticate(any(), any()))
                .thenThrow(new SaintPortalUnavailableException("timeout"));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sToken", "tok").param("sIdno", "20231234").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));
    }

    @Test
    void callbackSuccessPageDoesNotContainStudentId() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));
        when(saintSsoService.authenticate("tok", "20231234"))
                .thenReturn(new UsaintAuthResult("20231234", "홍길동", "CS", "재학"));

        mockMvc.perform(get("/api/mcp/auth/saint/callback")
                        .param("sToken", "tok").param("sIdno", "20231234").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("20231234"))));
    }
}
