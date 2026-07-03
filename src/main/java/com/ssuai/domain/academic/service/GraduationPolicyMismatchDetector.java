package com.ssuai.domain.academic.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.ssuai.domain.academic.dto.AcademicPolicyBriefResponse;
import com.ssuai.domain.academic.dto.AcademicPolicyEvidence;
import com.ssuai.domain.academic.dto.GraduationPolicyMismatchWarning;
import com.ssuai.domain.saint.dto.GraduationRequirementItem;
import com.ssuai.domain.saint.dto.GraduationStatus;

/**
 * Cross-checks the u-SAINT graduation assessment against numeric claims found in
 * the cited policy evidence and warns when the two disagree. Data-quality
 * guardrail only: neither source is corrected — the client is asked to advise
 * manual verification (ADR 0073).
 *
 * <p>Conservative by design. A claim is extracted only when an evidence chunk
 * yields exactly one distinct number anchored to the requirement keyword;
 * ambiguous or unparseable evidence produces no claim, and therefore no
 * warning, keeping false positives near zero. The seed corpus deliberately
 * carries no hard numbers (requirements vary by department and entry year), so
 * this detector stays silent against seed-only evidence and fires mainly when
 * {@code live=true} fetches an official clause that does state a figure.
 *
 * <p>Only two requirement types have unambiguous numeric semantics and are
 * checked: total graduation credits and chapel semesters.
 */
@Component
public class GraduationPolicyMismatchDetector {

    static final String KEY_TOTAL_CREDITS = "TOTAL_CREDITS";
    static final String KEY_CHAPEL_SEMESTERS = "CHAPEL_SEMESTERS";

    // A total graduation-credit figure (100-199), anchored to 졸업/이수/취득 + 학점.
    // The number may precede ("130학점 이상 이수") or follow ("졸업학점 130") the keyword.
    private static final Pattern TOTAL_CREDITS = Pattern.compile(
            "(?:졸업|이수|취득)[^0-9\\n]{0,8}(1\\d{2})\\s*학점"
                    + "|(1\\d{2})\\s*학점[^0-9\\n]{0,10}(?:졸업|이수|취득)");

    // Chapel requirement: a single digit (1-8) anchored to 채플 + 학기/회.
    private static final Pattern CHAPEL = Pattern.compile(
            "채플[^0-9\\n]{0,12}([1-8])\\s*(?:개\\s*)?(?:학기|회)"
                    + "|([1-8])\\s*(?:개\\s*)?(?:학기|회)[^0-9\\n]{0,8}채플");

    public List<GraduationPolicyMismatchWarning> detect(
            GraduationStatus status, AcademicPolicyBriefResponse brief) {
        if (status == null || brief == null || brief.evidence() == null) {
            return List.of();
        }
        List<GraduationPolicyMismatchWarning> warnings = new ArrayList<>();
        for (AcademicPolicyEvidence evidence : brief.evidence()) {
            if (evidence == null) {
                continue;
            }
            String text = joinText(evidence);
            checkTotalCredits(status, evidence, text).ifPresent(warnings::add);
            checkChapel(status, evidence, text).ifPresent(warnings::add);
        }
        return warnings;
    }

    private Optional<GraduationPolicyMismatchWarning> checkTotalCredits(
            GraduationStatus status, AcademicPolicyEvidence evidence, String text) {
        if (status.graduationPoints() <= 0f) {
            return Optional.empty(); // assessment has no total to compare against
        }
        return singleNumber(TOTAL_CREDITS, text).flatMap(policyValue -> {
            float assessment = status.graduationPoints();
            if (Math.abs(assessment - policyValue) < 0.5f) {
                return Optional.empty();
            }
            return Optional.of(new GraduationPolicyMismatchWarning(
                    KEY_TOTAL_CREDITS, assessment, policyValue,
                    evidence.title(), evidence.heading(),
                    "졸업 이수학점이 u-SAINT 졸업사정표(" + trim(assessment) + "학점)와 학칙 근거("
                            + policyValue + "학점)에서 다릅니다. "
                            + "학과별 교육과정·입학연도 경과조치를 학과 사무실에 확인하세요."));
        });
    }

    private Optional<GraduationPolicyMismatchWarning> checkChapel(
            GraduationStatus status, AcademicPolicyEvidence evidence, String text) {
        Optional<GraduationRequirementItem> chapel = status.requirements().stream()
                .filter(r -> r.name() != null && r.name().contains("채플"))
                .findFirst();
        if (chapel.isEmpty() || chapel.get().required() <= 0f) {
            return Optional.empty();
        }
        return singleNumber(CHAPEL, text).flatMap(policyValue -> {
            float assessment = chapel.get().required();
            if (Math.abs(assessment - policyValue) < 0.5f) {
                return Optional.empty();
            }
            return Optional.of(new GraduationPolicyMismatchWarning(
                    KEY_CHAPEL_SEMESTERS, assessment, policyValue,
                    evidence.title(), evidence.heading(),
                    "채플 이수 기준이 u-SAINT 졸업사정표(" + trim(assessment) + "회)와 학칙 근거("
                            + policyValue + "회)에서 다릅니다. "
                            + "입학연도별 채플 이수 기준을 학과 사무실 또는 학사팀에 확인하세요."));
        });
    }

    /**
     * The single distinct integer captured by {@code pattern} in {@code text},
     * or empty when there are zero matches or two-or-more distinct values
     * (ambiguous → no claim). The alternation patterns fill exactly one capture
     * group per match; a value matched by both branches dedupes to one.
     */
    private static Optional<Integer> singleNumber(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Set<Integer> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String captured = matcher.group(group);
                if (captured != null) {
                    found.add(Integer.parseInt(captured));
                }
            }
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }

    private static String joinText(AcademicPolicyEvidence evidence) {
        String heading = evidence.heading() == null ? "" : evidence.heading();
        String snippet = evidence.snippet() == null ? "" : evidence.snippet();
        return heading + "\n" + snippet;
    }

    private static String trim(float value) {
        return value == Math.rint(value)
                ? String.valueOf((int) value)
                : String.valueOf(value);
    }
}
