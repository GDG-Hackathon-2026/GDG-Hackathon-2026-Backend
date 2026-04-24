package com.moggo._gdg.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression("'${gemini.project:}' != ''")
public class GeminiService {

    private final Client client;
    private final String model;

    public GeminiService(Client client,
                         @Value("${gemini.model:gemini-2.5-flash}") String model) {
        this.client = client;
        this.model = model;
    }

    /** 단순 prompt → text. Ping/테스트용. */
    public String generate(String prompt) {
        GenerateContentResponse response = client.models.generateContent(model, prompt, null);
        return response.text();
    }

    /** prompt + 출력 토큰 제한 → 응답 텍스트와 usage 메타데이터. */
    public GenerationResult generateWithUsage(String prompt, int maxOutputTokens) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .maxOutputTokens(Math.max(1, Math.min(maxOutputTokens, 2048)))
                .build();
        GenerateContentResponse response = client.models.generateContent(model, prompt, config);
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
