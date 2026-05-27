package com.ssuai.domain.user.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssuai.domain.user.entity.Student;
import com.ssuai.domain.user.repository.StudentRepository;

@Service
public class StudentService {

    private final StudentRepository repository;
    private final Clock clock;

    @Autowired
    public StudentService(StudentRepository repository) {
        this(repository, Clock.systemUTC());
    }

    StudentService(StudentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Insert-or-update the student row keyed by SSU student id. Called from
     * the u-SAINT SSO callback after identity confirmation: a returning
     * student's profile fields are refreshed, a first-time student row is
     * created. Either way the row's {@code lastLoginAt} is bumped.
     */
    @Transactional
    public Student upsertOnLogin(String studentId, String name, String major, String enrollmentStatus) {
        Instant now = clock.instant();
        Optional<Student> existing = repository.findById(studentId);
        if (existing.isPresent()) {
            Student student = existing.get();
            student.updateProfile(name, major, enrollmentStatus, now);
            return student;
        }
        return repository.save(new Student(studentId, name, major, enrollmentStatus, now));
    }

    @Transactional(readOnly = true)
    public Optional<Student> findById(String studentId) {
        return repository.findById(studentId);
    }
}
