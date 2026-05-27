package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.CourseGrade;
import com.ssuai.domain.saint.dto.GpaSummary;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.domain.saint.dto.TermGpa;
import com.ssuai.global.exception.SaintSessionExpiredException;

class RusaintGradesConnectorTests {

    @Test
    void delegatesToRusaintClientWithStoredSessionJson() {
        StubRusaintClient client = new StubRusaintClient();
        RusaintGradesConnector connector = new RusaintGradesConnector(client);

        GradesResponse response = connector.fetchGrades(
                "20221528",
                new PortalCookies("{\"session\":\"json\"}"));

        assertThat(client.gradesStudentId).isEqualTo("20221528");
        assertThat(client.gradesSessionJson).isEqualTo("{\"session\":\"json\"}");
        assertThat(response.history()).hasSize(1);
        assertThat(response.detailsByTerm()).containsKey("2025-2학기");
    }

    @Test
    void mapsRusaintFailureToSessionExpired() {
        StubRusaintClient client = new StubRusaintClient();
        client.failGrades = true;
        RusaintGradesConnector connector = new RusaintGradesConnector(client);

        assertThatThrownBy(() -> connector.fetchGrades(
                "20221528",
                new PortalCookies("{\"session\":\"stale\"}")))
                .isInstanceOf(SaintSessionExpiredException.class)
                .hasMessageContaining("rusaint grades session rejected");
    }

    private static final class StubRusaintClient implements RusaintClient {
        private boolean failGrades;
        private String gradesStudentId;
        private String gradesSessionJson;

        @Override
        public RusaintAuthenticatedSession authenticateWithToken(String studentId, String ssoToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduleResponse fetchSchedule(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GradesResponse fetchGrades(String studentId, String sessionJson) {
            if (failGrades) {
                throw new RusaintClientException("stub grades failure");
            }
            gradesStudentId = studentId;
            gradesSessionJson = sessionJson;
            TermGpa term = new TermGpa(
                    2025,
                    "2학기",
                    18.0d,
                    18.0d,
                    3.0d,
                    3.5d,
                    63.0d,
                    85.0d,
                    "50/100",
                    "60/100",
                    false,
                    false,
                    false);
            GpaSummary summary = new GpaSummary(75.0d, 75.0d, 262.5d, 3.5d, 85.0d, 12.0d);
            return new GradesResponse(
                    List.of(term),
                    summary,
                    summary,
                    Map.of(term.termKey(), List.of(new CourseGrade(
                            "95",
                            "A0",
                            "자료구조",
                            "21500001",
                            3.0d,
                            "김교수",
                            ""))));
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
        public GraduationStatus fetchGraduationRequirements(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ScholarshipEntry> fetchScholarships(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }
    }
}
