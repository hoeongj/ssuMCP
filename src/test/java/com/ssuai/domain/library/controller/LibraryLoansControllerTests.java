package com.ssuai.domain.library.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.library.dto.LibraryLoanItem;
import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.domain.library.service.LibraryLoansService;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@ActiveProfiles("test")
@WebMvcTest(LibraryLoansController.class)
class LibraryLoansControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private LibraryLoansService loansService;

    @Autowired
    LibraryLoansControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void getLoansReturnsSuccessEnvelope() throws Exception {
        LibraryLoansResponse response = new LibraryLoansResponse(1, List.of(
                new LibraryLoanItem(1001L, "스프링 부트 핵심 가이드", "장정우",
                        "005.133J38 장7362스",
                        LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 26),
                        false, true)
        ));
        when(loansService.getLoansForSession(anyString())).thenReturn(response);

        mockMvc.perform(get("/api/library/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.loans[0].title").value("스프링 부트 핵심 가이드"))
                .andExpect(jsonPath("$.data.loans[0].isRenewable").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void getLoansReturnsEmptyListWhenNoLoans() throws Exception {
        when(loansService.getLoansForSession(anyString()))
                .thenReturn(new LibraryLoansResponse(0, List.of()));

        mockMvc.perform(get("/api/library/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.loans").isEmpty());
    }

    @Test
    void libraryAuthRequiredMapsTo401() throws Exception {
        when(loansService.getLoansForSession(anyString()))
                .thenThrow(new LibraryAuthRequiredException());

        mockMvc.perform(get("/api/library/loans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LIBRARY_SESSION_REQUIRED"));
    }

    @Test
    void connectorTimeoutMapsTo504() throws Exception {
        when(loansService.getLoansForSession(anyString()))
                .thenThrow(new ConnectorTimeoutException());

        mockMvc.perform(get("/api/library/loans"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("CONNECTOR_TIMEOUT"));
    }

    @Test
    void connectorUnavailableMapsTo503() throws Exception {
        when(loansService.getLoansForSession(anyString()))
                .thenThrow(new ConnectorUnavailableException());

        mockMvc.perform(get("/api/library/loans"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CONNECTOR_UNAVAILABLE"));
    }
}
