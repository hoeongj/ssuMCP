package com.ssuai.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;

import com.ssuai.global.exception.UnauthorizedException;

class AuthUserArgumentResolverTests {

    private AuthUserArgumentResolver resolver;
    private NativeWebRequest webRequest;

    @BeforeEach
    void setUp() {
        resolver = new AuthUserArgumentResolver();
        webRequest = mock(NativeWebRequest.class);
    }

    @Test
    void supportsParameterReturnsTrueWhenAnnotatedAndIsString() throws Exception {
        MethodParameter parameter = mock(MethodParameter.class);
        when(parameter.hasParameterAnnotation(AuthUser.class)).thenReturn(true);
        // We need to return String.class as the parameter type
        when(parameter.getParameterType()).thenAnswer(inv -> String.class);

        boolean result = resolver.supportsParameter(parameter);

        assertThat(result).isTrue();
    }

    @Test
    void supportsParameterReturnsFalseWhenNotAnnotated() {
        MethodParameter parameter = mock(MethodParameter.class);
        when(parameter.hasParameterAnnotation(AuthUser.class)).thenReturn(false);
        when(parameter.getParameterType()).thenAnswer(inv -> String.class);

        boolean result = resolver.supportsParameter(parameter);

        assertThat(result).isFalse();
    }

    @Test
    void supportsParameterReturnsFalseWhenAnnotatedButNotString() {
        MethodParameter parameter = mock(MethodParameter.class);
        when(parameter.hasParameterAnnotation(AuthUser.class)).thenReturn(true);
        when(parameter.getParameterType()).thenAnswer(inv -> Long.class);

        boolean result = resolver.supportsParameter(parameter);

        assertThat(result).isFalse();
    }

    @Test
    void resolveArgumentReturnsStudentIdWhenAttributeIsPresentAndNonBlank() {
        when(webRequest.getAttribute(AuthAttributes.STUDENT_ID, RequestAttributes.SCOPE_REQUEST))
                .thenReturn("20241234");

        Object result = resolver.resolveArgument(null, null, webRequest, null);

        assertThat(result).isEqualTo("20241234");
    }

    @Test
    void resolveArgumentThrowsUnauthorizedExceptionWhenAttributeIsMissing() {
        when(webRequest.getAttribute(AuthAttributes.STUDENT_ID, RequestAttributes.SCOPE_REQUEST))
                .thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, webRequest, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void resolveArgumentThrowsUnauthorizedExceptionWhenAttributeIsBlank() {
        when(webRequest.getAttribute(AuthAttributes.STUDENT_ID, RequestAttributes.SCOPE_REQUEST))
                .thenReturn("   ");

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, webRequest, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void resolveArgumentThrowsUnauthorizedExceptionWhenAttributeIsNotString() {
        when(webRequest.getAttribute(AuthAttributes.STUDENT_ID, RequestAttributes.SCOPE_REQUEST))
                .thenReturn(12345L);

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, webRequest, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void resolveArgumentReturnsNullWhenOptionalAttributeIsMissing() throws Exception {
        MethodParameter parameter = methodParameter("optionalAuthUser");
        when(webRequest.getAttribute(AuthAttributes.STUDENT_ID, RequestAttributes.SCOPE_REQUEST))
                .thenReturn(null);

        Object result = resolver.resolveArgument(parameter, null, webRequest, null);

        assertThat(result).isNull();
    }

    @Test
    void resolveArgumentReturnsNullWhenOptionalAttributeIsBlank() throws Exception {
        MethodParameter parameter = methodParameter("optionalAuthUser");
        when(webRequest.getAttribute(AuthAttributes.STUDENT_ID, RequestAttributes.SCOPE_REQUEST))
                .thenReturn("   ");

        Object result = resolver.resolveArgument(parameter, null, webRequest, null);

        assertThat(result).isNull();
    }

    private MethodParameter methodParameter(String methodName) throws NoSuchMethodException {
        Method method = AuthUserArgumentResolverTests.class.getDeclaredMethod(methodName, String.class);
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    private void optionalAuthUser(@AuthUser(required = false) String studentId) {
    }
}
