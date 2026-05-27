package com.ssuai.domain.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.domain.user.entity.Student;
import com.ssuai.domain.user.service.StudentService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.auth.InvalidJwtException;
import com.ssuai.global.auth.JwtClaims;
import com.ssuai.global.auth.JwtProperties;
import com.ssuai.global.auth.JwtProvider;
import com.ssuai.global.auth.JwtTokenType;

@ActiveProfiles("test")
@WebMvcTest(AuthController.class)
@Import({AuthProperties.class, JwtProperties.class})
@TestPropertySource(properties = {
        "ssuai.auth.refresh-cookie.name=ssuai_refresh",
        "ssuai.auth.refresh-cookie.path=/api/auth",
        "ssuai.auth.refresh-cookie.secure=true",
        "ssuai.auth.refresh-cookie.same-site=Lax",
        "ssuai.jwt.access-ttl=15m",
        "ssuai.jwt.refresh-ttl=14d"
})
class AuthControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private StudentService studentService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Autowired
    AuthControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    // ---------- /api/auth/me ----------

    @Test
    void meReturnsCurrentStudentWhenAttributePresent() throws Exception {
        Student student = new Student("20231234", "홍길동", "컴퓨터학부", "재학", Instant.now());
        when(studentService.findById("20231234")).thenReturn(Optional.of(student));

        mockMvc.perform(get("/api/auth/me")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20231234")
                        .requestAttr(AuthAttributes.STUDENT_NAME, "홍길동"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value("20231234"))
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.major").value("컴퓨터학부"))
                .andExpect(jsonPath("$.data.enrollmentStatus").value("재학"))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void meReturns401WhenAttributeMissing() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(studentService);
    }

    @Test
    void meReturns401WhenAttributeIsBlank() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .requestAttr(AuthAttributes.STUDENT_ID, "  "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(studentService);
    }

    @Test
    void meReturns401WhenStudentRowMissing() throws Exception {
        when(studentService.findById(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/me")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20239999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---------- /api/auth/refresh ----------

    @Test
    void refreshIssuesNewAccessAndRotatesRefreshCookie() throws Exception {
        Student student = new Student("20231234", "홍길동", "컴퓨터학부", "재학", Instant.now());
        JwtClaims claims = new JwtClaims(
                "20231234", "홍길동",
                JwtTokenType.REFRESH,
                Instant.now(), Instant.now().plusSeconds(86_400));

        when(jwtProvider.parse("old.refresh.jwt", JwtTokenType.REFRESH)).thenReturn(claims);
        when(studentService.findById("20231234")).thenReturn(Optional.of(student));
        when(jwtProvider.issueAccess(student)).thenReturn("new.access.jwt");
        when(jwtProvider.issueRefresh(student)).thenReturn("new.refresh.jwt");

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("ssuai_refresh", "old.refresh.jwt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new.access.jwt"))
                .andExpect(jsonPath("$.data.accessTtlSeconds").value(900))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(header().string("Set-Cookie",
                        containsString("ssuai_refresh=new.refresh.jwt")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/auth")));
    }

    @Test
    void refreshReturns401WhenCookieMissing() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(jwtProvider);
    }

    @Test
    void refreshReturns401WhenCookieValueBlank() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("ssuai_refresh", "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(jwtProvider);
    }

    @Test
    void refreshReturns401WhenTokenInvalid() throws Exception {
        when(jwtProvider.parse(eq("tampered.jwt"), eq(JwtTokenType.REFRESH)))
                .thenThrow(new InvalidJwtException("tampered"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("ssuai_refresh", "tampered.jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(studentService);
    }

    @Test
    void refreshReturns401WhenStudentNoLongerExists() throws Exception {
        JwtClaims claims = new JwtClaims(
                "20231234", "홍길동",
                JwtTokenType.REFRESH,
                Instant.now(), Instant.now().plusSeconds(86_400));
        when(jwtProvider.parse("ok.refresh.jwt", JwtTokenType.REFRESH)).thenReturn(claims);
        when(studentService.findById("20231234")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("ssuai_refresh", "ok.refresh.jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---------- /api/auth/logout ----------

    @Test
    void logoutClearsRefreshCookieAndReturns200() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(header().string("Set-Cookie",
                        containsString("ssuai_refresh=")))
                .andExpect(header().string("Set-Cookie",
                        containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/auth")));

        verifyNoInteractions(jwtProvider, studentService);
    }

    @Test
    void logoutIsNoOpForAnonymousCaller() throws Exception {
        // No cookie attached — endpoint should still 200 + Set-Cookie clear.
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }
}
