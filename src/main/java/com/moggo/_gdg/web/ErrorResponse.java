package com.moggo._gdg.web;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "오류 응답 공통 포맷")
public record ErrorResponse(
        @Schema(description = "HTTP 상태 코드", example = "502")
        int status,

        @Schema(description = "HTTP 상태 문구", example = "Bad Gateway")
        String error,

        @Schema(description = "사람이 읽을 수 있는 오류 메시지",
                example = "LLM upstream error: billing not enabled on GCP project")
        String message,

        @Schema(description = "원인 분류 키. 프론트에서 분기 처리용.",
                example = "GEMINI_UPSTREAM",
                allowableValues = {"GEMINI_UPSTREAM", "AUTH", "VALIDATION", "NOT_FOUND", "MELTED", "INTERNAL"})
        String code
) {}
