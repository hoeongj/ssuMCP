package com.ssuai.domain.saint.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SaintAcademicDtoTests {

    @Test
    void courseGradePointUsesOfficialSsuScale() {
        assertThat(new CourseGrade("97", "A+", "", "", 3.0d, "", "").gradePoint()).isEqualTo(4.5d);
        assertThat(new CourseGrade("94", "Ao", "", "", 3.0d, "", "").gradePoint()).isEqualTo(4.3d);
        assertThat(new CourseGrade("90", "A", "", "", 3.0d, "", "").gradePoint()).isEqualTo(4.0d);
        assertThat(new CourseGrade("87", "B+", "", "", 3.0d, "", "").gradePoint()).isEqualTo(3.5d);
        assertThat(new CourseGrade("84", "Bo", "", "", 3.0d, "", "").gradePoint()).isEqualTo(3.3d);
        assertThat(new CourseGrade("80", "B", "", "", 3.0d, "", "").gradePoint()).isEqualTo(3.0d);
        assertThat(new CourseGrade("77", "C+", "", "", 3.0d, "", "").gradePoint()).isEqualTo(2.5d);
        assertThat(new CourseGrade("74", "Co", "", "", 3.0d, "", "").gradePoint()).isEqualTo(2.3d);
        assertThat(new CourseGrade("70", "C", "", "", 3.0d, "", "").gradePoint()).isEqualTo(2.0d);
        assertThat(new CourseGrade("67", "D+", "", "", 3.0d, "", "").gradePoint()).isEqualTo(1.5d);
        assertThat(new CourseGrade("64", "Do", "", "", 3.0d, "", "").gradePoint()).isEqualTo(1.3d);
        assertThat(new CourseGrade("60", "D", "", "", 3.0d, "", "").gradePoint()).isEqualTo(1.0d);
        assertThat(new CourseGrade("59", "F", "", "", 3.0d, "", "").gradePoint()).isEqualTo(0.0d);
    }

    @Test
    void courseGradePointFallsBackToNumericScoreWhenLetterIsMissing() {
        assertThat(new CourseGrade("96", "", "", "", 3.0d, "", "").gradePoint()).isEqualTo(4.3d);
        assertThat(new CourseGrade("86", "", "", "", 3.0d, "", "").gradePoint()).isEqualTo(3.3d);
        assertThat(new CourseGrade("66", "", "", "", 3.0d, "", "").gradePoint()).isEqualTo(1.3d);
    }

    @Test
    void passFailCourseHasNoGradePoint() {
        assertThat(new CourseGrade("P", "P", "", "", 0.5d, "", "").gradePoint()).isNull();
    }

    @Test
    void gpaCreditsExcludePassFailCredits() {
        assertThat(new GpaSummary(92.0d, 89.0d, 229.2d, 3.3269d, 84.0d, 20.0d).gpaCredits())
                .isEqualTo(69.0d);
        assertThat(new TermGpa(2025, "winter", 3.0d, 3.0d, 3.0d, null, 0.0d, 0.0d,
                "0/0", "0/0", false, false, false).gpaCredits())
                .isEqualTo(0.0d);
    }

    @Test
    void passFailOnlyTermNullsGpaEvenWhenUpstreamSendsZero() {
        TermGpa term = new TermGpa(2026, "summer", 3.0d, 3.0d, 3.0d, 0.0d, 0.0d, 0.0d,
                "0/0", "0/0", false, false, false);

        assertThat(term.gpaCredits()).isEqualTo(0.0d);
        assertThat(term.gpa()).isNull();
    }

    @Test
    void graduationRequirementExposesDeficitAndGateTypeSeparately() {
        GraduationRequirementItem credit = new GraduationRequirementItem(
                "major-required", "major", 15.0f, 9.0f, 6.0f, false);
        GraduationRequirementItem gate = new GraduationRequirementItem(
                "thesis", "graduation", 0.0f, 0.0f, 0.0f, false);

        assertThat(credit.remaining()).isEqualTo(6.0f);
        assertThat(credit.difference()).isEqualTo(-6.0f);
        assertThat(credit.creditBased()).isTrue();
        assertThat(credit.requirementType()).isEqualTo("CREDIT");
        assertThat(gate.creditBased()).isFalse();
        assertThat(gate.requirementType()).isEqualTo("GATE");
    }

    @Test
    void graduationRequirementNormalizesNegativeOrStaleRemaining() {
        GraduationRequirementItem deficient = new GraduationRequirementItem(
                "major-required", "major", 15.0f, 9.0f, -6.0f, false);
        GraduationRequirementItem overCompleted = new GraduationRequirementItem(
                "major-elective", "major", 15.0f, 18.0f, -3.0f, true);

        assertThat(deficient.remaining()).isEqualTo(6.0f);
        assertThat(overCompleted.remaining()).isZero();
    }
}
