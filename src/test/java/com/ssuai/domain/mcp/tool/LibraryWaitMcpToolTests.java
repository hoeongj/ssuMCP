package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentView;
import com.ssuai.domain.library.reservation.intent.LibraryReservationPreferenceNormalizer;
import com.ssuai.domain.library.reservation.intent.LibraryReservationRegistrationResult;
import com.ssuai.domain.library.reservation.intent.LibraryReservationWaitRequest;

class LibraryWaitMcpToolTests {

    private static final String SESSION_ID = "mcp-session";
    private static final String SESSION_KEY = "library-key";
    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");

    private LibraryReservationIntentTransactions transactions;
    private McpAuthHelper authHelper;
    private LibraryWaitMcpTool tool;

    @BeforeEach
    void setUp() {
        transactions = mock(LibraryReservationIntentTransactions.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibraryWaitMcpTool(
                transactions,
                new LibraryReservationPreferenceNormalizer(),
                authHelper);
    }

    @Test
    void returnsAuthRequiredWhenLibraryIsNotLinked() {
        McpPrivateToolResponse<String> authRequired =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login", NOW.plusSeconds(600));
        when(authHelper.principalKey(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<String>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(authRequired);

        McpPrivateToolResponse<String> response =
                tool.waitForLibrarySeat(null, null, null, null, null, null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(transactions);
    }

    @Test
    void waitRegistrationMessageStatesAutonomousReservationConsent() {
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(SESSION_KEY));
        when(transactions.registerWait(
                org.mockito.ArgumentMatchers.eq(SESSION_KEY),
                org.mockito.ArgumentMatchers.any(LibraryReservationWaitRequest.class)))
                .thenReturn(new LibraryReservationRegistrationResult(
                        new LibraryReservationIntentView(
                                11L,
                                LibraryReservationIntentStatus.WAITING_FOR_SEAT,
                                0,
                                NOW,
                                NOW.plusSeconds(7200),
                                null,
                                null,
                                null,
                                3179L,
                                null),
                        true));

        McpPrivateToolResponse<String> response =
                tool.waitForLibrarySeat(SESSION_ID, "6F", "57,58", "window,outlet", "3179", 30);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data())
                .contains("intentId=11")
                .contains("WAITING_FOR_SEAT")
                .contains("autonomously reserve");
    }
}
