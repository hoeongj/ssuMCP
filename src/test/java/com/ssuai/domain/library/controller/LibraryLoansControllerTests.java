package com.ssuai.domain.library.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ssuai.domain.library.auth.LibrarySessionKeyResolver;
import com.ssuai.domain.library.auth.LibrarySessionProperties;
import com.ssuai.domain.library.dto.LibraryLoanItem;
import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.domain.library.service.LibraryLoansService;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@ActiveProfiles("test")
@WebMvcTest(LibraryLoansController.class)
@Import({LibrarySessionProperties.class, LibrarySessionKeyResolver.class})
class LibraryLoansControllerTests {

    private static final Cookie LIBRARY_COOKIE = new Cookie("ssuai_library_session", "cookie-session-key");

    private final MockMvc mockMvc;

    @MockitoBean
    private LibraryLoansService loansService;

    @Autowired
    LibraryLoansControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void getLoansWithOnlyTheCookie_returnsSuccessEnvelope_simulatingAPodSwitch() throws Exception {
        // No servlet session at all — this is exactly the pod-restart / pod-switch case that
        // used to 401 when the store key was the in-memory servlet session id (ADR 0096).
        LibraryLoansResponse response = new LibraryLoansResponse(1, List.of(
                new LibraryLoanItem(1001L, "스프링 부트 핵심 가이드", "장정우",
                        "005.133J38 장7362스",
                        LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 26),
                        false, true)
        ));
        when(loansService.getLoansForSession(eq("cookie-session-key"))).thenReturn(response);

        mockMvc.perform(get("/api/library/loans").cookie(LIBRARY_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.loans[0].title").value("스프링 부트 핵심 가이드"))
                .andExpect(jsonPath("$.data.loans[0].loanDate").value("2026-05-12"))
                .andExpect(jsonPath("$.data.loans[0].dueDate").value("2026-05-26"))
                .andExpect(jsonPath("$.data.loans[0].isRenewable").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void getLoansReturnsEmptyListWhenNoLoans() throws Exception {
        when(loansService.getLoansForSession(anyString()))
                .thenReturn(new LibraryLoansResponse(0, List.of()));

        mockMvc.perform(get("/api/library/loans").cookie(LIBRARY_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.loans").isEmpty());
    }

    @Test
    void getLoansWithLegacyServletSessionOnly_returnsSuccessEnvelope() throws Exception {
        // Legacy fallback: a library session bound before this cookie existed is still
        // resolvable via the servlet session id for one deploy generation.
        MockHttpSession session = new MockHttpSession();
        when(loansService.getLoansForSession(eq(session.getId())))
                .thenReturn(new LibraryLoansResponse(0, List.of()));

        mockMvc.perform(get("/api/library/loans").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void getLoansWithNoCookieAndNoSession_returns401AndCreatesNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/library/loans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LIBRARY_SESSION_REQUIRED"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        verifyNoInteractions(loansService);
    }

    @Test
    void libraryAuthRequiredMapsTo401() throws Exception {
        when(loansService.getLoansForSession(anyString()))
                .thenThrow(new LibraryAuthRequiredException());

        mockMvc.perform(get("/api/library/loans").cookie(LIBRARY_COOKIE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LIBRARY_SESSION_REQUIRED"));
    }

    @Test
    void connectorTimeoutMapsTo504() throws Exception {
        when(loansService.getLoansForSession(anyString()))
                .thenThrow(new ConnectorTimeoutException());

        mockMvc.perform(get("/api/library/loans").cookie(LIBRARY_COOKIE))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("CONNECTOR_TIMEOUT"));
    }

    @Test
    void connectorUnavailableMapsTo503() throws Exception {
        when(loansService.getLoansForSession(anyString()))
                .thenThrow(new ConnectorUnavailableException());

        mockMvc.perform(get("/api/library/loans").cookie(LIBRARY_COOKIE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CONNECTOR_UNAVAILABLE"));
    }
}
