package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.ScheduleEntry;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.domain.saint.dto.TermSchedule;
import com.ssuai.global.exception.SaintSessionExpiredException;

class RusaintScheduleConnectorTests {

    @Test
    void delegatesToRusaintClientWithStoredSessionJson() {
        StubRusaintClient client = new StubRusaintClient();
        RusaintScheduleConnector connector = new RusaintScheduleConnector(client);

        ScheduleResponse response = connector.fetchSchedule(
                "20221528",
                new PortalCookies("{\"session\":\"json\"}"));

        assertThat(client.scheduleStudentId).isEqualTo("20221528");
        assertThat(client.scheduleSessionJson).isEqualTo("{\"session\":\"json\"}");
        assertThat(response.currentYear()).isEqualTo(2026);
        assertThat(response.terms()).hasSize(1);
    }

    @Test
    void mapsRusaintFailureToSessionExpired() {
        StubRusaintClient client = new StubRusaintClient();
        client.failSchedule = true;
        RusaintScheduleConnector connector = new RusaintScheduleConnector(client);

        assertThatThrownBy(() -> connector.fetchSchedule(
                "20221528",
                new PortalCookies("{\"session\":\"stale\"}")))
                .isInstanceOf(SaintSessionExpiredException.class)
                .hasMessageContaining("rusaint schedule session rejected");
    }

    private static final class StubRusaintClient implements RusaintClient {
        private boolean failSchedule;
        private String scheduleStudentId;
        private String scheduleSessionJson;

        @Override
        public RusaintAuthenticatedSession authenticateWithToken(String studentId, String ssoToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduleResponse fetchSchedule(String studentId, String sessionJson) {
            if (failSchedule) {
                throw new RusaintClientException("stub schedule failure");
            }
            scheduleStudentId = studentId;
            scheduleSessionJson = sessionJson;
            return new ScheduleResponse(
                    2022,
                    2026,
                    1,
                    List.of(new TermSchedule(
                            2026,
                            1,
                            List.of(new ScheduleEntry(
                                    1,
                                    "월",
                                    3,
                                    "10:30-11:45",
                                    "자료구조",
                                    "김교수",
                                    "정보과학관 30100")))));
        }

        @Override
        public com.ssuai.domain.saint.dto.GradesResponse fetchGrades(String studentId, String sessionJson) {
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
        public GraduationStatus fetchGraduationRequirements(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ScholarshipEntry> fetchScholarships(String studentId, String sessionJson) {
            throw new UnsupportedOperationException();
        }
    }
}
