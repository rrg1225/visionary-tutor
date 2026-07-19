package com.visionary.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.AiApiConfig;
import com.visionary.service.LocalMockService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class HttpAiClientSupport {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AiApiConfig config;
    private final ProviderCircuitBreaker circuitBreaker;
    private final ObjectProvider<LocalMockService> localMockServiceProvider;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpAiClientSupport(
            AiApiConfig config,
            ObjectMapper objectMapper,
            ProviderCircuitBreaker circuitBreaker,
            ObjectProvider<LocalMockService> localMockServiceProvider
    ) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.localMockServiceProvider = localMockServiceProvider;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    public String postJsonWithRetry(String url, String bearerToken, String jsonBody) throws IOException {
        String provider = providerFromUrl(url);
        LocalMockService localMockService = localMockServiceProvider.getIfAvailable();
        if (localMockService != null && localMockService.isEnabled()) {
            return localMockService.openAiCompatibleResponse(provider);
        }
        circuitBreaker.requireAvailable(provider);
        IOException lastFailure = null;
        int maxAttempts = Math.max(1, config.getMaxRetries());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + bearerToken)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + ": " + abbreviate(responseBody));
                }
                circuitBreaker.recordSuccess(provider);
                return responseBody;
            } catch (IOException ex) {
                lastFailure = ex;
                circuitBreaker.recordFailure(provider);
                log.warn("AI HTTP call failed (attempt {}/{}): {}", attempt, maxAttempts, ex.getMessage());
                if (attempt < maxAttempts) {
                    sleep(config.getRetryBackoffMs() * attempt);
                }
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("AI HTTP call failed");
    }

    public String extractAssistantContent(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IOException("Missing choices[0].message.content in AI response");
        }
        return content.asText();
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }

    private String providerFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "ai-provider";
        }
        try {
            String host = java.net.URI.create(url).getHost();
            return host != null ? host : "ai-provider";
        } catch (Exception ignored) {
            return "ai-provider";
        }
    }
}
