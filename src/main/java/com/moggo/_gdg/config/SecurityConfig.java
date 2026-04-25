package com.moggo._gdg.config;

import com.moggo._gdg.security.DevAuthFilter;
import com.moggo._gdg.security.FirebaseTokenFilter;
import com.moggo._gdg.security.JsonAuthEntryPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String[] PUBLIC_PATHS = {
            "/actuator/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/api/ping"
    };

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    ObjectProvider<FirebaseTokenFilter> firebaseFilterProvider,
                                    ObjectProvider<DevAuthFilter> devAuthFilterProvider,
                                    JsonAuthEntryPoint jsonAuthEntryPoint,
                                    @Value("${gemini.raw-public:false}") boolean geminiRawPublic) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jsonAuthEntryPoint)
                        .accessDeniedHandler(jsonAuthEntryPoint)
                )
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers(PUBLIC_PATHS).permitAll();
                    if (geminiRawPublic) {
                        log.warn("gemini.raw-public=true — POST /api/gemini/generate 가 인증 없이 공개됨. "
                                + "프롬프트 엔지니어링 전용. 해커톤 종료 후 반드시 끄세요.");
                        auth.requestMatchers(HttpMethod.POST, "/api/gemini/generate").permitAll();
                    }
                    auth.anyRequest().authenticated();
                });

        FirebaseTokenFilter firebaseFilter = firebaseFilterProvider.getIfAvailable();
        if (firebaseFilter != null) {
            http.addFilterBefore(firebaseFilter, UsernamePasswordAuthenticationFilter.class);
        }

        // dev/local 프로파일 전용 우회 필터 — Firebase 이후에 돌아서 실제 토큰이 우선
        DevAuthFilter devAuthFilter = devAuthFilterProvider.getIfAvailable();
        if (devAuthFilter != null) {
            if (firebaseFilter != null) {
                http.addFilterAfter(devAuthFilter, FirebaseTokenFilter.class);
            } else {
                http.addFilterBefore(devAuthFilter, UsernamePasswordAuthenticationFilter.class);
            }
        }

        return http.build();
    }
}
