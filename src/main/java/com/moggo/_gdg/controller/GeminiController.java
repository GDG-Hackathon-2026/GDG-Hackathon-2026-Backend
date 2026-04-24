package com.moggo._gdg.controller;

import com.moggo._gdg.service.GeminiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/gemini")
@ConditionalOnExpression("'${gemini.project:}' != ''")
@Tag(name = "Gemini (raw)",
        description = """
                Vertex AI Gemini 를 **탄소/녹아내림 로직 없이** 바로 호출하는 테스트 엔드포인트.

                프로덕션 채팅 기능은 `/api/conversations/*` 를 사용. 이 엔드포인트는 SDK 연동 검증·데모 용도로만 유지.
                **응답에 탄소 누적이 반영되지 않으므로** 탄소 모니터링 기획과는 별개.
                """)
public class GeminiController {

    private final GeminiService geminiService;

    public GeminiController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    @PostMapping("/generate")
    @Operation(
            summary = "단발성 Gemini 호출 (탄소 추적 없음)",
            description = """
                    단일 prompt 를 Gemini 모델에 전달하고 응답 텍스트만 반환. 대화 이력·탄소 누적·입력 자르기 모두 없음.

                    프론트에서는 이 엔드포인트 대신 **`POST /api/conversations/{id}/messages`** 를 사용해야 한다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Gemini 응답",
                    content = @Content(
                            schema = @Schema(example = "{\"response\":\"...\"}"),
                            examples = @ExampleObject(value = "{\"response\":\"Pong, Pong, Pong\"}")
                    )
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "500", description = "Gemini API 오류")
    })
    public Map<String, String> generate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(examples = @ExampleObject(
                            value = "{\"prompt\":\"Say pong in three languages. One word each, comma separated.\"}"))
            )
            @RequestBody GenerateRequest request) {
        String text = geminiService.generate(request.prompt());
        return Map.of("response", text);
    }

    @Schema(description = "Gemini 호출 요청 body")
    public record GenerateRequest(
            @Schema(description = "Gemini 에 보낼 프롬프트 (길이 제한 없음 — 이 엔드포인트에선 자르지 않음)",
                    example = "Say pong in three languages. One word each, comma separated.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String prompt
    ) {}
}
