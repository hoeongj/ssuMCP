package com.ssuai.domain.academic.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.ssuai.domain.academic.dto.AcademicPolicyEvidence;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.Decision;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.MatchedRequirement;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.RequirementResult;

@Component
public class ScholarshipPolicyEvaluator {

    private static final Pattern GPA_FORWARD = Pattern.compile(
            "(?i)(?:gpa|평점평균|평균평점|평점)[^0-9]{0,24}(\\d(?:\\.\\d+)?)\\s*(?:점)?\\s*(?:이상|초과|>=)");
    private static final Pattern GPA_REVERSE = Pattern.compile(
            "(?i)(\\d(?:\\.\\d+)?)\\s*(?:점)?\\s*(?:이상|초과|>=)[^\\n.]{0,24}(?:gpa|평점평균|평균평점|평점)");
    private static final Pattern CREDITS_FORWARD = Pattern.compile(
            "(?:취득\\s*학점|취득학점|이수\\s*학점|이수학점)[^0-9]{0,24}(?<![\\d.])(\\d{1,3})(?!\\.\\d)\\s*(?:학점)?\\s*(?:이상|초과|>=)");
    private static final Pattern CREDITS_REVERSE = Pattern.compile(
            "(?<![\\d.])(\\d{1,3})(?!\\.\\d)\\s*(?:학점)?\\s*(?:이상|초과|>=)[^\\n.]{0,24}(?:취득\\s*학점|취득학점|이수\\s*학점|이수학점)");
    private static final Pattern ADMISSION_YEAR_FORWARD = Pattern.compile(
            "(?:입학\\s*연도|입학연도|입학자|입학생)[^0-9]{0,24}(20\\d{2})\\s*(?:학년도)?\\s*(?:이후|이상|부터)");
    private static final Pattern ADMISSION_YEAR_REVERSE = Pattern.compile(
            "(20\\d{2})\\s*(?:학년도)?\\s*(?:이후|이상|부터)[^\\n.]{0,24}(?:입학|입학자|입학생)");
    private static final Pattern TOPIK_FORWARD = Pattern.compile(
            "(?i)(?:topik|토픽|한국어능력시험)[^0-9]{0,24}(\\d)\\s*급\\s*(?:이상|취득|요건)");
    private static final Pattern TOPIK_REVERSE = Pattern.compile(
            "(?i)(\\d)\\s*급\\s*(?:이상|취득자|요건)[^\\n.]{0,24}(?:topik|토픽|한국어능력시험)");

    String buildScholarshipQuery(String query, List<String> facts) {
        String safe = query == null || query.isBlank() ? "장학금 성적 기준 취득학점 중복수혜" : query.trim();
        if (facts.isEmpty()) {
            return safe;
        }
        return safe + " " + String.join(" ", facts);
    }

    ScholarshipPolicyCheckResponse evaluate(
            String query,
            List<String> facts,
            List<AcademicPolicyEvidence> evidence,
            List<String> briefCautions,
            Double gpa,
            Integer earnedCredits,
            Integer admissionYear,
            Integer topikLevel,
            Boolean internationalStudent) {
        List<MatchedRequirement> matchedRequirements = evaluateScholarshipRequirements(
                query, evidence, gpa, earnedCredits, admissionYear, topikLevel, internationalStudent);
        Decision decision = aggregateScholarshipDecision(matchedRequirements);
        List<String> caveats = new ArrayList<>(briefCautions);
        caveats.add("장학금은 학기별 공지, 등록 상태, 국가장학금 신청 여부, 중복 수혜 제한에 따라 달라질 수 있습니다.");
        if (Boolean.TRUE.equals(internationalStudent)) {
            caveats.add("외국인 유학생 장학금은 입학연도와 TOPIK 급수별 구간이 달라 공식 안내의 연도별 기준을 우선해야 합니다.");
        }
        return new ScholarshipPolicyCheckResponse(
                query,
                facts,
                decision,
                matchedRequirements,
                scholarshipSummary(decision),
                caveats,
                evidence);
    }

    private static List<MatchedRequirement> evaluateScholarshipRequirements(
            String query,
            List<AcademicPolicyEvidence> evidence,
            Double gpa,
            Integer earnedCredits,
            Integer admissionYear,
            Integer topikLevel,
            Boolean internationalStudent) {
        List<MatchedRequirement> requirements = new ArrayList<>();
        if (evidence.isEmpty()) {
            requirements.add(new MatchedRequirement(
                    "공식 정책 근거",
                    "질문과 일치하는 장학 정책 evidence",
                    null,
                    RequirementResult.UNKNOWN));
            return requirements;
        }

        String evidenceText = scholarshipEvidenceText(query, evidence);
        String normalized = normalize(evidenceText);

        addMinimumDoubleRequirement(
                requirements,
                "GPA/평점 기준",
                "GPA",
                gpa,
                extractDoubleThresholds(evidenceText, 0.0d, 4.5d, GPA_FORWARD, GPA_REVERSE),
                mentionsAny(normalized, "gpa", "평점", "성적"));
        addMinimumIntegerRequirement(
                requirements,
                "취득학점 기준",
                "earnedCredits",
                earnedCredits,
                extractIntegerThresholds(evidenceText, 0, 200, CREDITS_FORWARD, CREDITS_REVERSE),
                mentionsAny(normalized, "취득학점", "취득 학점", "이수학점", "이수 학점"));
        addMinimumIntegerRequirement(
                requirements,
                "입학연도 기준",
                "admissionYear",
                admissionYear,
                extractIntegerThresholds(evidenceText, 2000, 2100, ADMISSION_YEAR_FORWARD, ADMISSION_YEAR_REVERSE),
                mentionsAny(normalized, "입학연도", "입학 연도", "입학자", "입학생"));
        addMinimumIntegerRequirement(
                requirements,
                "TOPIK 급수 기준",
                "topikLevel",
                topikLevel,
                extractIntegerThresholds(evidenceText, 1, 6, TOPIK_FORWARD, TOPIK_REVERSE),
                mentionsAny(normalized, "topik", "토픽", "한국어능력시험"));

        if (mentionsAny(normalized, "외국인 유학생", "외국인유학생", "international student")) {
            RequirementResult result = internationalStudent == null
                    ? RequirementResult.UNKNOWN
                    : Boolean.TRUE.equals(internationalStudent) ? RequirementResult.OK : RequirementResult.FAIL;
            requirements.add(new MatchedRequirement(
                    "대상 학생 유형",
                    "외국인 유학생",
                    internationalStudent,
                    result));
        }

        addUnknownIfMentioned(requirements, normalized, "등록 상태", "장학금 지급 가능 등록 상태", "등록 상태");
        addUnknownIfMentioned(requirements, normalized, "국가장학금 신청 여부", "필요 시 국가장학금 신청 완료", "국가장학금");
        addUnknownIfMentioned(requirements, normalized, "중복 수혜 제한", "중복 수혜 제한 미해당", "중복 수혜");
        addUnknownIfMentioned(requirements, normalized, "정규학기 제한", "정규학기 초과 아님", "정규학기");

        if (requirements.isEmpty()) {
            requirements.add(new MatchedRequirement(
                    "판정 가능한 장학 요건",
                    "공식 근거에 명시된 GPA/취득학점/입학연도/TOPIK 등 조건",
                    null,
                    RequirementResult.UNKNOWN));
        }
        return requirements;
    }

    private static void addMinimumDoubleRequirement(
            List<MatchedRequirement> requirements,
            String requirement,
            String fieldName,
            Double userValue,
            List<Double> thresholds,
            boolean policyMentionsRequirement) {
        if (thresholds.size() == 1) {
            Double threshold = thresholds.getFirst();
            requirements.add(new MatchedRequirement(
                    requirement,
                    fieldName + " >= " + formatNumber(threshold),
                    userValue,
                    minimumResult(userValue, threshold)));
            return;
        }
        if (thresholds.size() > 1) {
            requirements.add(new MatchedRequirement(
                    requirement,
                    "공식 근거에서 복수 기준 감지: " + fieldName + " >= " + joinNumbers(thresholds),
                    userValue,
                    RequirementResult.UNKNOWN));
            return;
        }
        if (policyMentionsRequirement) {
            requirements.add(new MatchedRequirement(
                    requirement,
                    "공식 근거에서 정량 기준 확인 필요",
                    userValue,
                    RequirementResult.UNKNOWN));
        }
    }

    private static void addMinimumIntegerRequirement(
            List<MatchedRequirement> requirements,
            String requirement,
            String fieldName,
            Integer userValue,
            List<Integer> thresholds,
            boolean policyMentionsRequirement) {
        if (thresholds.size() == 1) {
            Integer threshold = thresholds.getFirst();
            requirements.add(new MatchedRequirement(
                    requirement,
                    fieldName + " >= " + threshold,
                    userValue,
                    minimumResult(userValue, threshold)));
            return;
        }
        if (thresholds.size() > 1) {
            requirements.add(new MatchedRequirement(
                    requirement,
                    "공식 근거에서 복수 기준 감지: " + fieldName + " >= " + joinIntegers(thresholds),
                    userValue,
                    RequirementResult.UNKNOWN));
            return;
        }
        if (policyMentionsRequirement) {
            requirements.add(new MatchedRequirement(
                    requirement,
                    "공식 근거에서 정량 기준 확인 필요",
                    userValue,
                    RequirementResult.UNKNOWN));
        }
    }

    private static RequirementResult minimumResult(Double userValue, Double threshold) {
        if (userValue == null) {
            return RequirementResult.UNKNOWN;
        }
        return userValue >= threshold ? RequirementResult.OK : RequirementResult.FAIL;
    }

    private static RequirementResult minimumResult(Integer userValue, Integer threshold) {
        if (userValue == null) {
            return RequirementResult.UNKNOWN;
        }
        return userValue >= threshold ? RequirementResult.OK : RequirementResult.FAIL;
    }

    private static void addUnknownIfMentioned(
            List<MatchedRequirement> requirements,
            String normalized,
            String requirement,
            String required,
            String token) {
        if (normalized.contains(token)) {
            requirements.add(new MatchedRequirement(requirement, required, null, RequirementResult.UNKNOWN));
        }
    }

    private static Decision aggregateScholarshipDecision(List<MatchedRequirement> requirements) {
        if (requirements.stream().anyMatch(requirement -> requirement.result() == RequirementResult.FAIL)) {
            return Decision.NOT_ELIGIBLE;
        }
        if (requirements.isEmpty()
                || requirements.stream().anyMatch(requirement -> requirement.result() == RequirementResult.UNKNOWN)) {
            return Decision.INSUFFICIENT_EVIDENCE;
        }
        return Decision.ELIGIBLE;
    }

    private static String scholarshipSummary(Decision decision) {
        return switch (decision) {
            case ELIGIBLE -> "공식 근거에서 추출한 조건과 입력값이 모두 충족됩니다.";
            case NOT_ELIGIBLE -> "하나 이상의 장학 조건이 입력값과 맞지 않습니다.";
            case INSUFFICIENT_EVIDENCE -> "필요한 공식 기준 또는 학생 입력값이 부족해 판단을 보류합니다.";
        };
    }

    private static String scholarshipEvidenceText(String query, List<AcademicPolicyEvidence> evidence) {
        StringBuilder builder = new StringBuilder(query == null ? "" : query);
        for (AcademicPolicyEvidence item : evidence) {
            builder.append('\n')
                    .append(item.title()).append(' ')
                    .append(item.heading()).append(' ')
                    .append(item.snippet()).append(' ')
                    .append(String.join(" ", item.matchedTerms()));
        }
        return builder.toString();
    }

    private static List<Double> extractDoubleThresholds(
            String text, double minimum, double maximum, Pattern... patterns) {
        List<Double> values = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                double value = Double.parseDouble(matcher.group(1));
                if (value >= minimum && value <= maximum && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static List<Integer> extractIntegerThresholds(
            String text, int minimum, int maximum, Pattern... patterns) {
        List<Integer> values = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                int value = Integer.parseInt(matcher.group(1));
                if (value >= minimum && value <= maximum && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static boolean mentionsAny(String normalized, String... tokens) {
        for (String token : tokens) {
            if (normalized.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String formatNumber(Double value) {
        if (value % 1 == 0) {
            return Integer.toString(value.intValue());
        }
        return value.toString();
    }

    // Render alternative thresholds as readable text ("3.5, 4.0"), NOT a raw
    // List.toString() ("[3.5, 4.0]") — this string is shown to the user.
    private static String joinNumbers(List<Double> values) {
        return String.join(", ", values.stream()
                .map(ScholarshipPolicyEvaluator::formatNumber)
                .toList());
    }

    private static String joinIntegers(List<Integer> values) {
        return String.join(", ", values.stream()
                .map(String::valueOf)
                .toList());
    }
}
