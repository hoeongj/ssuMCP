package com.ssuai.domain.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.action.ActionService.ActionExpiredException;
import com.ssuai.domain.action.ActionService.NoPendingActionException;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.connector.LmsMaterialsConnector;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsExportConfirmResponse;
import com.ssuai.domain.lms.dto.LmsExportPrepareResponse;
import com.ssuai.domain.lms.dto.LmsExportSelectionItem;
import com.ssuai.domain.lms.dto.LmsMaterial;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.dto.SelectionPayload;
import com.ssuai.domain.lms.export.LmsExportJob;
import com.ssuai.domain.lms.export.LmsExportJobRepository;
import com.ssuai.domain.lms.export.LmsExportProperties;

class LmsMaterialExportServiceTests {

    private LmsMaterialsConnector connector;
    private LmsSessionStore sessionStore;
    private LmsAssignmentsService assignmentsService;
    private ActionService actionService;
    private LmsExportJobRepository jobRepository;
    private LmsExportProperties properties;
    private ObjectMapper objectMapper;
    private LmsMaterialExportService service;

    private static final String STUDENT_ID = "20221528";
    private static final LmsCookies COOKIES = new LmsCookies("xn_api_token=xyz;");

    @BeforeEach
    void setUp() {
        connector = mock(LmsMaterialsConnector.class);
        sessionStore = mock(LmsSessionStore.class);
        assignmentsService = mock(LmsAssignmentsService.class);
        actionService = mock(ActionService.class);
        jobRepository = mock(LmsExportJobRepository.class);
        properties = new LmsExportProperties();
        properties.setMaxFilesPerExport(2);
        properties.setMaxBytesPerExport(100L);
        properties.setDownloadTtl(Duration.ofMinutes(20));
        properties.setPublicBaseUrl("https://ssumcp.test");
        objectMapper = new ObjectMapper();

        service = new LmsMaterialExportService(
                connector, sessionStore, assignmentsService, actionService,
                jobRepository, properties, objectMapper
        );

        when(sessionStore.cookies(STUDENT_ID)).thenReturn(Optional.of(COOKIES));
    }

    @Test
    void prepare_excludesNonWhitelistedAndOversizedCorrectly() {
        // Given
        LmsCourse course = new LmsCourse(1L, "Math", "MATH101", 100L);
        List<LmsTermItem> terms = List.of(new LmsTermItem(100L, "Term", null, null, true));
        when(assignmentsService.fetchTerms(STUDENT_ID)).thenReturn(terms);
        when(connector.fetchCourses(STUDENT_ID, COOKIES, 100L)).thenReturn(List.of(course));

        LmsMaterial mat1 = new LmsMaterial("c1", 1L, "Math", "a.pdf", "pdf", 40L, "Week 1", "Lecture 1");
        LmsMaterial mat2 = new LmsMaterial("c2", 1L, "Math", "b.pdf", "pdf", 50L, "Week 1", "Lecture 2");
        LmsMaterial mat3 = new LmsMaterial("c3", 1L, "Math", "c.mp4", "mp4", 10L, "Week 1", "Lecture 3"); // excluded - non whitelisted
        LmsMaterial mat4 = new LmsMaterial("c4", 1L, "Math", "d.pdf", "pdf", 30L, "Week 1", "Lecture 4"); // excluded - size overflow (exceeds 100 limit, 40+50=90, next is 30 -> 120)
        when(connector.fetchMaterials(STUDENT_ID, COOKIES, course)).thenReturn(List.of(mat1, mat2, mat3, mat4));

        // When
        LmsExportPrepareResponse resp = service.prepare(STUDENT_ID, 100L, List.of("c1", "c2", "c3", "c4", "invalid"));

        // Then
        assertThat(resp.fileCount()).isEqualTo(2);
        assertThat(resp.totalBytes()).isEqualTo(90L);
        assertThat(resp.selected()).hasSize(1);
        assertThat(resp.excluded()).hasSize(3);

        assertThat(resp.excluded().get(0).contentId()).isEqualTo("c3");
        assertThat(resp.excluded().get(0).reason()).contains("지원하지 않는 파일 형식");

        assertThat(resp.excluded().get(1).contentId()).isEqualTo("invalid");
        assertThat(resp.excluded().get(1).reason()).isEqualTo("파일을 찾을 수 없습니다.");

        assertThat(resp.excluded().get(2).contentId()).isEqualTo("c4");
        assertThat(resp.excluded().get(2).reason()).isEqualTo("한도 초과");

        verify(actionService).createPendingAction(eq(STUDENT_ID), eq("LMS_MATERIAL_EXPORT"), any(SelectionPayload.class));
    }

    @Test
    void prepare_withAllInvalidSelections_returnsEmptyAndCreatesNoPendingAction() {
        // Given — every requested contentId is unknown, so nothing is whitelisted/accepted.
        LmsCourse course = new LmsCourse(1L, "Math", "MATH101", 100L);
        List<LmsTermItem> terms = List.of(new LmsTermItem(100L, "Term", null, null, true));
        when(assignmentsService.fetchTerms(STUDENT_ID)).thenReturn(terms);
        when(connector.fetchCourses(STUDENT_ID, COOKIES, 100L)).thenReturn(List.of(course));
        LmsMaterial mat1 = new LmsMaterial("c1", 1L, "Math", "a.pdf", "pdf", 40L, "Week 1", "Lecture 1");
        when(connector.fetchMaterials(STUDENT_ID, COOKIES, course)).thenReturn(List.of(mat1));

        // When
        LmsExportPrepareResponse resp = service.prepare(STUDENT_ID, 100L, List.of("nope1", "nope2"));

        // Then — empty preview, clear message, and crucially NO pending action (so confirm()
        // cannot mint a 0-file job + download URL).
        assertThat(resp.fileCount()).isZero();
        assertThat(resp.selected()).isEmpty();
        assertThat(resp.excluded()).hasSize(2);
        assertThat(resp.message()).contains("내보낼 수 있는 파일이 없습니다");
        verify(actionService, never()).createPendingAction(any(), any(), any());
    }

    @Test
    void confirm_createsJobWithHashedToken() throws Exception {
        // Given
        ActionAudit claimed = mock(ActionAudit.class);
        when(actionService.claimPendingAction(STUDENT_ID)).thenReturn(claimed);
        
        SelectionPayload payload = new SelectionPayload(List.of(
                new LmsExportSelectionItem("c1", 1L, "Math", "a.pdf")
        ), 4096L);
        when(actionService.payload(claimed, SelectionPayload.class)).thenReturn(payload);

        ArgumentCaptor<LmsExportJob> jobCaptor = ArgumentCaptor.forClass(LmsExportJob.class);
        when(jobRepository.save(jobCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        LmsExportConfirmResponse resp = service.confirm(STUDENT_ID);

        // Then
        LmsExportJob savedJob = jobCaptor.getValue();
        assertThat(resp.jobId()).isEqualTo(savedJob.getId());
        assertThat(resp.downloadUrl()).contains("/api/lms/exports/" + savedJob.getId() + "/download?token=");
        assertThat(resp.estimatedBytes()).isEqualTo(4096L); // carried through from prepare-time SelectionPayload

        // Verify the raw token is not stored, but rather its SHA-256 hash
        String rawToken = resp.downloadUrl().substring(resp.downloadUrl().indexOf("token=") + 6);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
        String expectedHash = HexFormat.of().formatHex(hashed);

        assertThat(savedJob.getTokenHash()).isEqualTo(expectedHash);
        assertThat(savedJob.getTokenHash()).isNotEqualTo(rawToken);

        verify(actionService).completeAction(eq(claimed), eq(ActionService.OUTCOME_SUCCESS), any());
    }

    @Test
    void confirm_throwsGracefullyOnNoPendingAction() {
        when(actionService.claimPendingAction(STUDENT_ID)).thenThrow(new NoPendingActionException());
        assertThatThrownBy(() -> service.confirm(STUDENT_ID))
                .isInstanceOf(NoPendingActionException.class);
    }

    @Test
    void confirm_throwsGracefullyOnExpiredAction() {
        when(actionService.claimPendingAction(STUDENT_ID)).thenThrow(new ActionExpiredException());
        assertThatThrownBy(() -> service.confirm(STUDENT_ID))
                .isInstanceOf(ActionExpiredException.class);
    }

    @Test
    void exportAll_includesAllEligibleMaterials() {
        // Given
        LmsCourse course1 = new LmsCourse(1L, "Math", "MATH101", 100L);
        LmsCourse course2 = new LmsCourse(2L, "Physics", "PHYS101", 100L);
        List<LmsTermItem> terms = List.of(new LmsTermItem(100L, "Term Label", null, null, true));
        when(assignmentsService.fetchTerms(STUDENT_ID)).thenReturn(terms);
        when(connector.fetchCourses(STUDENT_ID, COOKIES, 100L)).thenReturn(List.of(course1, course2));

        LmsMaterial mat1 = new LmsMaterial("c1", 1L, "Math", "a.pdf", "pdf", 10L, "Week 1", "Lecture 1");
        LmsMaterial mat2 = new LmsMaterial("c2", 1L, "Math", "b.pdf", "pdf", 10L, "Week 1", "Lecture 2");
        when(connector.fetchMaterials(STUDENT_ID, COOKIES, course1)).thenReturn(List.of(mat1, mat2));

        LmsMaterial mat3 = new LmsMaterial("c3", 2L, "Physics", "c.pdf", "pdf", 10L, "Week 1", "Lecture 1");
        LmsMaterial mat4 = new LmsMaterial("c4", 2L, "Physics", "d.pdf", "pdf", 10L, "Week 1", "Lecture 2");
        when(connector.fetchMaterials(STUDENT_ID, COOKIES, course2)).thenReturn(List.of(mat3, mat4));

        properties.setMaxFilesPerExport(10); // set high so all are included

        // When
        LmsExportPrepareResponse resp = service.exportAll(STUDENT_ID, 100L);

        // Then
        assertThat(resp.fileCount()).isEqualTo(4);
        assertThat(resp.totalBytes()).isEqualTo(40L);
        assertThat(resp.selected()).hasSize(2); // 2 courses
        assertThat(resp.excluded()).isEmpty();
        assertThat(resp.message()).contains("[Term Label]");
    }

    @Test
    void exportAll_respectsFileLimitCap() {
        // Given
        LmsCourse course1 = new LmsCourse(1L, "Math", "MATH101", 100L);
        List<LmsTermItem> terms = List.of(new LmsTermItem(100L, "Term Label", null, null, true));
        when(assignmentsService.fetchTerms(STUDENT_ID)).thenReturn(terms);
        when(connector.fetchCourses(STUDENT_ID, COOKIES, 100L)).thenReturn(List.of(course1));

        LmsMaterial mat1 = new LmsMaterial("c1", 1L, "Math", "a.pdf", "pdf", 10L, "Week 1", "Lecture 1");
        LmsMaterial mat2 = new LmsMaterial("c2", 1L, "Math", "b.pdf", "pdf", 10L, "Week 1", "Lecture 2");
        LmsMaterial mat3 = new LmsMaterial("c3", 1L, "Math", "c.pdf", "pdf", 10L, "Week 1", "Lecture 3");
        when(connector.fetchMaterials(STUDENT_ID, COOKIES, course1)).thenReturn(List.of(mat1, mat2, mat3));

        properties.setMaxFilesPerExport(2); // only allow 2 files

        // When
        LmsExportPrepareResponse resp = service.exportAll(STUDENT_ID, 100L);

        // Then
        assertThat(resp.fileCount()).isEqualTo(2);
        assertThat(resp.excluded()).hasSize(1);
        assertThat(resp.excluded().get(0).reason()).isEqualTo("한도 초과");
    }

    @Test
    void exportAll_createsPendingActionWithCorrectType() {
        // Given
        LmsCourse course1 = new LmsCourse(1L, "Math", "MATH101", 100L);
        List<LmsTermItem> terms = List.of(new LmsTermItem(100L, "Term Label", null, null, true));
        when(assignmentsService.fetchTerms(STUDENT_ID)).thenReturn(terms);
        when(connector.fetchCourses(STUDENT_ID, COOKIES, 100L)).thenReturn(List.of(course1));

        LmsMaterial mat1 = new LmsMaterial("c1", 1L, "Math", "a.pdf", "pdf", 10L, "Week 1", "Lecture 1");
        when(connector.fetchMaterials(STUDENT_ID, COOKIES, course1)).thenReturn(List.of(mat1));

        // When
        service.exportAll(STUDENT_ID, 100L);

        // Then
        verify(actionService).createPendingAction(eq(STUDENT_ID), eq("LMS_MATERIAL_EXPORT"), any(SelectionPayload.class));
    }
}
