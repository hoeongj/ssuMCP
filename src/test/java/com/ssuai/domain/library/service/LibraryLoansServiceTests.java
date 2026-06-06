package com.ssuai.domain.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.connector.LibraryLoansConnector;
import com.ssuai.domain.library.dto.LibraryLoanItem;
import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class LibraryLoansServiceTests {

    private static final String SESSION_KEY = "test-session-id";
    private static final String TOKEN = "stub-pyxis-token";

    @Test
    void mockModeReturnsLoansWithoutSessionCheck() {
        LibraryLoansConnector connector = mock(LibraryLoansConnector.class);
        LibraryLoansResponse stub = stubResponse(2);
        when(connector.fetchLoans(any())).thenReturn(stub);
        LibraryLoansService service = new LibraryLoansService(
                connector, mock(LibrarySessionStore.class), "mock");

        LibraryLoansResponse response = service.getLoansForSession(SESSION_KEY);

        assertThat(response).isSameAs(stub);
    }

    @Test
    void realModeWithoutSessionThrows401() {
        LibraryLoansConnector connector = mock(LibraryLoansConnector.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        when(store.token(SESSION_KEY)).thenReturn(Optional.empty());
        LibraryLoansService service = new LibraryLoansService(
                connector, store, "real");

        assertThatThrownBy(() -> service.getLoansForSession(SESSION_KEY))
                .isInstanceOf(LibraryAuthRequiredException.class);

        verifyNoInteractions(connector);
    }

    @Test
    void realModeWithCapturedSessionDelegatesToConnector() {
        LibraryLoansConnector connector = mock(LibraryLoansConnector.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        when(store.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        LibraryLoansResponse stub = stubResponse(1);
        when(connector.fetchLoans(TOKEN)).thenReturn(stub);
        LibraryLoansService service = new LibraryLoansService(connector, store, "real");

        LibraryLoansResponse response = service.getLoansForSession(SESSION_KEY);

        assertThat(response).isSameAs(stub);
    }

    @Test
    void connectorExceptionBubblesUpWithoutWrapping() {
        LibraryLoansConnector connector = mock(LibraryLoansConnector.class);
        when(connector.fetchLoans(any())).thenThrow(new ConnectorTimeoutException());
        LibraryLoansService service = new LibraryLoansService(
                connector, mock(LibrarySessionStore.class), "mock");

        assertThatThrownBy(() -> service.getLoansForSession(SESSION_KEY))
                .isInstanceOf(ConnectorTimeoutException.class);
    }

    private static LibraryLoansResponse stubResponse(int count) {
        List<LibraryLoanItem> items = List.of(
                new LibraryLoanItem(1001L, "테스트 도서", "저자", "000.000",
                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 15),
                        false, true)
        );
        return new LibraryLoansResponse(count, items);
    }
}
