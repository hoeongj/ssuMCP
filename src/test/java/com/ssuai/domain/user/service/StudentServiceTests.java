package com.ssuai.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ssuai.domain.user.entity.Student;
import com.ssuai.domain.user.repository.StudentRepository;

class StudentServiceTests {

    private static final Instant NOW = Instant.parse("2026-05-16T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final StudentRepository repository = mock(StudentRepository.class);
    private final StudentService service = new StudentService(repository, FIXED_CLOCK);

    @Test
    void firstLoginInsertsNewStudent() {
        when(repository.findById("20210001")).thenReturn(Optional.empty());
        when(repository.save(any(Student.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Student saved = service.upsertOnLogin("20210001", "홍길동", "컴퓨터학부", "재학");

        assertThat(saved.getStudentId()).isEqualTo("20210001");
        assertThat(saved.getName()).isEqualTo("홍길동");
        assertThat(saved.getMajor()).isEqualTo("컴퓨터학부");
        assertThat(saved.getEnrollmentStatus()).isEqualTo("재학");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getLastLoginAt()).isEqualTo(NOW);

        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStudentId()).isEqualTo("20210001");
    }

    @Test
    void repeatLoginUpdatesProfileAndLastLoginAt() {
        Instant past = Instant.parse("2026-04-01T10:00:00Z");
        Student existing = new Student("20210001", "홍길동(이전)", "전산학부", "재학", past);
        when(repository.findById("20210001")).thenReturn(Optional.of(existing));

        Student updated = service.upsertOnLogin("20210001", "홍길동", "컴퓨터학부", "재학");

        assertThat(updated.getStudentId()).isEqualTo("20210001");
        assertThat(updated.getName()).isEqualTo("홍길동");
        assertThat(updated.getMajor()).isEqualTo("컴퓨터학부");
        assertThat(updated.getCreatedAt()).isEqualTo(past);
        assertThat(updated.getLastLoginAt()).isEqualTo(NOW);

        // JPA dirty-checking handles persistence; service should not call save
        // explicitly on the update path.
        verify(repository, never()).save(any(Student.class));
    }

    @Test
    void blankStudentIdIsRejected() {
        when(repository.findById(eq(""))).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.upsertOnLogin("", "홍길동", "컴퓨터학부", "재학"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findByIdDelegatesToRepository() {
        Student stub = new Student("20210001", "홍길동", "컴퓨터학부", "재학", NOW);
        when(repository.findById("20210001")).thenReturn(Optional.of(stub));

        assertThat(service.findById("20210001")).contains(stub);
    }
}
