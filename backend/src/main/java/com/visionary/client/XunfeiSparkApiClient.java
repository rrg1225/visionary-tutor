package com.visionary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.AiApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Xunfei Spark Open API client (HTTP). Voice / face tokens from the frontend are
 * treated as multimodal summaries to be fused into a student affective profile.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XunfeiSparkApiClient {

    private static final String PROVIDER = "xunfei-spark";

    private final AiApiConfig config;
    private final HttpAiClientSupport httpSupport;
    private final ObjectMapper objectMapper;

    public String analyzeEmotionProfile(String voiceToken, String facialToken, String payloadText) throws IOException {
        String apiKey = resolveApiKey();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getXunfeiSparkModel());
        body.put("temperature", 0.2);

        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", """
                You are an affective computing tutor assistant.
                Infer student emotional and cognitive state from sensory summaries.
                Return strict JSON with keys:
                affectiveState, attentionLevel, cognitiveLoad, sensorySummary.
                """);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", buildUserContent(voiceToken, facialToken, payloadText));

        String url = config.getXunfeiSparkBaseUrl() + "/chat/completions";
        String responseJson = httpSupport.postJsonWithRetry(url, apiKey, body.toString());
        return httpSupport.extractAssistantContent(responseJson);
    }

    public boolean isConfigured() {
        return config.isXunfeiConfigured();
    }

    public String providerName() {
        return PROVIDER;
    }

    private String resolveApiKey() {
        if (config.getXunfeiSparkMaxKey() != null && !config.getXunfeiSparkMaxKey().isBlank()) {
            return config.getXunfeiSparkMaxKey();
        }
        return config.getXunfeiSparkVoiceKey();
    }

    private String buildUserContent(String voiceToken, String facialToken, String payloadText) {
        StringBuilder builder = new StringBuilder();
        builder.append("Voice sensory token/summary: ")
                .append(blankToDefault(voiceToken, "N/A"))
                .append("\n");
        builder.append("Facial sensory token/summary: ")
                .append(blankToDefault(facialToken, "N/A"))
                .append("\n");
        builder.append("Additional payload: ")
                .append(blankToDefault(payloadText, "N/A"));
        return builder.toString();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
