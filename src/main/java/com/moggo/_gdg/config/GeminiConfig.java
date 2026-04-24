package com.moggo._gdg.config;

import com.google.genai.Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("'${gemini.project:}' != ''")
public class GeminiConfig {

    @Bean
    Client geminiClient(
            @Value("${gemini.project}") String project,
            @Value("${gemini.location:us-central1}") String location) {
        return Client.builder()
                .vertexAI(true)
                .project(project)
                .location(location)
                .build();
    }
}
