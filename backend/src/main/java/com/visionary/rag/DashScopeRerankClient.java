package com.visionary.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.AiApiConfig;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class DashScopeRerankClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AiApiConfig config;
    private final ObjectMapper objectMapper;

    private volatile OkHttpClient httpClient;

    public boolean isConfigured() {
        return config.isDashScopeConfigured();
    }

    public List<VectorDbService.KnowledgeFragment> rerank(
            String query,
            List<VectorDbService.KnowledgeFragment> fragments,
            int topK
    ) throws IOException {
        if (!isConfigured() || fragments == null || fragments.isEmpty()) {
            return fragments == null ? List.of() : fragments.stream().limit(Math.max(1, topK)).toList();
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getDashScopeRerankModel());
        ObjectNode input = body.putObject("input");
        input.put("query", query);
        ArrayNode documents = input.putArray("documents");
        for (VectorDbService.KnowledgeFragment fragment : fragments) {
            documents.add(fragment.content());
        }
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("top_n", Math.max(1, topK));
        parameters.put("return_documents", false);

        Request request = new Request.Builder()
                .url(config.getDashScopeRerankUrl())
                .addHeader("Authorization", "Bearer " + config.getDashScopeKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("DashScope rerank HTTP " + response.code() + ": " + abbreviate(responseBody));
            }
            JsonNode results = objectMapper.readTree(responseBody).path("output").path("results");
            if (!results.isArray()) {
                return fragments.stream().limit(Math.max(1, topK)).toList();
            }
            return toRanked(results, fragments, topK);
        }
    }

    private List<VectorDbService.KnowledgeFragment> toRanked(JsonNode results, List<VectorDbService.KnowledgeFragment> fragments, int topK) {
        record Ranked(int index, double score) {}
        return java.util.stream.StreamSupport.stream(results.spliterator(), false)
                .map(node -> new Ranked(
                        node.path("index").asInt(-1),
                        node.path("relevance_score").asDouble(node.path("score").asDouble(0.0))
                ))
                .filter(item -> item.index() >= 0 && item.index() < fragments.size())
                .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                .limit(Math.max(1, topK))
                .map(item -> fragments.get(item.index()))
                .toList();
    }

    private OkHttpClient client() {
        OkHttpClient local = httpClient;
        if (local == null) {
            synchronized (this) {
                local = httpClient;
                if (local == null) {
                    local = new OkHttpClient.Builder()
                            .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(config.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                            .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                            .build();
                    httpClient = local;
                }
            }
        }
        return local;
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 360 ? text : text.substring(0, 360) + "...";
    }
}
