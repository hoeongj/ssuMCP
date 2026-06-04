package com.ssuai.domain.saint.service;

import org.springframework.stereotype.Service;

import com.ssuai.domain.saint.dto.GpaSimulationResponse;
import com.ssuai.domain.saint.dto.GpaSummary;
import com.ssuai.domain.saint.dto.GradesResponse;

@Service
public class SaintGpaSimulationService {

    private static final double MAX_GRADE_POINT = 4.5d;

    private final SaintGradesService gradesService;

    public SaintGpaSimulationService(SaintGradesService gradesService) {
        this.gradesService = gradesService;
    }

    public GpaSimulationResponse simulate(
            String studentId,
            double plannedCredits,
            Double plannedGradePointAverage,
            Double targetGpa
    ) {
        if (plannedCredits <= 0.0d) {
            throw new IllegalArgumentException("plannedCredits must be positive");
        }
        if (plannedGradePointAverage == null && targetGpa == null) {
            throw new IllegalArgumentException("plannedGradePointAverage or targetGpa is required");
        }
        validateGradePoint(plannedGradePointAverage, "plannedGradePointAverage");
        validateGradePoint(targetGpa, "targetGpa");

        GradesResponse grades = gradesService.fetchGrades(studentId);
        GpaSummary summary = grades.academicRecord();
        double currentGpaCredits = summary.gpaCredits();
        double currentGpaSum = summary.gpaSum();

        Double projected = plannedGradePointAverage == null
                ? null
                : round((currentGpaSum + plannedCredits * plannedGradePointAverage)
                        / (currentGpaCredits + plannedCredits));

        Double required = null;
        Boolean achievable = null;
        if (targetGpa != null) {
            double rawRequired = (targetGpa * (currentGpaCredits + plannedCredits) - currentGpaSum)
                    / plannedCredits;
            required = round(Math.max(0.0d, rawRequired));
            achievable = required <= MAX_GRADE_POINT;
        }

        double maxAchievableGpa = round(
                (currentGpaSum + plannedCredits * MAX_GRADE_POINT) / (currentGpaCredits + plannedCredits));

        return new GpaSimulationResponse(
                round(summary.gpa()),
                round(currentGpaCredits),
                round(currentGpaSum),
                round(plannedCredits),
                plannedGradePointAverage == null ? null : round(plannedGradePointAverage),
                projected,
                targetGpa == null ? null : round(targetGpa),
                required,
                achievable,
                MAX_GRADE_POINT,
                maxAchievableGpa
        );
    }

    private static void validateGradePoint(Double value, String name) {
        if (value != null && (value < 0.0d || value > MAX_GRADE_POINT)) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 4.5");
        }
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
