package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.GraduationStatus;

class MockSaintGraduationConnectorTests {

    @Test
    void returnsSatisfiedAndOutstandingRequirements() {
        MockSaintGraduationConnector connector = new MockSaintGraduationConnector();

        GraduationStatus response = connector.fetchGraduationRequirements(
                "20241234", new PortalCookies("MYSAPSSO2=abc"));

        assertThat(response.isGraduatable()).isFalse();
        assertThat(response.requirements())
                .anyMatch(requirement -> requirement.satisfied() && requirement.remaining() == 0.0f)
                .anyMatch(requirement -> !requirement.satisfied() && requirement.remaining() > 0.0f);
    }
}
