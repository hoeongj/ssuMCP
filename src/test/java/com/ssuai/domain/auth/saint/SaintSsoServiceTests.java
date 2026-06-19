package com.ssuai.domain.auth.saint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.saint.connector.RusaintAuthenticatedSession;
import com.ssuai.domain.saint.connector.RusaintClient;
import com.ssuai.domain.saint.connector.RusaintClientException;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.global.exception.SaintAuthFailedException;

class SaintSsoServiceTests {

    private static final Instant T0 = Instant.parse("2026-05-16T10:00:00Z");

    private SaintSessionStore sessionStore;
    private StubRusaintClient rusaintClient;
    private SaintSsoService service;

    @BeforeEach
    void setUp() {
        SaintSessionProperties sessionProps = new SaintSessionProperties();
        sessionProps.setTtl(Duration.ofMinutes(30));
        sessionProps.setEncryptionKey("");
        sessionStore = new SaintSessionStore(
                sessionProps, Clock.fixed(T0, ZoneOffset.UTC), new SecureRandom());
        rusaintClient = new StubRusaintClient();
        service = new SaintSsoService(sessionStore, rusaintClient);
    }

    @Test
    void authenticateUsesRusaintTokenFlowAndStoresSessionJson() {
        UsaintAuthResult result = service.authenticate("sToken-one-shot", "20231234");

        assertThat(result.studentId()).isEqualTo("20231234");
        assertThat(result.name()).isEqualTo("테스트");
        assertThat(result.major()).isEqualTo("컴퓨터학부");
        assertThat(result.enrollmentStatus()).isNull();
        assertThat(rusaintClient.lastStudentId).isEqualTo("20231234");
        assertThat(rusaintClient.lastToken).isEqualTo("sToken-one-shot");
        assertThat(sessionStore.cookies("20231234"))
                .hasValueSatisfying(cookies -> {
                    assertThat(cookies.rawCookieHeader()).isEqualTo("{\"rusaint\":\"session\"}");
                    assertThat(cookies.sessionJson()).isEqualTo("{\"rusaint\":\"session\"}");
                });
    }

    @Test
    void blankInputIsRejectedBeforeRusaintCall() {
        assertThatThrownBy(() -> service.authenticate("  ", "20231234"))
                .isInstanceOf(SaintAuthFailedException.class)
                .hasMessageContaining("sToken");
        assertThatThrownBy(() -> service.authenticate("token", null))
                .isInstanceOf(SaintAuthFailedException.class)
                .hasMessageContaining("sIdno");

        assertThat(rusaintClient.callCount).isZero();
    }

    @Test
    void rusaintFailureFailsAuthAndDoesNotStoreSession() {
        rusaintClient.fail = true;

        assertThatThrownBy(() -> service.authenticate("bad", "20231234"))
                .isInstanceOf(SaintAuthFailedException.class)
                .hasMessageContaining("rusaint token authentication failed");
        assertThat(sessionStore.cookies("20231234")).isEmpty();
    }

    private static final class StubRusaintClient implements RusaintClient {
        private int callCount;
        private boolean fail;
        private String lastStudentId;
        private String lastToken;

        @Override
        public RusaintAuthenticatedSession authenticateWithToken(String studentId, String ssoToken) {
            callCount++;
            lastStudentId = studentId;
            lastToken = ssoToken;
            if (fail) {
                throw new RusaintClientException("stub failure", new RuntimeException("upstream"));
            }
            return new RusaintAuthenticatedSession(
                    studentId,
                    "테스트",
                    "컴퓨터학부",
                    null,
                    "{\"rusaint\":\"session\"}");
        }

        @Override
        public ScheduleResponse fetchSchedule(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GradesResponse fetchGrades(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChapelInfo fetchChapelInfo(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChapelInfo fetchChapelInfo(String studentId, String sessionJson, Integer year, String semester) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countChapelPassedSemesters(String studentId, String sessionJson, int entryYear) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraduationStatus fetchGraduationRequirements(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ScholarshipEntry> fetchScholarships(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }
    }
}
