package com.ssuai.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ssuai.domain.user.entity.Student;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StudentRepositoryTests {

    private final StudentRepository repository;

    @Autowired
    StudentRepositoryTests(StudentRepository repository) {
        this.repository = repository;
    }

    @Test
    void persistsAndFindsByStudentId() {
        Instant now = Instant.parse("2026-05-16T10:00:00Z");
        repository.save(new Student("20210001", "홍길동", "컴퓨터학부", "재학", now));

        assertThat(repository.findById("20210001"))
                .hasValueSatisfying(student -> {
                    assertThat(student.getName()).isEqualTo("홍길동");
                    assertThat(student.getMajor()).isEqualTo("컴퓨터학부");
                    assertThat(student.getEnrollmentStatus()).isEqualTo("재학");
                    assertThat(student.getCreatedAt()).isEqualTo(now);
                });
    }

    @Test
    void updatesPreservesCreatedAt() {
        Instant created = Instant.parse("2026-04-01T10:00:00Z");
        Instant later = Instant.parse("2026-05-16T10:00:00Z");
        repository.save(new Student("20210002", "박철수", "컴퓨터학부", "재학", created));

        Student loaded = repository.findById("20210002").orElseThrow();
        loaded.updateProfile("박철수", "AI융합학부", "재학", later);
        repository.save(loaded);

        Student reloaded = repository.findById("20210002").orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
        assertThat(reloaded.getLastLoginAt()).isEqualTo(later);
        assertThat(reloaded.getMajor()).isEqualTo("AI융합학부");
    }
}
