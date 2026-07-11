package com.ssuai.global.auth;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.ssuai.global.exception.UnauthorizedException;

/**
 * Resolves the {@code @AuthUser String studentId} controller parameter
 * by extracting the student ID populated by {@link JwtAuthFilter} in the request attributes.
 */
@Component
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthUser.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Object studentId = webRequest.getAttribute(
                AuthAttributes.STUDENT_ID,
                RequestAttributes.SCOPE_REQUEST);
        if (studentId instanceof String id && !id.isBlank()) {
            return id;
        }
        if (studentId == null || studentId instanceof String) {
            if (!isRequired(parameter)) {
                return null;
            }
        }
        throw new UnauthorizedException();
    }

    private boolean isRequired(MethodParameter parameter) {
        AuthUser authUser = parameter == null ? null : parameter.getParameterAnnotation(AuthUser.class);
        return authUser == null || authUser.required();
    }
}
