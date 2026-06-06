package com.ssuai.domain.library.controller;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogEntry;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogResponse;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogService;

@ActiveProfiles("test")
@WebMvcTest(LibrarySeatCatalogController.class)
class LibrarySeatCatalogControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private LibrarySeatRoomCatalogService roomCatalogService;

    @Autowired
    LibrarySeatCatalogControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void getSeatCatalogReturnsSuccessEnvelope() throws Exception {
        LibrarySeatRoomCatalogResponse response = new LibrarySeatRoomCatalogResponse(
                1,
                true,
                "static catalog",
                List.of(new LibrarySeatRoomCatalogEntry(
                        "B1",
                        -1,
                        "basement-reading-b1",
                        "지하열람실(B1)",
                        "all",
                        true,
                        false,
                        false,
                        "1-172",
                        List.of("general"),
                        List.of("left", "right"),
                        List.of("1 2 3"),
                        List.of("B1 static only"))));
        when(roomCatalogService.catalog("B1", null, true)).thenReturn(response);

        mockMvc.perform(get("/api/library/seat-catalog")
                        .param("floorCode", "B1")
                        .param("includeLayout", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomCount").value(1))
                .andExpect(jsonPath("$.data.includesLayout").value(true))
                .andExpect(jsonPath("$.data.rooms[0].roomCode").value("basement-reading-b1"))
                .andExpect(jsonPath("$.data.rooms[0].textLayout[0]").value("1 2 3"))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }
}
