package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.global.exception.SaintSessionExpiredException;

class RusaintExtendedConnectorsTests {

    private final PortalCookies cookies = new PortalCookies("{\"session\":\"json\"}");

    @Test
    void chapelDelegatesRequestedTermToClient() {
        StubRusaintClient client = new StubRusaintClient();

        ChapelInfo response = new RusaintChapelConnector(client)
                .fetchChapelInfo("20221528", cookies, 2025, "2학기");

        assertThat(response.year()).isEqualTo(2025);
        assertThat(client.studentId).isEqualTo("20221528");
        assertThat(client.sessionJson).isEqualTo("{\"session\":\"json\"}");
        assertThat(client.year).isEqualTo(2025);
        assertThat(client.semester).isEqualTo("2학기");
    }

    @Test
    void graduationAndScholarshipDelegateToClient() {
        StubRusaintClient client = new StubRusaintClient();

        GraduationStatus graduation = new RusaintGraduationConnector(client)
                .fetchGraduationRequirements("20221528", cookies);
        List<ScholarshipEntry> scholarships = new RusaintScholarshipConnector(client)
                .fetchScholarships("20221528", cookies);

        assertThat(graduation.department()).isEqualTo("컴퓨터학부");
        assertThat(scholarships).hasSize(1);
    }

    @Test
    void mapsClientFailuresToSessionExpired() {
        StubRusaintClient client = new StubRusaintClient();
        client.fail = true;

        assertThatThrownBy(() -> new RusaintChapelConnector(client)
                .fetchChapelInfo("20221528", cookies, null, null))
                .isInstanceOf(SaintSessionExpiredException.class);
        assertThatThrownBy(() -> new RusaintGraduationConnector(client)
                .fetchGraduationRequirements("20221528", cookies))
                .isInstanceOf(SaintSessionExpiredException.class);
        assertThatThrownBy(() -> new RusaintScholarshipConnector(client)
                .fetchScholarships("20221528", cookies))
                .isInstanceOf(SaintSessionExpiredException.class);
    }

    private static final class StubRusaintClient implements RusaintClient {
        private boolean fail;
        private String studentId;
        private String sessionJson;
        private Integer year;
        private String semester;

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
            throw new UnsupportedOperationException();
        }

        @Override
        public ChapelInfo fetchChapelInfo(String studentId, String sessionJson) {
            return fetchChapelInfo(studentId, sessionJson, null, null);
        }

        @Override
        public ChapelInfo fetchChapelInfo(String studentId, String sessionJson, Integer year, String semester) {
            failIfNeeded();
            this.studentId = studentId;
            this.sessionJson = sessionJson;
            this.year = year;
            this.semester = semester;
            return new ChapelInfo(year == null ? 2026 : year, semester, "", "", null, null, 0, "",
                    List.of(), List.of());
        }

        @Override
        public GraduationStatus fetchGraduationRequirements(String studentId, String sessionJson) {
            failIfNeeded();
            return new GraduationStatus(false, "학생", "컴퓨터학부", 3, 110, 133, List.of());
        }

        @Override
        public List<ScholarshipEntry> fetchScholarships(String studentId, String sessionJson) {
            failIfNeeded();
            return List.of(new ScholarshipEntry(2025, "2학기", "장학금", 1000, "지급", "완료"));
        }

        private void failIfNeeded() {
            if (fail) {
                throw new RusaintClientException("stub failure");
            }
        }
    }
}
