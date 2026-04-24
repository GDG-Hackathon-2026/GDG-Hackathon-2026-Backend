package com.moggo._gdg.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Ping", description = "서버 기동/연결 확인용 엔드포인트")
public class PingController {

    @GetMapping("/ping")
    @Operation(summary = "Ping the server",
            description = "프론트/외부에서 백엔드 도달 여부와 CORS 설정 검증용")
    public Map<String, String> ping() {
        return Map.of(
                "message", "pong",
                "service", "2026gdg"
        );
    }
}
