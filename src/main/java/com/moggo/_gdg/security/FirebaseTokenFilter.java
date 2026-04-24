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
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String idToken = header.substring(7);
            try {
                FirebaseToken decoded = firebaseAuth.verifyIdToken(idToken);
                Authentication auth = new UsernamePasswordAuthenticationToken(
                        decoded.getUid(),
                        null,
                        AuthorityUtils.NO_AUTHORITIES
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (FirebaseAuthException e) {
                log.debug("Firebase token verification failed: {}", e.getMessage());
                // leave SecurityContext empty; downstream endpoint-level auth enforcement will reject
            }
        }
        chain.doFilter(request, response);
    }
}
