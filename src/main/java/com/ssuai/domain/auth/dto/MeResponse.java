package com.ssuai.domain.auth.dto;

import com.ssuai.domain.user.entity.Student;

public record MeResponse(
        String studentId,
        String name,
        String major,
        String enrollmentStatus
) {

    public static MeResponse from(Student student) {
        return new MeResponse(
                student.getStudentId(),
                student.getName(),
                student.getMajor(),
                student.getEnrollmentStatus()
        );
    }
}
