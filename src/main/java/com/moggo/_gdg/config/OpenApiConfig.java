package com.moggo._gdg.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";
    private static final String DEV_UID = "devUid";

    @Bean
    OpenAPI gdgOpenApi(Environment environment) {
        boolean devBypass = Boolean.parseBoolean(environment.getProperty("dev.auth.bypass", "false"));

        Components components = new Components()
                .addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Firebase Authentication ID token. 프론트에서 `user.getIdToken()` 으로 획득."));

        List<SecurityRequirement> security = new ArrayList<>();
        security.add(new SecurityRequirement().addList(BEARER));

        if (devBypass) {
            components.addSecuritySchemes(DEV_UID, new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("X-Dev-Uid")
                    .description("""
                            **개발·테스트 전용 우회 헤더.** 임의의 uid 문자열을 넣으면 Firebase 토큰 없이 그 사용자인 척 요청을 보낼 수 있음.

                            환경변수 `DEV_AUTH_BYPASS=true` 일 때만 이 스키마가 노출되고 백엔드 필터도 동작한다. 끄면 스키마도 사라지고 헤더도 무시됨.

                            Swagger 의 **Authorize** 버튼 → `devUid` 칸에 `test-user-1` 같은 값을 넣고 사용.
                            """));
            security.add(new SecurityRequirement().addList(DEV_UID));
        }

        return new OpenAPI()
                .info(new Info()
                        .title("GDG Hackathon 2026 Backend API")
                        .version("v0.1")
                        .description("""
                                ### 기획 요약
                                "당신의 UI/UX가 서서히 녹아내린다, Polar.ai" — AI 사용에 따른 탄소 배출을 누적 추적하고
                                그 양에 비례해 UI 녹아내림 + 컨텍스트 창 축소를 유도해 사용자에게 환경 영향을 체감시킨다.

                                ### 인증
                                거의 모든 엔드포인트는 Firebase Authentication ID token 이 필요하다.
                                프론트에서 `signInAnonymously()` 후 `user.getIdToken()` 으로 얻은 JWT 를
                                `Authorization: Bearer <token>` 헤더로 보낸다. 우상단 **Authorize** 버튼에 토큰을 넣으면
                                Swagger UI 에서 바로 테스트 가능.

                                **(개발 환경 전용) `X-Dev-Uid` 헤더** 로 Firebase 없이 테스트 가능. prod 배포에선 비활성.

                                ### 탄소 환산 (기본값, application.yml 에서 조정 가능)
                                - 입력 토큰 1000개당 0.015 gCO₂eq
                                - 출력 토큰 1000개당 0.025 gCO₂eq

                                ### 녹아내림 단계 (stage = 누적 탄소량 기반)
                                | stage | 누적 탄소 (gCO₂eq) | 허용 input tokens |
                                |-------|-------------------|-------------------|
                                | 0 | < 20 | 8192 |
                                | 1 | 20 ~ 50 | 4096 |
                                | 2 | 50 ~ 100 | 2048 |
                                | 3 | 100 ~ 200 | 1024 |
                                | 4 | 200 ~ 500 | 512 |
                                | 5 | ≥ 500 | 0 (요청 거부 — 402) |

                                `meltingPercent` 는 현재 stage 내부의 0~100% 진행률. 프론트에서 UI 강도 smooth 보간용.
                                """))
                .servers(List.of(
                        new Server().url("http://13.125.197.228:8080").description("EC2 (prod)"),
                        new Server().url("http://localhost:8080").description("Local dev")
                ))
                .components(components)
                .security(security);
    }
}
