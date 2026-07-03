package com.ssuai.domain.academic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.academic.dto.AcademicPolicyBriefResponse;
import com.ssuai.domain.academic.dto.AcademicPolicyEvidence;
import com.ssuai.domain.academic.dto.GraduationPolicyMismatchWarning;
import com.ssuai.domain.saint.dto.GraduationRequirementItem;
import com.ssuai.domain.saint.dto.GraduationStatus;

class GraduationPolicyMismatchDetectorTests {

    private final GraduationPolicyMismatchDetector detector = new GraduationPolicyMismatchDetector();

    private static GraduationStatus status(float graduationPoints, GraduationRequirementItem... requirements) {
        return new GraduationStatus(false, "홍길동", "컴퓨터학부", 3, 100f, graduationPoints, List.of(requirements));
    }

    private static GraduationRequirementItem chapel(float required) {
        return new GraduationRequirementItem("채플", "이수", required, 4f, 0f, false);
    }

    private static AcademicPolicyBriefResponse brief(String heading, String snippet) {
        AcademicPolicyEvidence evidence = new AcademicPolicyEvidence(
                "src-1", "학칙시행세칙(학사과정)", "graduation", "seed", "https://ssu.ac.kr",
                "2026-01", "2026-01-01", false, false, Instant.EPOCH, 90, heading, snippet, List.of());
        return new AcademicPolicyBriefResponse("졸업", "graduation", "요약", List.of(), List.of(evidence));
    }

    @Test
    void totalCreditsDisagreementProducesWarning() {
        var warnings = detector.detect(
                status(133f),
                brief("졸업 이수학점", "본교 졸업을 위해서는 130학점 이상을 이수하여야 한다."));

        assertThat(warnings).hasSize(1);
        GraduationPolicyMismatchWarning warning = warnings.get(0);
        assertThat(warning.requirementKey()).isEqualTo("TOTAL_CREDITS");
        assertThat(warning.assessmentValue()).isEqualTo(133f);
        assertThat(warning.policyValue()).isEqualTo(130);
        assertThat(warning.evidenceTitle()).isEqualTo("학칙시행세칙(학사과정)");
        assertThat(warning.message()).contains("133").contains("130").contains("확인");
    }

    @Test
    void totalCreditsAgreementProducesNoWarning() {
        var warnings = detector.detect(
                status(130f),
                brief("졸업 이수학점", "졸업을 위해서는 130학점 이상 이수하여야 한다."));

        assertThat(warnings).isEmpty();
    }

    @Test
    void chapelDisagreementProducesWarning() {
        var warnings = detector.detect(
                status(130f, chapel(6f)),
                brief("채플", "채플은 4개 학기 이수하여야 한다."));

        assertThat(warnings).hasSize(1);
        GraduationPolicyMismatchWarning warning = warnings.get(0);
        assertThat(warning.requirementKey()).isEqualTo("CHAPEL_SEMESTERS");
        assertThat(warning.assessmentValue()).isEqualTo(6f);
        assertThat(warning.policyValue()).isEqualTo(4);
        assertThat(warning.message()).contains("6").contains("4");
    }

    @Test
    void chapelAgreementProducesNoWarning() {
        var warnings = detector.detect(
                status(130f, chapel(6f)),
                brief("채플", "채플은 6개 학기를 이수하여야 한다."));

        assertThat(warnings).isEmpty();
    }

    @Test
    void ambiguousEvidenceWithTwoDifferentNumbersProducesNoClaim() {
        // Two conflicting credit figures in one chunk -> not confident -> silent.
        var warnings = detector.detect(
                status(133f),
                brief("졸업 이수학점", "졸업학점은 130학점이며 일부 학과는 140학점을 이수하여야 한다."));

        assertThat(warnings).isEmpty();
    }

    @Test
    void seedCorpusStyleGuidanceWithNoNumbersProducesNoWarning() {
        // The real seed corpus deliberately states no hard figures.
        var warnings = detector.detect(
                status(133f, chapel(6f)),
                brief("졸업요건 안내",
                        "졸업 가능 여부는 총 취득학점만으로 판단하지 않고 전공, 교양, 필수 과목, "
                                + "인증, 채플 등 별도 요건을 함께 본다."));

        assertThat(warnings).isEmpty();
    }

    @Test
    void noEvidenceProducesNoWarning() {
        var brief = new AcademicPolicyBriefResponse("졸업", "graduation", "요약", List.of(), List.of());

        assertThat(detector.detect(status(133f, chapel(6f)), brief)).isEmpty();
    }

    @Test
    void bothTotalCreditsAndChapelCanWarnTogether() {
        var warnings = detector.detect(
                status(133f, chapel(6f)),
                brief("졸업요건",
                        "졸업을 위해서는 130학점 이상 이수하여야 하며, 채플은 4개 학기 이수하여야 한다."));

        assertThat(warnings).hasSize(2);
        assertThat(warnings).extracting(GraduationPolicyMismatchWarning::requirementKey)
                .containsExactlyInAnyOrder("TOTAL_CREDITS", "CHAPEL_SEMESTERS");
    }

    @Test
    void nullStatusOrBriefIsSafe() {
        assertThat(detector.detect(null, brief("채플", "채플 4학기"))).isEmpty();
        assertThat(detector.detect(status(130f), null)).isEmpty();
    }
}
