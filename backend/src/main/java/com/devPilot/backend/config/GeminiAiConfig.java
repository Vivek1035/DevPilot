package com.devPilot.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Gemini AI beans that are not auto-configured by
 * {@code spring-ai-starter-model-google-genai}.
 *
 * <p>Spring AI 2.0's google-genai starter ships only a {@code ChatModel}.
 * The {@link EmbeddingModel} used by pgvector must be provided manually.
 */
@Configuration
public class GeminiAiConfig {

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${spring.ai.google.genai.api-key}") String apiKey) {
        return new GeminiEmbeddingModel(apiKey);
    }
}

