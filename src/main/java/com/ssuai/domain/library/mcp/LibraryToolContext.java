package com.ssuai.domain.library.mcp;

/**
 * Thread-local carrier for the {@code LibrarySessionStore} key used by the private library
 * chat tool dispatch ({@code get_my_library_loans}). Public aggregate seat status uses the
 * self-MCP path and the internal sampler session instead.
 *
 * <p>The key is used to look up the user's Pyxis-Auth-Token in
 * {@code LibrarySessionStore}. Set by {@code ChatController} — via
 * {@code LibrarySessionKeyResolver}, preferring the persistent library-session cookie over a
 * legacy servlet session id (ADR 0096) — before calling {@code ChatService.reply()} so the key
 * is available on the same thread when a tool dispatches to a library service.
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
