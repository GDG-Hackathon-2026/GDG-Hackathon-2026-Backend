package com.moggo._gdg.controller;

import com.moggo._gdg.domain.Persona;
import com.moggo._gdg.service.GeminiService;
import com.moggo._gdg.service.PromptTemplateService;
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
@Tag(name = "99.Gemini (raw)",
        description = """
                Vertex AI Gemini 를 **탄소/녹아내림 로직 없이** 바로 호출하는 프롬프트 엔지니어링 전용 엔드포인트.

                프로덕션 채팅 기능은 `/api/conversations/*` 를 사용. 이 엔드포인트는 SDK 연동 검증·페르소나 튜닝 용도.
                **응답에 탄소 누적이 반영되지 않으므로** 탄소 모니터링 기획과는 별개.

                환경변수 `GEMINI_RAW_PUBLIC=true` 인 경우 **인증 없이도** 호출 가능 (해커톤 기간 한정).
                """)
public class GeminiController {

    private final GeminiService geminiService;
    private final PromptTemplateService promptTemplateService;

    public GeminiController(GeminiService geminiService, PromptTemplateService promptTemplateService) {
        this.geminiService = geminiService;
        this.promptTemplateService = promptTemplateService;
    }

    @PostMapping("/generate")
    @Operation(
            summary = "단발성 Gemini 호출 (탄소 추적 없음)",
            description = """
                    단일 prompt 를 Gemini 모델에 전달하고 응답 텍스트만 반환. 대화 이력·탄소 누적·입력 자르기 모두 없음.

                    **system prompt 규칙** (우선순위 높→낮):
                    1. `systemPrompt` 가 있으면 그 값을 그대로 사용 (빈 문자열 전달 시 시스템 프롬프트 없이 호출).
                    2. 없고 `persona` 가 있으면 해당 페르소나 .md 를 시스템 프롬프트로 사용.
                    3. 둘 다 없으면 active 템플릿 (`chat.prompt.active-template`, 기본 `polar-bear-grandpa`) 적용.

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
            @ApiResponse(responseCode = "401", description = "인증 실패 (GEMINI_RAW_PUBLIC=false 인 경우)"),
            @ApiResponse(responseCode = "500", description = "Gemini API 오류")
    })
    public Map<String, String> generate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(examples = {
                            @ExampleObject(name = "active 템플릿 사용 (기본)",
                                    value = "{\"prompt\":\"너 이름이 뭐야?\"}"),
                            @ExampleObject(name = "페르소나 지정",
                                    value = "{\"prompt\":\"오늘 날씨 어때?\",\"persona\":\"POLAR_BEAR_GIRL\"}"),
                            @ExampleObject(name = "커스텀 시스템 프롬프트 override",
                                    value = "{\"prompt\":\"안녕?\",\"systemPrompt\":\"너는 츤데레 고양이다. 매 문장을 '냥'으로 끝내라.\"}"),
                            @ExampleObject(name = "시스템 프롬프트 없이",
                                    value = "{\"prompt\":\"Say pong in three languages.\",\"systemPrompt\":\"\"}")
                    })
            )
            @RequestBody GenerateRequest request) {
        String systemPrompt;
        if (request.systemPrompt() != null) {
            systemPrompt = request.systemPrompt();
        } else if (request.persona() != null) {
            systemPrompt = promptTemplateService.getSystemPromptByKey(request.persona().promptKey());
        } else {
            systemPrompt = promptTemplateService.getActiveSystemPrompt();
        }
        GeminiService.GenerationResult result = geminiService.generateWithUsage(request.prompt(), systemPrompt);
        return Map.of("response", result.text());
    }

    @Schema(description = "Gemini 호출 요청 body")
    public record GenerateRequest(
            @Schema(description = "Gemini 에 보낼 user 프롬프트 (길이 제한 없음)",
                    example = "너 이름이 뭐야?",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String prompt,
            @Schema(description = "시스템 프롬프트 override. null 이면 persona / active 템플릿으로 fallback, 빈 문자열이면 시스템 프롬프트 없이 호출.",
                    example = "너는 츤데레 고양이다. 매 문장을 '냥'으로 끝내라.",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    nullable = true)
            String systemPrompt,
            @Schema(description = "북극곰 페르소나. systemPrompt 가 null 인 경우 이 값으로 시스템 프롬프트 결정. "
                    + "둘 다 null 이면 active 템플릿. 가용 값은 GET /api/personas 참고",
                    example = "POLAR_BEAR_BOY",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    nullable = true)
            Persona persona
    ) {}
}
