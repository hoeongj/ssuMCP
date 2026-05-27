package com.ssuai.domain.lms.connector;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.lms.dto.AssignmentItem;
import com.ssuai.domain.lms.dto.AssignmentsResponse;

/**
 * Reads {@code fixtures/lms/todos.json} and {@code fixtures/lms/courses.json}
 * from the classpath and joins them into an {@link AssignmentsResponse}.
 *
 * <p>Active by default so prod, CI, and dev never accidentally call
 * canvas.ssu.ac.kr — flip to {@code ssuai.connector.lms-assignments: real}
 * once a deployment holds a live LMS session.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.lms-assignments",
        havingValue = "mock", matchIfMissing = true)
class MockLmsAssignmentsConnector implements LmsAssignmentsConnector {

    private static final long MOCK_TERM_ID = 1L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AssignmentsResponse fetchAssignments(String studentId, LmsCookies cookies) {
        Map<Long, String> courseNames = loadCourseNames();
        List<AssignmentItem> items = loadTodoItems(courseNames);
        return new AssignmentsResponse(MOCK_TERM_ID, items);
    }

    private Map<Long, String> loadCourseNames() {
        JsonNode courses = loadFixture("fixtures/lms/courses.json");
        Map<Long, String> map = new HashMap<>();
        if (courses.isArray()) {
            for (JsonNode course : courses) {
                long id = course.path("id").asLong();
                String name = course.path("name").asText("");
                if (id > 0 && !name.isBlank()) {
                    map.put(id, name);
                }
            }
        }
        return map;
    }

    private List<AssignmentItem> loadTodoItems(Map<Long, String> courseNames) {
        JsonNode root = loadFixture("fixtures/lms/todos.json");
        JsonNode todos = root.path("to_dos");
        List<AssignmentItem> items = new ArrayList<>();
        if (todos.isArray()) {
            for (JsonNode courseNode : todos) {
                long courseId = courseNode.path("course_id").asLong();
                String courseName = courseNames.getOrDefault(courseId, "Unknown Course");
                JsonNode todoList = courseNode.path("todo_list");
                if (todoList.isArray()) {
                    for (JsonNode todo : todoList) {
                        String title = todo.path("title").asText("");
                        String type = todo.path("component_type").asText("assignment");
                        String dueDate = todo.path("due_date").isNull()
                                ? null : todo.path("due_date").asText(null);
                        items.add(new AssignmentItem(courseName, title, type, dueDate));
                    }
                }
            }
        }
        return items;
    }

    private JsonNode loadFixture(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found on classpath: " + path);
            }
            return objectMapper.readTree(in);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load fixture: " + path, exception);
        }
    }
}
