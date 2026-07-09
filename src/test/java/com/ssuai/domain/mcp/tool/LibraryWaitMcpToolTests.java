package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(authHelper.resolvePrincipal(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<String>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(authRequired);

        McpPrivateToolResponse<String> response =
                tool.waitForLibrarySeat(null, null, null, null, null, null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(transactions);
    }

    @Test
    void waitRegistrationMessageStatesAutonomousReservationConsent() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
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

    @Test
    void getWaitStatusWithoutIntentIdReturnsLatestForSession() {
        // Backward-compatible default (no intent_id): the pre-existing "latest wait intent"
        // behavior, used by the plain wait_for_library_seat flow where only one intent matters.
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(transactions.latestForSession(SESSION_KEY))
                .thenReturn(Optional.of(intentView(20L, LibraryReservationIntentStatus.RESERVING)));

        McpPrivateToolResponse<String> response = tool.getLibraryWaitStatus(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("intentId=20").contains("RESERVING");
        verify(transactions, never()).isOwnedBySession(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getWaitStatusWithIntentIdReturnsThatSpecificIntentWhenOwned() {
        // C1 follow-up: after confirm_action returns ACCEPTED with an intentId for a specific
        // reservation, get_library_wait_status(intent_id=...) must be able to look up THAT
        // intent even if a newer, unrelated intent has since become "latest" for the session —
        // this is what disambiguates concurrent reservations (G2).
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(transactions.isOwnedBySession(eq(21L), eq(SESSION_KEY))).thenReturn(true);
        when(transactions.findById(21L))
                .thenReturn(Optional.of(intentView(21L, LibraryReservationIntentStatus.SUCCEEDED)));

        McpPrivateToolResponse<String> response = tool.getLibraryWaitStatus(SESSION_ID, 21L);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("intentId=21").contains("SUCCEEDED");
        verify(transactions, never()).latestForSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getWaitStatusWithIntentIdNotOwnedByCallerReturnsNoIntentMessage() {
        // IDOR guard (mirrors the SSE subscribe endpoint): a guessable intentId belonging to
        // another session must not leak that session's reservation outcome.
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(transactions.isOwnedBySession(eq(999L), eq(SESSION_KEY))).thenReturn(false);

        McpPrivateToolResponse<String> response = tool.getLibraryWaitStatus(SESSION_ID, 999L);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).isEqualTo("No library seat wait intent exists.");
        verify(transactions, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    private static LibraryReservationIntentView intentView(Long intentId, LibraryReservationIntentStatus status) {
        boolean terminal = status != LibraryReservationIntentStatus.RESERVING
                && status != LibraryReservationIntentStatus.WAITING_FOR_SEAT
                && status != LibraryReservationIntentStatus.REQUESTED;
        return new LibraryReservationIntentView(
                intentId,
                status,
                0,
                NOW,
                NOW.plusSeconds(300),
                terminal ? NOW : null,
                terminal ? status.name() : null,
                terminal ? "done" : null,
                3179L,
                77L);
    }
}
