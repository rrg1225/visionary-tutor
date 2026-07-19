package com.visionary.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.AiApiConfig;
import com.visionary.service.LocalMockService;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class DashScopeImageClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AiApiConfig config;
    private final ObjectMapper objectMapper;
    private final ProviderCircuitBreaker circuitBreaker;
    private final ObjectProvider<LocalMockService> localMockServiceProvider;

    private volatile OkHttpClient httpClient;

    public boolean isConfigured() {
        return config.isDashScopeConfigured();
    }

    public String generateTeachingKeyframe(String prompt) throws IOException {
        LocalMockService localMockService = localMockServiceProvider.getIfAvailable();
        if (localMockService != null && localMockService.isEnabled()) {
            return localMockService.imageUrl();
        }
        circuitBreaker.requireAvailable("dashscope-image");
        if (!isConfigured()) {
            throw new IOException("DASHSCOPE_API_KEY is not configured");
        }
        try {
            String taskId = submitImageTask(prompt);
            long deadline = System.currentTimeMillis() + 30_000L;
            MediaTaskStatus status = new MediaTaskStatus(taskId, "PENDING", 15, null, null, null);
            while (System.currentTimeMillis() < deadline) {
                sleep(1500L);
                status = queryTask(taskId);
                if (status.succeeded() || status.failed()) {
                    break;
                }
            }
            if (status.mediaUrl() == null || status.mediaUrl().isBlank()) {
                throw new IOException("DashScope image task not ready: " + status.status());
            }
            circuitBreaker.recordSuccess("dashscope-image");
            return status.mediaUrl();
        } catch (IOException ex) {
            circuitBreaker.recordFailure("dashscope-image");
            throw ex;
        }
    }

    private String submitImageTask(String prompt) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getDashScopeImageModel());
        ObjectNode input = body.putObject("input");
        input.put("prompt", prompt);
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("size", "1024*1024");
        parameters.put("n", 1);

        Request request = new Request.Builder()
                .url(config.getDashScopeImageUrl())
                .addHeader("Authorization", "Bearer " + config.getDashScopeKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("X-DashScope-Async", "enable")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("DashScope image HTTP " + response.code() + ": " + abbreviate(responseBody));
            }
            JsonNode root = objectMapper.readTree(responseBody);
            String taskId = root.path("output").path("task_id").asText("");
            if (taskId.isBlank()) {
                taskId = root.path("task_id").asText("");
            }
            if (taskId.isBlank()) {
                throw new IOException("DashScope image response missing task_id: " + root);
            }
            return taskId;
        }
    }

    private MediaTaskStatus queryTask(String taskId) throws IOException {
        Request request = new Request.Builder()
                .url(trimSlash(config.getDashScopeTaskUrl()) + "/" + taskId)
                .addHeader("Authorization", "Bearer " + config.getDashScopeKey())
                .get()
                .build();
        try (Response response = client().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("DashScope task HTTP " + response.code() + ": " + abbreviate(responseBody));
            }
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode output = root.path("output");
            String status = output.path("task_status").asText(root.path("task_status").asText("UNKNOWN"));
            String imageUrl = firstImageUrl(output);
            return new MediaTaskStatus(taskId, status, imageUrl == null ? 50 : 100, imageUrl, imageUrl, output.path("message").asText(null));
        }
    }

    private String firstImageUrl(JsonNode node) {
        JsonNode results = node.path("results");
        if (results.isArray() && !results.isEmpty()) {
            JsonNode first = results.get(0);
            String url = first.path("url").asText("");
            if (!url.isBlank()) return url;
        }
        String url = node.path("url").asText("");
        return url.isBlank() ? null : url;
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 360 ? text : text.substring(0, 360) + "...";
    }
}
