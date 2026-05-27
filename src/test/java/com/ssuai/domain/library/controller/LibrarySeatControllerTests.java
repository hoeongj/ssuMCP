package com.ssuai.domain.library.controller;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatItem;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@ActiveProfiles("test")
@WebMvcTest(LibrarySeatController.class)
class LibrarySeatControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private LibrarySeatService libraryService;

    @Autowired
    LibrarySeatControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void getSeatStatusReturnsSuccessEnvelope() throws Exception {
        LibrarySeatStatusResponse response = new LibrarySeatStatusResponse(
                2, "2층", 344, 230, 112, 2,
                Instant.parse("2026-05-15T07:30:14Z"),
                List.of(new LibrarySeatZone(
                        "숭실스퀘어ON(2F)", 112, 87, List.of("2-A-001"),
                        List.of(new LibrarySeatItem("2-A-001", "A-1", "available"))
                ))
        );
        when(libraryService.getSeatStatusForSession(eq(LibraryFloor.F2), anyString())).thenReturn(response);

        mockMvc.perform(get("/api/library/seats").param("floor", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.floor").value(2))
                .andExpect(jsonPath("$.data.floorLabel").value("2층"))
                .andExpect(jsonPath("$.data.availableSeats").value(230))
                .andExpect(jsonPath("$.data.totalSeats").value(344))
                .andExpect(jsonPath("$.data.zones[0].label").value("숭실스퀘어ON(2F)"))
                .andExpect(jsonPath("$.data.zones[0].seats[0].label").value("A-1"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }

    @Test
    void getSeatStatusAcceptsFloor5() throws Exception {
        LibrarySeatStatusResponse response = new LibrarySeatStatusResponse(
                5, "5층", 104, 70, 32, 2,
                Instant.parse("2026-05-15T07:30:14Z"),
                List.of()
        );
        when(libraryService.getSeatStatusForSession(eq(LibraryFloor.F5), anyString())).thenReturn(response);

        mockMvc.perform(get("/api/library/seats").param("floor", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.floor").value(5))
                .andExpect(jsonPath("$.data.floorLabel").value("5층"));
    }

    @Test
    void getSeatStatusRejectsFloor4() throws Exception {
        mockMvc.perform(get("/api/library/seats").param("floor", "4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(libraryService);
    }

    @Test
    void getSeatStatusRejectsUnsupportedFloor() throws Exception {
        mockMvc.perform(get("/api/library/seats").param("floor", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(libraryService);
    }

    @Test
    void getSeatStatusRejectsMissingFloor() throws Exception {
        mockMvc.perform(get("/api/library/seats"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(libraryService);
    }

    @Test
    void connectorTimeoutMapsTo504() throws Exception {
        when(libraryService.getSeatStatusForSession(eq(LibraryFloor.F2), anyString()))
                .thenThrow(new ConnectorTimeoutException());

        mockMvc.perform(get("/api/library/seats").param("floor", "2"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("CONNECTOR_TIMEOUT"));
    }

    @Test
    void connectorUnavailableMapsTo503() throws Exception {
        when(libraryService.getSeatStatusForSession(eq(LibraryFloor.F2), anyString()))
                .thenThrow(new ConnectorUnavailableException());

        mockMvc.perform(get("/api/library/seats").param("floor", "2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CONNECTOR_UNAVAILABLE"));
    }

    @Test
    void libraryAuthRequiredMapsTo401() throws Exception {
        when(libraryService.getSeatStatusForSession(eq(LibraryFloor.F2), anyString()))
                .thenThrow(new LibraryAuthRequiredException());

        mockMvc.perform(get("/api/library/seats").param("floor", "2"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LIBRARY_SESSION_REQUIRED"));
    }

}
