package com.devPilot.backend.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Custom {@link EmbeddingModel} that calls Google's Gemini text-embedding-004 model
 * via the Generative Language REST API (same API key used for Gemini chat).
 *
 * <p>Spring AI 2.0's {@code spring-ai-starter-model-google-genai} does not include
 * an EmbeddingModel implementation, so we provide one here.
 *
 * <p>Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent
 */
@Slf4j
public class GeminiEmbeddingModel implements EmbeddingModel {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String EMBED_PATH = "/v1beta/models/gemini-embedding-001:embedContent";

    /** Output dimension of gemini-embedding-001 (using Matryoshka outputDimensionality 768 for pgvector HNSW compatibility) */
    public static final int DIMENSIONS = 768;

    private final RestClient restClient;
    private final String apiKey;

    public GeminiEmbeddingModel(String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            String text = request.getInstructions().get(i);
            float[] vector = embed(text);
            embeddings.add(new Embedding(vector, i));
        }
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata("gemini-embedding-001", null));
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        EmbedRequest body = new EmbedRequest(new ContentPart(List.of(new Part(text))), DIMENSIONS);

        EmbedResponse response = restClient.post()
                .uri(EMBED_PATH + "?key={key}", apiKey)
                .body(body)
                .retrieve()
                .body(EmbedResponse.class);

        if (response == null || response.embedding() == null) {
            throw new IllegalStateException("Empty embedding response from Gemini API");
        }
        List<Float> vals = response.embedding().values();
        float[] result = new float[vals.size()];
        for (int i = 0; i < vals.size(); i++) {
            result[i] = vals.get(i);
        }
        return result;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    // ── Request / Response records ───────────────────────────────────────────

    private record EmbedRequest(ContentPart content, int outputDimensionality) {}

    private record ContentPart(List<Part> parts) {}

    private record Part(String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedResponse(EmbeddingValues embedding) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingValues(List<Float> values) {}
}

