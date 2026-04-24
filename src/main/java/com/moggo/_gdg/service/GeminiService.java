package com.moggo._gdg.service;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnExpression("'${gemini.project:}' != ''")
public class GeminiService {

    private final Client client;
    private final String model;
    private final float temperature;
    private final float topP;
    private final int maxOutputTokens;

    public GeminiService(Client client,
                         @Value("${gemini.model:gemini-2.5-flash}") String model,
                         @Value("${gemini.temperature:1.2}") float temperature,
                         @Value("${gemini.top-p:0.95}") float topP,
                         @Value("${gemini.max-output-tokens:8192}") int maxOutputTokens) {
        this.client = client;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxOutputTokens = maxOutputTokens;
    }

    /** 단순 prompt → text. Ping/테스트용 (system prompt 없음, 모델 기본 파라미터). */
    public String generate(String prompt) {
        GenerateContentResponse response = client.models.generateContent(model, prompt, null);
        return response.text();
    }

    /**
     * 멀티턴 대화: role 이 부여된 Content 목록을 그대로 Gemini 에 전달.
     * 리스트 마지막은 이번에 보낼 user 턴이어야 한다.
     * 출력 길이는 설정값 {@code gemini.max-output-tokens} 하나로 고정 (stage 와 독립).
     */
    public GenerationResult generateWithHistory(List<Content> contents, String systemInstruction) {
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                .maxOutputTokens(maxOutputTokens)
                .temperature(temperature)
                .topP(topP);

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            configBuilder.systemInstruction(Content.fromParts(Part.fromText(systemInstruction)));
        }

        GenerateContentResponse response = client.models.generateContent(model, contents, configBuilder.build());
        String text = response.text();

        int promptTokens = 0;
        int completionTokens = 0;
        var usageOpt = response.usageMetadata();
        if (usageOpt.isPresent()) {
            GenerateContentResponseUsageMetadata usage = usageOpt.get();
            promptTokens = usage.promptTokenCount().orElse(0);
            completionTokens = usage.candidatesTokenCount().orElse(0);
        }
        return new GenerationResult(text, promptTokens, completionTokens);
    }

    /** 편의용 단일턴 오버로드 (내부적으로 generateWithHistory 사용). */
    public GenerationResult generateWithUsage(String prompt, String systemInstruction) {
        Content single = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(prompt)))
                .build();
        return generateWithHistory(List.of(single), systemInstruction);
    }

    public record GenerationResult(String text, int promptTokens, int completionTokens) {}
}
