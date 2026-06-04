package com.ssuai.domain.saint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.saint.dto.GpaSimulationResponse;
import com.ssuai.domain.saint.dto.GpaSummary;
import com.ssuai.domain.saint.dto.GradesResponse;

class SaintGpaSimulationServiceTests {

    private final SaintGradesService gradesService = mock(SaintGradesService.class);
    private final SaintGpaSimulationService service = new SaintGpaSimulationService(gradesService);

    @Test
    void projectsGpaUsingGpaCreditsNotEarnedCredits() {
        when(gradesService.fetchGrades("20221528")).thenReturn(grades(89.0d, 20.0d, 229.2d, 3.3269d));

        GpaSimulationResponse response = service.simulate("20221528", 18.0d, 4.0d, 3.45d);

        assertThat(response.currentGpaCredits()).isEqualTo(69.0d);
        assertThat(response.projectedGpa()).isEqualTo(3.4621d);
        assertThat(response.requiredGradePointAverage()).isEqualTo(3.9417d);
        assertThat(response.achievable()).isTrue();
        // maxAchievableGpa: (229.2 + 18 * 4.5) / (69 + 18) = 310.2 / 87 = 3.5655...
        assertThat(response.maxAchievableGpa()).isEqualTo(3.5655d);
    }

    @Test
    void reportsUnachievableWhenRequiredAverageExceedsMaxGradePoint() {
        when(gradesService.fetchGrades("20221528")).thenReturn(grades(89.0d, 20.0d, 229.2d, 3.3269d));

        GpaSimulationResponse response = service.simulate("20221528", 3.0d, null, 4.0d);

        assertThat(response.projectedGpa()).isNull();
        assertThat(response.requiredGradePointAverage()).isGreaterThan(4.5d);
        assertThat(response.achievable()).isFalse();
        // maxAchievableGpa: (229.2 + 3 * 4.5) / (69 + 3) = 242.7 / 72 = 3.3708...
        assertThat(response.maxAchievableGpa()).isEqualTo(3.3708d);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> service.simulate("20221528", 0.0d, 4.0d, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.simulate("20221528", 18.0d, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.simulate("20221528", 18.0d, 4.6d, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GradesResponse grades(double earnedCredits, double passFailCredits, double gpaSum, double gpa) {
        GpaSummary summary = new GpaSummary(earnedCredits, earnedCredits, gpaSum, gpa, 0.0d, passFailCredits);
        return new GradesResponse(List.of(), summary, summary, Map.of());
    }
}
