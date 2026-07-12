package com.ssuai.domain.library.auth;

import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link LibrarySessionStore} key for an inbound request (ADR 0096).
 *
 * <p>Preferred: the persistent {@code ssuai_library_session} cookie minted at
 * {@code POST /api/library/login}. Its value is a server-generated random key that is
 * completely independent of the Tomcat servlet session, so it survives a backend redeploy
 * or a pod switch across replicas — the two events that used to orphan the old
 * servlet-session-keyed binding.
 *
 * <p>Legacy fallback: an existing servlet session id, for library sessions bound before this
 * cookie existed. This is a one-deploy-generation grace path. It reads the existing session
 * only ({@link HttpServletRequest#getSession(boolean) getSession(false)}) — resolving a
 * library key must never mint a new servlet session as a side effect.
 */
@Component
public class LibrarySessionKeyResolver {

    private final LibrarySessionProperties properties;

    public LibrarySessionKeyResolver(LibrarySessionProperties properties) {
        this.properties = properties;
    }

    public Optional<String> resolve(HttpServletRequest request) {
        String cookieValue = readCookie(request);
        if (cookieValue != null && !cookieValue.isBlank()) {
            return Optional.of(cookieValue);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            return Optional.of(session.getId());
        }
        return Optional.empty();
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String cookieName = properties.getCookie().getName();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
