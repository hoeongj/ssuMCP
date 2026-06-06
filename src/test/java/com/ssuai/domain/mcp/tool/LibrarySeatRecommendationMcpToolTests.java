package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.recommendation.LibrarySeatAttributes;
import com.ssuai.domain.library.recommendation.LibrarySeatPreference;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendation;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationResponse;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationService;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class LibrarySeatRecommendationMcpToolTests {

    private static final String SESSION_ID = "test-session-library";
    private static final String OPAQUE_KEY = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final Instant EXPIRES = Instant.parse("2026-06-06T10:00:00Z");

    private LibrarySeatRecommendationService recommendationService;
    private McpAuthHelper authHelper;
    private LibrarySeatRecommendationMcpTool tool;

    @BeforeEach
    void setUp() {
        recommendationService = mock(LibrarySeatRecommendationService.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibrarySeatRecommendationMcpTool(recommendationService, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<LibrarySeatRecommendationResponse> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<LibrarySeatRecommendationResponse>buildAuthRequired(null, McpProviderType.LIBRARY))
                .thenReturn(stub);

        McpPrivateToolResponse<LibrarySeatRecommendationResponse> response =
                tool.recommendLibrarySeats(2, true, null, null, null, null, null, 5, null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verify(recommendationService, never()).recommend(any(), any(), any(), any());
    }

    @Test
    void returnsRecommendationsForLinkedLibrarySession() {
        LibrarySeatPreference preference = new LibrarySeatPreference(true, true, false, null, null, false);
        LibrarySeatRecommendationResponse stub = recommendationResponse();
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(recommendationService.recommend(LibraryFloor.F2, OPAQUE_KEY, preference, 3))
                .thenReturn(stub);

        McpPrivateToolResponse<LibrarySeatRecommendationResponse> response =
                tool.recommendLibrarySeats(2, true, true, false, null, null, false, 3, SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).isSameAs(stub);
        verify(recommendationService).recommend(LibraryFloor.F2, OPAQUE_KEY, preference, 3);
    }

    @Test
    void expiredLibraryTokenReturnsAuthRequiredForRelinking() {
        McpPrivateToolResponse<LibrarySeatRecommendationResponse> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(recommendationService.recommend(any(), any(), any(), any()))
                .thenThrow(new LibraryAuthRequiredException());
        when(authHelper.<LibrarySeatRecommendationResponse>buildAuthRequired(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(stub);

        McpPrivateToolResponse<LibrarySeatRecommendationResponse> response =
                tool.recommendLibrarySeats(2, true, null, null, null, null, null, null, SESSION_ID);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void rejectsUnsupportedFloorBeforeAuthentication() {
        assertThatThrownBy(() ->
                tool.recommendLibrarySeats(4, null, null, null, null, null, null, null, SESSION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floor");
        verify(authHelper, never()).principalKey(any(), any());
    }

    @Test
    void connectorErrorMapsToFriendlyMessage() {
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(recommendationService.recommend(any(), any(), any(), any()))
                .thenThrow(new ConnectorTimeoutException());

        assertThatThrownBy(() ->
                tool.recommendLibrarySeats(2, true, null, null, null, null, null, null, SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("library seats");
    }

    private static LibrarySeatRecommendationResponse recommendationResponse() {
        return new LibrarySeatRecommendationResponse(
                2,
                "2F",
                3,
                1,
                1,
                4,
                1,
                "live_seat_items",
                "Recommendations are ranked by live availability and the requested seat preferences.",
                List.of(new LibrarySeatRecommendation(
                "2-A-001",
                "1",
                "A-1",
                "open-reading-2f",
                "Open Reading Room 2F",
                "2F A Zone",
                "general",
                "all",
                "available",
                150,
                        List.of("window", "outlet"),
                        List.of(),
                        new LibrarySeatAttributes(true, true, false, true, true, false),
                        null)));
    }
}
