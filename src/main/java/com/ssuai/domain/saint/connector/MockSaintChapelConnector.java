package com.ssuai.domain.saint.connector;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ChapelAbsenceApplication;
import com.ssuai.domain.saint.dto.ChapelAttendanceEntry;
import com.ssuai.domain.saint.dto.ChapelInfo;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-chapel",
        havingValue = "mock", matchIfMissing = true)
class MockSaintChapelConnector implements SaintChapelConnector {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    @Autowired
    MockSaintChapelConnector() {
        this(Clock.systemUTC());
    }

    MockSaintChapelConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ChapelInfo fetchChapelInfo(String studentId, PortalCookies cookies, Integer year, String semester) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        int selectedYear = year == null ? today.getYear() : year;
        String selectedSemester = semester == null
                ? (today.getMonthValue() <= 8 ? "1학기" : "2학기")
                : semester;
        return new ChapelInfo(
                selectedYear,
                selectedSemester,
                "목 10:30-11:20",
                "한경직기념관 대예배실",
                null,
                null,
                1,
                "진행중",
                List.of(
                        new ChapelAttendanceEntry("2026-03-12", "개강채플", "담당교목", "출석"),
                        new ChapelAttendanceEntry("2026-03-19", "공동체채플", "담당교목", "결석")),
                List.of(new ChapelAbsenceApplication(
                        "병무관계", "2026.05.14", "2026.05.20", "예비군", "승인")));
    }
}
