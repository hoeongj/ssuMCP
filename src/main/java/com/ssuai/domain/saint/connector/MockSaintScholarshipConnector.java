package com.ssuai.domain.saint.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScholarshipEntry;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-scholarship",
        havingValue = "mock", matchIfMissing = true)
class MockSaintScholarshipConnector implements SaintScholarshipConnector {

    @Override
    public List<ScholarshipEntry> fetchScholarships(String studentId, PortalCookies cookies) {
        return List.of(
                new ScholarshipEntry(2025, "2학기", "성적우수장학금", 1_500_000L, "학비감면", "지급완료"),
                new ScholarshipEntry(2024, "1학기", "교내근로장학금", 600_000L, "계좌지급", "지급완료"));
    }
}
