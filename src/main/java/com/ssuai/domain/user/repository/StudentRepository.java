package com.ssuai.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssuai.domain.user.entity.Student;

public interface StudentRepository extends JpaRepository<Student, String> {
}
