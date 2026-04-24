package com.moggo._gdg.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 개발·테스트 전용 우회 필터.
 * <p>
 * {@code X-Dev-Uid} 헤더가 있으면 그 값을 그대로 principal(uid) 로 세팅해 Firebase 인증을 생략한다.
 * {@code dev.auth.bypass=true} (환경변수 {@code DEV_AUTH_BYPASS=true}) 일 때만 빈 등록되므로
 * 기본 상태에선 아예 존재하지 않는다. 해커톤 기간 중 EC2 에서 토큰 없이 curl 테스트가 필요할 때
 * {@code ~/app/.env} 에 플래그 추가 → 재배포로 켤 수 있다.
 * <p>
 * FirebaseTokenFilter 이후에 돌아서 실제 Firebase 토큰이 유효하면 그게 우선이고, 없을 때만 fallback 한다.
 */
@Component
@ConditionalOnProperty(name = "dev.auth.bypass", havingValue = "true")
public class DevAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DevAuthFilter.class);
    private static final String HEADER = "X-Dev-Uid";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String devUid = request.getHeader(HEADER);
            if (devUid != null && !devUid.isBlank()) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                devUid.trim(),
                                null,
                                AuthorityUtils.NO_AUTHORITIES
                        )
                );
                log.warn("DEV-AUTH bypass used on {} {} with X-Dev-Uid={} — DO NOT USE IN PROD",
                        request.getMethod(), request.getRequestURI(), devUid);
            }
        }
        chain.doFilter(request, response);
    }
}
