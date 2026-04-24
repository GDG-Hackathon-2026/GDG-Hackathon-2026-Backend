package com.moggo._gdg.controller;

import com.moggo._gdg.service.GeminiService;
import io.swagger.v3.oas.annotations.Operation;
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
@Tag(name = "Gemini", description = "Google Gemini LLM 연동 테스트 엔드포인트")
public class GeminiController {

    private final GeminiService geminiService;

    public GeminiController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate text with Gemini",
            description = "전달한 prompt 를 Gemini 모델에 보내 응답 텍스트를 반환")
    public Map<String, String> generate(@RequestBody GenerateRequest request) {
        String text = geminiService.generate(request.prompt());
        return Map.of("response", text);
    }

    public record GenerateRequest(String prompt) {
    }
}
