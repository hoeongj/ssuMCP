package com.ssuai.domain.saint.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.GraduationRequirementItem;
import com.ssuai.domain.saint.dto.GraduationStatus;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-graduation",
        havingValue = "mock", matchIfMissing = true)
class MockSaintGraduationConnector implements SaintGraduationConnector {

    @Override
    public GraduationStatus fetchGraduationRequirements(String studentId, PortalCookies cookies) {
        return new GraduationStatus(
                false,
                "테스트 학생",
                "컴퓨터학부",
                3,
                110.0f,
                133.0f,
                List.of(
                        new GraduationRequirementItem("전공", "전공", 45.0f, 45.0f, 0.0f, true),
                        new GraduationRequirementItem("졸업학점", "전체", 133.0f, 110.0f, 23.0f, false)));
    }
}
