package com.ssuai.domain.lms.mcp;

/**
 * Thread-local carrier for the authenticated student id used by the private
 * LMS MCP tool ({@code get_my_assignments}).
 *
 * <p>Identical in design to {@code SaintToolContext} — see its Javadoc for
 * the rationale.  A separate class keeps the LMS and u-SAINT scopes
 * independent so neither accidentally propagates to the other's tools.
 */
public final class LmsToolContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private LmsToolContext() {
    }

    public static Scope withStudentId(String studentId) {
        String previous = CURRENT.get();
        CURRENT.set(studentId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static String currentStudentId() {
        return CURRENT.get();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
