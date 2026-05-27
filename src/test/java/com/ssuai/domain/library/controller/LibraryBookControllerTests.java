package com.ssuai.domain.library.controller;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
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

import com.ssuai.domain.library.dto.BookStatus;
import com.ssuai.domain.library.dto.LibraryBook;
import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.domain.library.service.LibraryBookService;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;

@ActiveProfiles("test")
@WebMvcTest(LibraryBookController.class)
class LibraryBookControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private LibraryBookService libraryBookService;

    @Autowired
    LibraryBookControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void searchBooksReturnsSuccessEnvelope() throws Exception {
        LibraryBookSearchResponse response = new LibraryBookSearchResponse(2, 0, 10, List.of(
                new LibraryBook(5006619L, "파이썬 : 기초와 활용", "한정란",
                        "파주 : 21세기사, 2023", "9791168330702", null,
                        "005.133P9 한7362파", "중앙도서관", BookStatus.AVAILABLE),
                new LibraryBook(5000719L, "(두근두근) 파이썬", "천인국",
                        "파주 : 생능, 2023", "9788970506562", null,
                        "005.133P9 천6921파2", "중앙도서관", BookStatus.AVAILABLE)
        ));
        when(libraryBookService.search(eq("파이썬"), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/library/books").param("query", "파이썬"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].title").value("파이썬 : 기초와 활용"))
                .andExpect(jsonPath("$.data.items[0].callNumber").value("005.133P9 한7362파"))
                .andExpect(jsonPath("$.data.items[0].location").value("중앙도서관"))
                .andExpect(jsonPath("$.data.items[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }

    @Test
    void missingQueryReturns400() throws Exception {
        mockMvc.perform(get("/api/library/books"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(libraryBookService);
    }

    @Test
    void blankQueryReturns400() throws Exception {
        when(libraryBookService.search(eq("   "), any(), any()))
                .thenThrow(new IllegalArgumentException("검색어를 입력해 주세요."));

        mockMvc.perform(get("/api/library/books").param("query", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void connectorTimeoutMapsTo504() throws Exception {
        when(libraryBookService.search(eq("파이썬"), any(), any()))
                .thenThrow(new ConnectorTimeoutException());

        mockMvc.perform(get("/api/library/books").param("query", "파이썬"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("CONNECTOR_TIMEOUT"));
    }

    @Test
    void connectorUnavailableMapsTo503() throws Exception {
        when(libraryBookService.search(eq("파이썬"), any(), any()))
                .thenThrow(new ConnectorUnavailableException());

        mockMvc.perform(get("/api/library/books").param("query", "파이썬"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CONNECTOR_UNAVAILABLE"));
    }
}
