package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScholarshipEntry;

class MockSaintScholarshipConnectorTests {

    @Test
    void returnsMultipleScholarshipEntries() {
        MockSaintScholarshipConnector connector = new MockSaintScholarshipConnector();

        List<ScholarshipEntry> response = connector.fetchScholarships(
                "20241234", new PortalCookies("MYSAPSSO2=abc"));

        assertThat(response).hasSize(2);
        assertThat(response).extracting(ScholarshipEntry::year).containsExactly(2025, 2024);
    }
}
