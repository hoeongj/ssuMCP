package com.ssuai.domain.library.mcp;

/**
 * Thread-local carrier for the HTTP session key used by the private library
 * MCP tool ({@code get_my_library_loans}).
 *
 * <p>The session key is used to look up the user's Pyxis-Auth-Token in
 * {@code LibrarySessionStore}. Set by {@code ChatController} before calling
 * {@code ChatService.reply()} so the key is available on the same thread
 * when the tool dispatches to {@code LibraryLoansService}.
 */
public final class LibraryToolContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private LibraryToolContext() {
    }

    public static Scope withSessionKey(String sessionKey) {
        String previous = CURRENT.get();
        CURRENT.set(sessionKey);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static String currentSessionKey() {
        return CURRENT.get();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
