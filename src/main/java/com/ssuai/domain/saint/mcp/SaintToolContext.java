package com.ssuai.domain.saint.mcp;

/**
 * Thread-local carrier for the authenticated student id used by the
 * private u-SAINT MCP tools ({@code get_my_schedule}, {@code get_my_grades}).
 *
 * <p>Spring AI's {@code @Tool}-annotated methods do not receive the
 * caller's identity through their parameter list because that would let
 * an external MCP client (Claude Desktop, etc.) spoof any student id.
 * Instead the chat path — which already holds the authenticated student
 * id from {@code AuthAttributes.STUDENT_ID} — opens a scope just before
 * dispatching the tool call, the tool method reads {@link #currentStudentId()}
 * inside the scope, and the scope auto-closes on exit. An external MCP
 * client that bypasses the chat path hits the tool method with the
 * thread-local unset; the tool then refuses.
 *
 * <p>This is the {@code chat thread-local pattern} called out in Task 16
 * spec §9 #4 (interim until Spring AI exposes per-call MCP identity
 * headers). Threads that never open a scope see {@code null}; the holder
 * never leaks across tasks because every {@link #withStudentId(String)}
 * returns a {@link Scope} that must be closed via try-with-resources.
 *
 * <pre>{@code
 * try (SaintToolContext.Scope ignored = SaintToolContext.withStudentId(sid)) {
 *     toolCallback.call(...);
 * }
 * }</pre>
 */
public final class SaintToolContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SaintToolContext() {
    }

    /**
     * Bind {@code studentId} to the current thread until the returned
     * {@link Scope} is closed. Nested scopes restore the previous value on
     * close, so chat → tool → another tool is safe.
     *
     * @param studentId authenticated ssuAI student id (non-blank); blank
     *                  and {@code null} are stored as-is so the tool
     *                  method can produce a uniform "not authenticated"
     *                  error rather than a {@code NullPointerException}.
     */
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

    /**
     * @return the student id bound by the nearest enclosing
     *         {@link #withStudentId(String)} scope, or {@code null} if
     *         the current thread has none (e.g. an external MCP client
     *         calling the tool directly).
     */
    public static String currentStudentId() {
        return CURRENT.get();
    }

    /** Auto-closeable handle returned by {@link #withStudentId(String)}. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
