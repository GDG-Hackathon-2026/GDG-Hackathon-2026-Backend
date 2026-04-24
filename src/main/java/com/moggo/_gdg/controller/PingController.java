package com.moggo._gdg.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Ping", description = "인증 없이 호출 가능한 서버 기동/도달성 확인 엔드포인트")
public class PingController {

    @GetMapping("/ping")
    @Operation(
            summary = "서버 생존 확인",
            description = """
                    **public 엔드포인트** — 인증 토큰 불필요.

                    용도:
                    - 프론트 배포 직후 백엔드 도달성 확인
                    - CORS preflight 없이 서버가 응답하는지 가장 가벼운 체크
                    - 모니터링/uptime 프로브 (Prometheus 스크랩과는 별개, 단순 HTTP 200)
                    """,
            security = {}, // 전역 bearerAuth 제외
            responses = @ApiResponse(
                    responseCode = "200",
                    description = "정상",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(example = "{\"message\":\"pong\",\"service\":\"2026gdg\"}"),
                            examples = @ExampleObject(value = "{\"message\":\"pong\",\"service\":\"2026gdg\"}")
                    )
            )
    )
    public Map<String, String> ping() {
        return Map.of(
                "message", "pong",
                "service", "2026gdg"
        );
    }
}
