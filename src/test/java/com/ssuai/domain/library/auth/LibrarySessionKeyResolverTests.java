package com.ssuai.domain.library.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LibrarySessionKeyResolverTests {

    private final LibrarySessionProperties properties = new LibrarySessionProperties();
    private final LibrarySessionKeyResolver resolver = new LibrarySessionKeyResolver(properties);

    @Test
    void resolve_prefersTheCookieOverAnExistingServletSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("ssuai_library_session", "cookie-key"));
        request.getSession(true);

        Optional<String> resolved = resolver.resolve(request);

        assertThat(resolved).contains("cookie-key");
    }

    @Test
    void resolve_fallsBackToLegacyServletSessionWhenNoCookiePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String sessionId = request.getSession(true).getId();

        Optional<String> resolved = resolver.resolve(request);

        assertThat(resolved).contains(sessionId);
    }

    @Test
    void resolve_returnsEmptyAndNeverCreatesAServletSessionWhenNeitherIsPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        Optional<String> resolved = resolver.resolve(request);

        assertThat(resolved).isEmpty();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void resolve_ignoresABlankCookieValueAndFallsBackToLegacySession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("ssuai_library_session", ""));
        String sessionId = request.getSession(true).getId();

        Optional<String> resolved = resolver.resolve(request);

        assertThat(resolved).contains(sessionId);
    }

    @Test
    void resolve_ignoresCookiesWithADifferentName() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("some_other_cookie", "unrelated-value"));

        Optional<String> resolved = resolver.resolve(request);

        assertThat(resolved).isEmpty();
    }
}
