CREATE TABLE students (
    student_id        VARCHAR(16)                   NOT NULL,
    name              VARCHAR(64)                   NOT NULL,
    major             VARCHAR(128),
    enrollment_status VARCHAR(32),
    created_at        TIMESTAMP(6) WITH TIME ZONE   NOT NULL,
    last_login_at     TIMESTAMP(6) WITH TIME ZONE   NOT NULL,
    CONSTRAINT pk_students PRIMARY KEY (student_id)
);
