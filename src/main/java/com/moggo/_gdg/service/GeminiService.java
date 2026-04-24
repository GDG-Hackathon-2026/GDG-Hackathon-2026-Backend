package com.moggo._gdg.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
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

    public String generate(String prompt) {
        GenerateContentResponse response = client.models.generateContent(model, prompt, null);
        return response.text();
    }
}
