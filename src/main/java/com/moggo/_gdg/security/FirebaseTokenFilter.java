package com.moggo._gdg.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@ConditionalOnBean(FirebaseAuth.class)
public class FirebaseTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenFilter.class);

    private final FirebaseAuth firebaseAuth;

    public FirebaseTokenFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            log.warn("AUTH-MISS {} {} — no Authorization header", request.getMethod(), path);
        } else if (!header.startsWith("Bearer ")) {
            log.warn("AUTH-BAD {} {} — header does not start with 'Bearer '. value starts with: '{}'",
                    request.getMethod(), path,
                    header.substring(0, Math.min(8, header.length())));
        } else {
            String idToken = header.substring(7);
            try {
                FirebaseToken decoded = firebaseAuth.verifyIdToken(idToken);
                Authentication auth = new UsernamePasswordAuthenticationToken(
                        decoded.getUid(),
                        null,
                        AuthorityUtils.NO_AUTHORITIES
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.info("AUTH-OK {} {} uid={}", request.getMethod(), path, decoded.getUid());
            } catch (FirebaseAuthException e) {
                log.warn("AUTH-FAIL {} {} — verifyIdToken failed: code={} msg={}",
                        request.getMethod(), path, e.getAuthErrorCode(), e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
