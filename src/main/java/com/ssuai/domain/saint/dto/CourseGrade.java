package com.ssuai.domain.saint.dto;

/**
 * One row of the 학기별 세부 성적 table (ZCMB3W0017 하단 표). Returned
 * by the parser for each term reached via the 이전학기 (WD01F0)
 * button-press iterate.
 *
 * <p>{@code score} is the 0-100 numeric score the page renders in
 * "성적" column. For P/F-graded courses both {@code score} and
 * {@code grade} read "P" or "F"; for letter-graded courses
 * {@code score} is the numeric and {@code grade} is the letter
 * ("A+", "A0", "A-", "B+", …). Kept as strings because of that
 * pass/fail collision.
 *
 * <p>{@code courseCode} = 학수번호 (8-digit numeric, e.g. "21503227")
 * — non-PII reference data from the curriculum catalog.
 *
 * <p>{@code remark} is most often an empty cell on the page; the parser
 * surfaces an empty string in that case rather than {@code null}.
 */
public record CourseGrade(
        String score,
        String grade,
        String courseName,
        String courseCode,
        double credits,
        String professor,
        String remark
) {

    public CourseGrade {
        if (courseName == null) {
            courseName = "";
        }
        if (professor == null) {
            professor = "";
        }
        if (remark == null) {
            remark = "";
        }
    }
}
