package com.ssuai.domain.saint.connector

import com.ssuai.domain.saint.dto.ChapelAbsenceApplication
import com.ssuai.domain.saint.dto.ChapelAttendanceEntry
import com.ssuai.domain.saint.dto.ChapelInfo
import com.ssuai.domain.saint.dto.CourseScheduleEntry
import com.ssuai.domain.saint.dto.CourseGrade
import com.ssuai.domain.saint.dto.GpaSummary
import com.ssuai.domain.saint.dto.GraduationRequirementItem
import com.ssuai.domain.saint.dto.GraduationStatus
import com.ssuai.domain.saint.dto.GradesResponse
import com.ssuai.domain.saint.dto.MeetingSlot
import com.ssuai.domain.saint.dto.ScheduleResponse
import com.ssuai.domain.saint.dto.ScholarshipEntry
import com.ssuai.domain.saint.dto.TermGpa
import com.ssuai.domain.saint.dto.TermSchedule
import dev.eatsteak.rusaint.ffi.ChapelApplicationBuilder
import dev.eatsteak.rusaint.ffi.CourseGradesApplicationBuilder
import dev.eatsteak.rusaint.ffi.GraduationRequirementsApplicationBuilder
import dev.eatsteak.rusaint.ffi.PersonalCourseScheduleApplicationBuilder
import dev.eatsteak.rusaint.ffi.ScholarshipsApplicationBuilder
import dev.eatsteak.rusaint.ffi.StudentInformationApplicationBuilder
import dev.eatsteak.rusaint.ffi.USaintSession
import dev.eatsteak.rusaint.ffi.USaintSessionBuilder
import dev.eatsteak.rusaint.model.ChapelAbsenceRequest
import dev.eatsteak.rusaint.model.ChapelAttendance
import dev.eatsteak.rusaint.model.ChapelInformation
import dev.eatsteak.rusaint.model.ClassGrade
import dev.eatsteak.rusaint.model.ClassScore
import dev.eatsteak.rusaint.model.CourseScheduleInformation
import dev.eatsteak.rusaint.model.CourseType
import dev.eatsteak.rusaint.model.GradeSummary
import dev.eatsteak.rusaint.model.GraduationRequirement
import dev.eatsteak.rusaint.model.SemesterGrade
import dev.eatsteak.rusaint.model.SemesterType
import dev.eatsteak.rusaint.model.Scholarship
import dev.eatsteak.rusaint.model.Weekday
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component
@Component
class RusaintUniFfiClient : RusaintClient {

    override fun authenticateWithToken(studentId: String, ssoToken: String): RusaintAuthenticatedSession =
        withClientError("rusaint token authentication failed") {
            require(studentId.isNotBlank()) { "studentId is required" }
            require(ssoToken.isNotBlank()) { "ssoToken is required" }

            runBlocking {
                USaintSessionBuilder().useAuto { builder ->
                    builder.withToken(studentId.trim(), ssoToken).useAuto { session ->
                        val info = StudentInformationApplicationBuilder().useAuto { appBuilder ->
                            appBuilder.build(session).useAuto { app -> app.general() }
                        }
                        RusaintAuthenticatedSession(
                            info.studentNumber.toString(),
                            info.name.ifBlank { studentId.trim() },
                            firstNonBlank(info.major, info.department),
                            null,
                            session.toJson(),
                        )
                    }
                }
            }
        }

    override fun fetchSchedule(studentId: String, sessionJson: String): ScheduleResponse =
        fetchSchedule(studentId, sessionJson, null, null)

    override fun fetchSchedule(
        studentId: String,
        sessionJson: String,
        year: Int?,
        term: Int?,
    ): ScheduleResponse =
        withClientError("rusaint schedule fetch failed") {
            require((year == null) == (term == null)) { "schedule year and term must be provided together" }
            require(year == null || year > 0) { "schedule year must be positive" }

            runBlocking {
                sessionFromJson(sessionJson).useAuto { session ->
                    PersonalCourseScheduleApplicationBuilder().useAuto { builder ->
                        builder.build(session).useAuto { app ->
                            val selected = app.getSelectedSemester()
                            val requestedYear = year?.toUInt() ?: selected.year
                            // Vacation semesters (SUMMER/WINTER) have no timetable.
                            // When no explicit term is requested, fall back to the
                            // immediately preceding regular semester so the student
                            // gets their most recent actual timetable instead of an error.
                            val requestedSemester = when {
                                term != null -> semesterType(term)!!
                                selected.semester == SemesterType.SUMMER -> SemesterType.ONE
                                selected.semester == SemesterType.WINTER -> SemesterType.TWO
                                else -> selected.semester
                            }
                            val responseYear = requestedYear.toInt()
                            val responseTerm = termNumber(requestedSemester)
                            val schedule = app.schedule(requestedYear, requestedSemester)
                            ScheduleResponse(
                                enrollmentYear(studentId),
                                responseYear,
                                responseTerm,
                                listOf(TermSchedule(responseYear, responseTerm, mapSchedule(schedule.schedule))),
                            )
                        }
                    }
                }
            }
        }

    override fun fetchGrades(studentId: String, sessionJson: String): GradesResponse =
        withClientError("rusaint grades fetch failed") {
            runBlocking {
                sessionFromJson(sessionJson).useAuto { session ->
                    CourseGradesApplicationBuilder().useAuto { builder ->
                        builder.build(session).useAuto { app ->
                            val courseType = CourseType.BACHELOR
                            val sourceHistory = app.semesters(courseType)
                            val history = sourceHistory.map(::mapTermGpa)
                            val details = linkedMapOf<String, List<CourseGrade>>()
                            sourceHistory.zip(history).forEach { (source, term) ->
                                val rows = app.classes(
                                    courseType,
                                    source.year,
                                    source.semester,
                                    false,
                                ).map(::mapCourseGrade)
                                if (rows.isNotEmpty()) {
                                    details[term.termKey()] = rows
                                }
                            }
                            GradesResponse(
                                history,
                                mapSummary(app.recordedSummary(courseType)),
                                mapSummary(app.certificatedSummary(courseType)),
                                details,
                            )
                        }
                    }
                }
            }
        }

    override fun fetchChapelInfo(studentId: String, sessionJson: String): ChapelInfo =
        fetchChapelInfo(studentId, sessionJson, null, null)

    override fun fetchChapelInfo(
        studentId: String,
        sessionJson: String,
        year: Int?,
        semester: String?,
    ): ChapelInfo =
        withClientError("rusaint chapel fetch failed") {
            require(year == null || year > 0) { "chapel year must be positive" }

            runBlocking {
                sessionFromJson(sessionJson).useAuto { session ->
                    ChapelApplicationBuilder().useAuto { builder ->
                        builder.build(session).useAuto { app ->
                            val selected = app.getSelectedSemester()
                            val requestedYear = year?.toUInt() ?: selected.year
                            // Apply the same vacation-semester fallback as the schedule connector:
                            // chapel data only exists for regular semesters.
                            val requestedSemester = when {
                                semester != null -> semesterType(semester) ?: selected.semester
                                selected.semester == SemesterType.SUMMER -> SemesterType.ONE
                                selected.semester == SemesterType.WINTER -> SemesterType.TWO
                                else -> selected.semester
                            }
                            mapChapelInfo(app.information(requestedYear, requestedSemester))
                        }
                    }
                }
            }
        }

    override fun fetchGraduationRequirements(studentId: String, sessionJson: String): GraduationStatus =
        withClientError("rusaint graduation requirements fetch failed") {
            runBlocking {
                sessionFromJson(sessionJson).useAuto { session ->
                    GraduationRequirementsApplicationBuilder().useAuto { builder ->
                        builder.build(session).useAuto { app ->
                            val requirements = app.requirements()
                            val student = app.studentInfo()
                            GraduationStatus(
                                requirements.isGraduatable,
                                student.name,
                                student.department,
                                student.grade.toInt(),
                                student.completedPoints,
                                student.graduationPoints,
                                requirements.requirements.values
                                    .map(::mapGraduationRequirement)
                                    .sortedBy { it.name() },
                            )
                        }
                    }
                }
            }
        }

    override fun fetchScholarships(studentId: String, sessionJson: String): List<ScholarshipEntry> =
        withClientError("rusaint scholarships fetch failed") {
            runBlocking {
                sessionFromJson(sessionJson).useAuto { session ->
                    ScholarshipsApplicationBuilder().useAuto { builder ->
                        builder.build(session).useAuto { app ->
                            app.scholarships().map(::mapScholarship)
                        }
                    }
                }
            }
        }

    private fun sessionFromJson(sessionJson: String): USaintSession =
        USaintSessionBuilder().useAuto { builder -> builder.fromJson(sessionJson) }

    private fun mapChapelInfo(info: ChapelInformation): ChapelInfo =
        ChapelInfo(
            info.year.toInt(),
            termLabel(info.semester),
            info.generalInformation.chapelTime,
            info.generalInformation.chapelRoom,
            info.generalInformation.seatNumber.takeIf { it.isNotBlank() },
            null,
            info.generalInformation.absenceTime.toInt(),
            info.generalInformation.result,
            info.attendances.map(::mapChapelAttendance),
            info.absenceRequests.map(::mapChapelAbsenceApplication),
        )

    private fun mapChapelAttendance(attendance: ChapelAttendance): ChapelAttendanceEntry =
        ChapelAttendanceEntry(
            attendance.classDate,
            attendance.title,
            attendance.instructor,
            attendance.attendance,
        )

    private fun mapChapelAbsenceApplication(request: ChapelAbsenceRequest): ChapelAbsenceApplication =
        ChapelAbsenceApplication(
            request.absenceDetail,
            request.absenceStart,
            request.absenceEnd,
            request.absenceReasonKr,
            request.status,
        )

    private fun mapGraduationRequirement(requirement: GraduationRequirement): GraduationRequirementItem {
        val required = requirement.requirement?.toFloat() ?: 0f
        val completed = requirement.calculation ?: 0f
        val difference = requirement.difference ?: completed - required
        val remaining = if (difference < 0f) -difference else 0f
        return GraduationRequirementItem(
            requirement.name,
            requirement.category,
            required,
            completed,
            remaining,
            requirement.result,
        )
    }

    private fun mapScholarship(scholarship: Scholarship): ScholarshipEntry =
        ScholarshipEntry(
            scholarship.year.toInt(),
            termLabel(scholarship.semester),
            scholarship.name,
            scholarship.receivedAmount.toLong(),
            scholarship.receiveType,
            scholarship.status,
        )

    private fun mapSchedule(schedule: Map<Weekday, List<CourseScheduleInformation>>): List<CourseScheduleEntry> {
        val grouped = linkedMapOf<Pair<String, String>, MutableList<MeetingSlot>>()
        schedule.entries
            .sortedBy { dayNumber(it.key) }
            .forEach { (day, entries) ->
                entries
                    .sortedWith(compareBy<CourseScheduleInformation> { periodFromTimeRange(it.time) }
                        .thenBy { it.time }
                        .thenBy { it.name }
                        .thenBy { it.professor })
                    .forEach { entry ->
                        grouped.getOrPut(entry.name to entry.professor) { mutableListOf() }
                            .add(
                                MeetingSlot(
                                    dayNumber(day),
                                    dayLabel(day),
                                    periodFromTimeRange(entry.time),
                                    entry.time,
                                    entry.classroom,
                                ),
                            )
                    }
            }

        return grouped.map { (key, meetings) ->
            CourseScheduleEntry(key.first, key.second, meetings)
        }
    }

    private fun mapSummary(summary: GradeSummary): GpaSummary =
        GpaSummary(
            summary.attemptedCredits.toDouble(),
            summary.earnedCredits.toDouble(),
            summary.gradePointsSum.toDouble(),
            summary.gradePointsAverage.toDouble(),
            summary.arithmeticMean.toDouble(),
            summary.pfEarnedCredits.toDouble(),
        )

    private fun mapTermGpa(grade: SemesterGrade): TermGpa =
        TermGpa(
            grade.year.toInt(),
            termLabel(grade.semester),
            grade.attemptedCredits.toDouble(),
            grade.earnedCredits.toDouble(),
            grade.pfEarnedCredits.toDouble(),
            grade.gradePointsAverage.toDouble().takeIf {
                grade.earnedCredits.toDouble() - grade.pfEarnedCredits.toDouble() > 0.0
            },
            grade.gradePointsSum.toDouble(),
            grade.arithmeticMean.toDouble(),
            rank(grade.semesterRank.first.toInt(), grade.semesterRank.second.toInt()),
            rank(grade.generalRank.first.toInt(), grade.generalRank.second.toInt()),
            grade.academicProbation,
            grade.consult,
            grade.flunked,
        )

    private fun mapCourseGrade(grade: ClassGrade): CourseGrade =
        CourseGrade(
            score(grade.score),
            grade.rank,
            grade.className,
            grade.code,
            grade.gradePoints.toDouble(),
            grade.professor,
            "",
        )

    private fun score(score: ClassScore): String =
        when (score) {
            ClassScore.Pass -> "P"
            ClassScore.Failed -> "F"
            ClassScore.Empty -> ""
            is ClassScore.Score -> score.v1.toString()
        }

    private fun termLabel(semester: SemesterType): String =
        when (semester) {
            SemesterType.ONE -> "1학기"
            SemesterType.SUMMER -> "여름학기"
            SemesterType.TWO -> "2학기"
            SemesterType.WINTER -> "겨울학기"
        }

    private fun termNumber(semester: SemesterType): Int =
        when (semester) {
            SemesterType.ONE -> 1
            SemesterType.SUMMER -> 2
            SemesterType.TWO -> 3
            SemesterType.WINTER -> 4
        }

    private fun semesterType(semester: String?): SemesterType? =
        when (semester?.trim()?.lowercase()) {
            null, "" -> null
            "1", "1학기", "one" -> SemesterType.ONE
            "여름학기", "summer" -> SemesterType.SUMMER
            "2", "2학기", "two" -> SemesterType.TWO
            "겨울학기", "winter" -> SemesterType.WINTER
            else -> throw IllegalArgumentException("unsupported semester: $semester")
        }

    private fun semesterType(term: Int?): SemesterType? =
        when (term) {
            null -> null
            1 -> SemesterType.ONE
            2 -> SemesterType.SUMMER
            3 -> SemesterType.TWO
            4 -> SemesterType.WINTER
            else -> throw IllegalArgumentException("unsupported term: $term")
        }

    private fun dayNumber(day: Weekday): Int =
        when (day) {
            Weekday.MON -> 1
            Weekday.TUE -> 2
            Weekday.WED -> 3
            Weekday.THU -> 4
            Weekday.FRI -> 5
            Weekday.SAT -> 6
            Weekday.SUN -> 7
        }

    private fun dayLabel(day: Weekday): String =
        when (day) {
            Weekday.MON -> "월"
            Weekday.TUE -> "화"
            Weekday.WED -> "수"
            Weekday.THU -> "목"
            Weekday.FRI -> "금"
            Weekday.SAT -> "토"
            Weekday.SUN -> "일"
        }

    private fun periodFromTimeRange(timeRange: String): Int {
        val start = TIME_START_PATTERN.find(timeRange)?.value ?: return 0
        return when (start) {
            "08:00" -> 0
            "09:00" -> 1
            "10:00" -> 2
            "10:30" -> 3
            "12:00" -> 4
            "13:30" -> 5
            "14:00" -> 6
            "15:00" -> 7
            "16:00" -> 8
            "16:30" -> 9
            "18:00" -> 10
            else -> 0
        }
    }

    private fun enrollmentYear(studentId: String): Int =
        studentId.take(4).toIntOrNull() ?: 0

    private fun rank(first: Int, second: Int): String =
        if (first == 0 && second == 0) "" else "$first/$second"

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private inline fun <T> withClientError(
        message: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (exception: RusaintClientException) {
            throw exception
        } catch (exception: Exception) {
            throw RusaintClientException(message, exception)
        }


    private inline fun <T : AutoCloseable, R> T.useAuto(block: (T) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }

    private companion object {
        private val TIME_START_PATTERN = Regex("""\d{2}:\d{2}""")
    }
}
