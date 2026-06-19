package com.ssuai.domain.saint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintChapelConnector;
import com.ssuai.domain.saint.connector.SaintGraduationConnector;
import com.ssuai.domain.saint.dto.GraduationRequirementItem;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.global.exception.SaintSessionExpiredException;

class SaintGraduationServiceTests {

    private static final PortalCookies COOKIES = new PortalCookies("session-json");
    private static final String STUDENT_ID = "20241234";

    private final SaintGraduationConnector connector = mock(SaintGraduationConnector.class);
    private final SaintChapelConnector chapelConnector = mock(SaintChapelConnector.class);
    private final SaintSessionStore sessionStore = mock(SaintSessionStore.class);
    private final SaintGraduationService service =
            new SaintGraduationService(connector, chapelConnector, sessionStore);

    @BeforeEach
    void setUp() {
        when(sessionStore.cookies(STUDENT_ID)).thenReturn(Optional.of(COOKIES));
    }

    @Test
    void delegatesWithStoredSession() {
        when(connector.fetchGraduationRequirements(STUDENT_ID, COOKIES))
                .thenReturn(statusWithChapel(0f, 0f));
        when(chapelConnector.countChapelPassedSemesters(any(), any(), anyInt()))
                .thenReturn(0);

        service.fetchGraduationRequirements(STUDENT_ID);

        verify(connector).fetchGraduationRequirements(STUDENT_ID, COOKIES);
    }

    @Test
    void missingSessionRaisesSessionExpiredWithoutCallingConnector() {
        when(sessionStore.cookies(STUDENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fetchGraduationRequirements(STUDENT_ID))
                .isInstanceOf(SaintSessionExpiredException.class);

        verifyNoInteractions(connector);
        verifyNoInteractions(chapelConnector);
    }

    @Test
    void chapelGateWithZerosMergesCountedSemesters() {
        when(connector.fetchGraduationRequirements(STUDENT_ID, COOKIES))
                .thenReturn(statusWithChapel(0f, 0f));
        when(chapelConnector.countChapelPassedSemesters(eq(STUDENT_ID), eq(COOKIES), anyInt()))
                .thenReturn(1);

        GraduationStatus result = service.fetchGraduationRequirements(STUDENT_ID);

        GraduationRequirementItem chapel = chapelItem(result);
        assertThat(chapel.required()).isEqualTo(6.0f);
        assertThat(chapel.completed()).isEqualTo(1.0f);
        assertThat(chapel.satisfied()).isFalse();
    }

    @Test
    void chapelGateWithFivePassedSemestersShowsFiveCompleted() {
        when(connector.fetchGraduationRequirements(STUDENT_ID, COOKIES))
                .thenReturn(statusWithChapelGrade(0f, 0f, 3));
        when(chapelConnector.countChapelPassedSemesters(eq(STUDENT_ID), eq(COOKIES), anyInt()))
                .thenReturn(5);

        GraduationStatus result = service.fetchGraduationRequirements(STUDENT_ID);

        GraduationRequirementItem chapel = chapelItem(result);
        assertThat(chapel.completed()).isEqualTo(5.0f);
        assertThat(chapel.satisfied()).isFalse(); // 5 < 6
    }

    @Test
    void chapelGateAlreadyPopulatedByRusaintIsNotOverridden() {
        when(connector.fetchGraduationRequirements(STUDENT_ID, COOKIES))
                .thenReturn(statusWithChapel(6f, 6f));

        service.fetchGraduationRequirements(STUDENT_ID);

        verify(chapelConnector, never()).countChapelPassedSemesters(any(), any(), anyInt());
    }

    @Test
    void noChapelItemInRequirementsIsPassedThroughUnchanged() {
        GraduationStatus noChapel = new GraduationStatus(
                false, "테스트", "컴퓨터학부", 1, 10f, 133f,
                List.of(new GraduationRequirementItem("전공", "전공", 45f, 45f, 0f, true)));
        when(connector.fetchGraduationRequirements(STUDENT_ID, COOKIES)).thenReturn(noChapel);

        service.fetchGraduationRequirements(STUDENT_ID);

        verify(chapelConnector, never()).countChapelPassedSemesters(any(), any(), anyInt());
    }

    @Test
    void chapelConnectorFailureKeepsZeroGateUnchanged() {
        when(connector.fetchGraduationRequirements(STUDENT_ID, COOKIES))
                .thenReturn(statusWithChapel(0f, 0f));
        when(chapelConnector.countChapelPassedSemesters(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("rusaint unavailable"));

        GraduationStatus result = service.fetchGraduationRequirements(STUDENT_ID);

        GraduationRequirementItem chapel = chapelItem(result);
        assertThat(chapel.required()).isEqualTo(0f);
        assertThat(chapel.completed()).isEqualTo(0f);
    }

    private static GraduationStatus statusWithChapel(float required, float completed) {
        return statusWithChapelGrade(required, completed, 1);
    }

    private static GraduationStatus statusWithChapelGrade(float required, float completed, int grade) {
        return new GraduationStatus(
                false, "테스트", "컴퓨터학부", grade, 10f, 133f,
                List.of(new GraduationRequirementItem("채플이수", "이수", required, completed, 0f, false)));
    }

    private static GraduationRequirementItem chapelItem(GraduationStatus status) {
        return status.requirements().stream()
                .filter(r -> r.name().contains("채플"))
                .findFirst()
                .orElseThrow();
    }
}
