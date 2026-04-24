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

@Service
@ConditionalOnExpression("'${gemini.project:}' != ''")
public class GeminiService {

    private final Client client;
    private final String model;
    private final float temperature;
    private final float topP;

    public GeminiService(Client client,
                         @Value("${gemini.model:gemini-2.5-flash}") String model,
                         @Value("${gemini.temperature:1.2}") float temperature,
                         @Value("${gemini.top-p:0.95}") float topP) {
        this.client = client;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
    }

    /** 단순 prompt → text. Ping/테스트용 (system prompt 없음, 모델 기본 파라미터). */
    public String generate(String prompt) {
        GenerateContentResponse response = client.models.generateContent(model, prompt, null);
        return response.text();
    }

    /** prompt + 출력 토큰 제한 + (옵션) system instruction → 응답 텍스트와 usage 메타데이터.
     *  temperature/topP 는 application.yml 기반 고정값 적용 (장황·창의적 응답 유도). */
    public GenerationResult generateWithUsage(String prompt, int maxOutputTokens, String systemInstruction) {
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                .maxOutputTokens(Math.max(1, Math.min(maxOutputTokens, 2048)))
                .temperature(temperature)
                .topP(topP);

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            Content sysContent = Content.fromParts(Part.fromText(systemInstruction));
            configBuilder.systemInstruction(sysContent);
        }

        GenerateContentResponse response = client.models.generateContent(model, prompt, configBuilder.build());
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

    public record GenerationResult(String text, int promptTokens, int completionTokens) {}
}
