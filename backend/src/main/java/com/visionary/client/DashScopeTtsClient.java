package com.visionary.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.AiApiConfig;
import com.visionary.config.TtsProperties;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class DashScopeTtsClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AiApiConfig aiApiConfig;
    private final TtsProperties ttsProperties;
    private final ObjectMapper objectMapper;

    private volatile OkHttpClient httpClient;

    public boolean isConfigured() {
        return aiApiConfig.isDashScopeConfigured();
    }

    public byte[] synthesize(String text, String voice, String format) throws IOException {
        if (!isConfigured()) {
            throw new IOException("DASHSCOPE_API_KEY is not configured");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", ttsProperties.getDashscope().getModel());
        ObjectNode input = body.putObject("input");
        input.put("text", text);
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("voice", voice != null && !voice.isBlank() ? voice : ttsProperties.getDefaultVoice());
        parameters.put("format", format != null && !format.isBlank() ? format : ttsProperties.getDefaultFormat());

        Request request = new Request.Builder()
                .url(ttsProperties.getDashscope().getUrl())
                .addHeader("Authorization", "Bearer " + aiApiConfig.getDashScopeKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("DashScope TTS failed: HTTP " + response.code());
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode audioNode = root.path("output").path("audio");
            if (audioNode.isTextual()) {
                return Base64.getDecoder().decode(audioNode.asText());
            }
            JsonNode urlNode = root.path("output").path("audio_url");
            if (urlNode.isTextual()) {
                return downloadBytes(urlNode.asText());
            }
            throw new IOException("DashScope TTS response missing audio");
        }
    }

    private byte[] downloadBytes(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("DashScope audio download failed");
            }
            return response.body().bytes();
        }
    }

    private OkHttpClient client() {
        OkHttpClient local = httpClient;
        if (local == null) {
            synchronized (this) {
                local = httpClient;
                if (local == null) {
                    local = new OkHttpClient.Builder()
                            .connectTimeout(aiApiConfig.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(aiApiConfig.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                            .writeTimeout(aiApiConfig.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                            .build();
                    httpClient = local;
                }
            }
        }
        return local;
    }
}
