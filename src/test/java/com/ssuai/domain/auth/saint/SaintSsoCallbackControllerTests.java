package com.ssuai.domain.auth.saint;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.domain.auth.lms.LmsSsoService;
import com.ssuai.domain.user.entity.Student;
import com.ssuai.domain.user.service.StudentService;
import com.ssuai.global.auth.JwtProperties;
import com.ssuai.global.auth.JwtProvider;
import com.ssuai.global.exception.SaintAuthFailedException;
import com.ssuai.global.exception.SaintPortalUnavailableException;

@ActiveProfiles("test")
@WebMvcTest(SaintSsoCallbackController.class)
@Import({AuthProperties.class, JwtProperties.class})
@TestPropertySource(properties = {
        "ssuai.frontend.origin=https://ssuai.vercel.app",
        "ssuai.auth.api-base-url=https://api.ssuai.test",
        "ssuai.auth.smartid-sso-url=https://smartid.example/sso",
        "ssuai.auth.refresh-cookie.name=ssuai_refresh",
        "ssuai.auth.refresh-cookie.path=/api/auth",
        "ssuai.auth.refresh-cookie.secure=true",
        "ssuai.auth.refresh-cookie.same-site=Lax",
        "ssuai.jwt.refresh-ttl=14d"
})
class SaintSsoCallbackControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private SaintSsoService saintSsoService;

    @MockitoBean
    private LmsSsoService lmsSsoService;

    @MockitoBean
    private StudentService studentService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Autowired
    SaintSsoCallbackControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void ssoInitRedirectsToSmartidWithEncodedApiReturnUrl() throws Exception {
        String expectedReturn = URLEncoder.encode(
                "https://api.ssuai.test/api/auth/saint/sso-callback",
                StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/auth/saint/sso-init"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(
                        "https://smartid.example/sso?apiReturnUrl=" + expectedReturn));

        verifyNoInteractions(saintSsoService, studentService, jwtProvider);
    }

    @Test
    void ssoCallbackHappyPathSetsRefreshCookieAndRedirectsToFrontend() throws Exception {
        Student student = new Student(
                "20231234", "홍길동", "컴퓨터학부", "재학", Instant.now());
        when(saintSsoService.authenticate("st-one-shot", "20231234"))
                .thenReturn(new UsaintAuthResult("20231234", "홍길동", "컴퓨터학부", "재학"));
        when(studentService.upsertOnLogin("20231234", "홍길동", "컴퓨터학부", "재학"))
                .thenReturn(student);
        when(jwtProvider.issueRefresh(student)).thenReturn("refresh.jwt.value");

        mockMvc.perform(get("/api/auth/saint/sso-callback")
                        .param("sToken", "st-one-shot")
                        .param("sIdno", "20231234"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(Matchers.containsString(
                        "https://ssuai.vercel.app/auth/return?ok=1")))
                .andExpect(header().string("Set-Cookie",
                        Matchers.containsString("ssuai_refresh=refresh.jwt.value")))
                .andExpect(header().string("Set-Cookie",
                        Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie",
                        Matchers.containsString("Secure")))
                .andExpect(header().string("Set-Cookie",
                        Matchers.containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie",
                        Matchers.containsString("Path=/api/auth")));

        verify(jwtProvider).issueRefresh(student);
    }

    @Test
    void ssoCallbackAuthFailedRedirectsWithAuthFailedQuery() throws Exception {
        when(saintSsoService.authenticate(anyString(), anyString()))
                .thenThrow(new SaintAuthFailedException("phase 1 marker missing"));

        mockMvc.perform(get("/api/auth/saint/sso-callback")
                        .param("sToken", "bad").param("sIdno", "20231234"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://ssuai.vercel.app/auth/return?error=auth_failed"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verifyNoInteractions(studentService, jwtProvider);
    }

    @Test
    void ssoCallbackPortalUnavailableRedirectsWithPortalQuery() throws Exception {
        when(saintSsoService.authenticate(anyString(), anyString()))
                .thenThrow(new SaintPortalUnavailableException("missing identity cells"));

        mockMvc.perform(get("/api/auth/saint/sso-callback")
                        .param("sToken", "ok").param("sIdno", "20231234"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(
                        "https://ssuai.vercel.app/auth/return?error=portal_unavailable"));
    }

    @Test
    void ssoCallbackUnknownExceptionRedirectsWithUnknownQuery() throws Exception {
        when(saintSsoService.authenticate(anyString(), anyString()))
                .thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(get("/api/auth/saint/sso-callback")
                        .param("sToken", "ok").param("sIdno", "20231234"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://ssuai.vercel.app/auth/return?error=unknown"));
    }

    @Test
    void ssoCallbackMissingParamsTreatedAsAuthFailure() throws Exception {
        when(saintSsoService.authenticate(isNull(), isNull()))
                .thenThrow(new SaintAuthFailedException("sToken is required"));

        mockMvc.perform(get("/api/auth/saint/sso-callback"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://ssuai.vercel.app/auth/return?error=auth_failed"));
    }
}
