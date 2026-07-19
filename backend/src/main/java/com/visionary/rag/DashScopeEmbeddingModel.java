package com.visionary.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible DashScope embedding model for Java-side RAG queries.
 */
public class DashScopeEmbeddingModel implements EmbeddingModel {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int dimension;

    public DashScopeEmbeddingModel(
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            String model,
            int dimension,
            int timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
        this.endpoint = stripTrailingSlash(baseUrl) + "/embeddings";
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        if (textSegments == null || textSegments.isEmpty()) {
            return Response.from(List.of());
        }

        List<String> input = textSegments.stream()
                .map(TextSegment::text)
                .toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("input", input);

        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("DashScope embeddings failed: HTTP "
                        + response.statusCode() + " " + response.body());
            }

            JsonNode data = objectMapper.readTree(response.body()).path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("DashScope embeddings response missing data array");
            }

            List<Embedding> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode vectorNode = item.path("embedding");
                float[] vector = new float[vectorNode.size()];
                for (int i = 0; i < vectorNode.size(); i++) {
                    vector[i] = (float) vectorNode.get(i).asDouble();
                }
                embeddings.add(Embedding.from(vector));
            }
            return Response.from(embeddings);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call DashScope embeddings", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DashScope embeddings interrupted", e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
