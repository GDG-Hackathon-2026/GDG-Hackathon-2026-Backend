package com.moggo._gdg.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moggo._gdg.web.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Spring Security 의 인증/인가 실패 응답을 {@link ErrorResponse} 공통 포맷으로 통일.
 * - 토큰 없거나 검증 실패 → 401 (AuthenticationEntryPoint)
 * - 토큰은 있지만 권한 부족 → 403 (AccessDeniedHandler)
 *
 * <p>현재 시스템은 역할 기반 인가가 없어서 사실상 401 만 발생하지만 양쪽 핸들러 모두 등록해둔다.
 */
@Component
public class JsonAuthEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writeError(response, HttpStatus.UNAUTHORIZED,
                "authentication required: supply Authorization: Bearer <idToken> (or X-Dev-Uid in dev)");
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writeError(response, HttpStatus.FORBIDDEN,
                "access denied: " + accessDeniedException.getMessage());
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(), message, "AUTH");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
