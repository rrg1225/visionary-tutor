package com.visionary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.AiApiConfig;
import com.visionary.dto.ChatMessageDto;
import com.visionary.service.LocalMockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.util.function.Consumer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DeepSeek API client — non-blocking streaming via OkHttp {@code enqueue}.
 * <p>API key is injected from {@code ${DEEPSEEK_API_KEY}} through {@link AiApiConfig}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekApiClient {

    private static final String PROVIDER = "deepseek";
    private static final okhttp3.MediaType JSON_MEDIA_TYPE =
            okhttp3.MediaType.get("application/json; charset=utf-8");

    private final AiApiConfig config;
    private final HttpAiClientSupport httpSupport;
    private final ObjectMapper objectMapper;
    private final ProviderCircuitBreaker circuitBreaker;
    private final ObjectProvider<LocalMockService> localMockServiceProvider;

    private volatile OkHttpClient streamHttpClient;

    /**
     * Standard blocking Chat API (agent batch tasks only).
     */
    public String chat(String systemPrompt, String userPrompt, boolean useReasoner) throws IOException {
        ObjectNode body = buildChatBody(systemPrompt, userPrompt, useReasoner, false);
        String url = config.getDeepSeekBaseUrl() + "/chat/completions";
        String responseJson = httpSupport.postJsonWithRetry(url, config.getDeepSeekKey(), body.toString());
        return httpSupport.extractAssistantContent(responseJson);
    }

    /**
     * Two-turn streaming convenience wrapper.
     */
    public void streamChat(String systemPrompt, String userPrompt, Consumer<String> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
        streamChatMessages(systemPrompt, List.of(new ChatMessageDto("user", userPrompt)), onChunk, onComplete, onError);
    }

    /**
     * Multi-turn streaming — primary production path (async HTTP, no blocking servlet thread).
     */
    public void streamChatMessages(String systemPrompt, List<ChatMessageDto> messages, Consumer<String> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
        LocalMockService localMockService = localMockServiceProvider.getIfAvailable();
        if (localMockService != null && localMockService.isEnabled()) {
            fallbackStream(localMockService.streamText(), onChunk, onComplete, onError);
            return;
        }
        if (!circuitBreaker.allowRequest(PROVIDER)) {
            fallbackStream(sanitizeLearnerQuestion(extractLastUserContent(messages)), onChunk, onComplete, onError);
            return;
        }
        if (!config.isDeepSeekConfigured()) {
            log.warn("DeepSeek API key missing (set DEEPSEEK_API_KEY); using fallback stream");
            String fallback = sanitizeLearnerQuestion(extractLastUserContent(messages));
            fallbackStream(fallback, onChunk, onComplete, onError);
            return;
        }

        try {
            ObjectNode body = buildStreamBody(systemPrompt, messages);
            Request request = buildStreamRequest(body);

            getStreamHttpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    circuitBreaker.recordFailure(PROVIDER);
                    onError.accept(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (response) {
                        if (!response.isSuccessful() || response.body() == null) {
                            String errorBody = response.body() != null ? response.body().string() : "empty body";
                            circuitBreaker.recordFailure(PROVIDER);
                            onError.accept(new IOException("HTTP " + response.code() + ": " + errorBody));
                            return;
                        }
                        pumpSseLines(response, onChunk, onComplete, onError);
                        circuitBreaker.recordSuccess(PROVIDER);
                    } catch (Exception e) {
                        circuitBreaker.recordFailure(PROVIDER);
                        onError.accept(e);
                    }
                }
            });
        } catch (Exception e) {
            circuitBreaker.recordFailure(PROVIDER);
            onError.accept(e);
        }
    }

    public boolean isConfigured() {
        return config.isDeepSeekConfigured();
    }

    public String providerName() {
        return PROVIDER;
    }

    // ==================== private ====================

    private OkHttpClient getStreamHttpClient() {
        OkHttpClient local = streamHttpClient;
        if (local == null) {
            synchronized (this) {
                local = streamHttpClient;
                if (local == null) {
                    local = new OkHttpClient.Builder()
                            .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(0, TimeUnit.SECONDS)
                            .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                            .dispatcher(new okhttp3.Dispatcher())
                            .build();
                    streamHttpClient = local;
                }
            }
        }
        return local;
    }

    private Request buildStreamRequest(ObjectNode body) {
        String url = config.getDeepSeekBaseUrl() + "/chat/completions";
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.getDeepSeekKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
                .build();
    }

    private ObjectNode buildStreamBody(String systemPrompt, List<ChatMessageDto> messages) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getDeepSeekChatModel());
        body.put("stream", true);
        body.put("temperature", 0.3);

        ArrayNode messagesNode = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode system = messagesNode.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
        }
        if (messages != null) {
            for (ChatMessageDto msg : messages) {
                if (msg == null || msg.content() == null || msg.content().isBlank()) {
                    continue;
                }
                ObjectNode node = messagesNode.addObject();
                node.put("role", msg.role());
                node.put("content", msg.content());
            }
        }
        return body;
    }

    private ObjectNode buildChatBody(String systemPrompt, String userPrompt, boolean useReasoner, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", useReasoner
                ? config.getDeepSeekReasonerModel()
                : config.getDeepSeekChatModel());
        body.put("stream", stream);
        body.put("temperature", 0.3);

        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);
        return body;
    }

    private void pumpSseLines(Response response, Consumer<String> onChunk, Runnable onComplete, Consumer<Throwable> onError) throws IOException {
        AtomicBoolean done = new AtomicBoolean(false);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if ("[DONE]".equals(payload)) {
                    done.set(true);
                    onComplete.run();
                    return;
                }
                String content = extractDeltaContent(payload);
                if (content != null && !content.isEmpty()) {
                    onChunk.accept(content);
                }
            }
            if (!done.get()) {
                onComplete.run();
            }
        }
    }

    private String extractDeltaContent(String payload) {
        try {
            var root = objectMapper.readTree(payload);
            var choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            var delta = choices.get(0).path("delta").path("content");
            if (!delta.isMissingNode() && !delta.isNull()) {
                return delta.asText();
            }
            var reasoning = choices.get(0).path("delta").path("reasoning_content");
            if (!reasoning.isMissingNode() && !reasoning.isNull()) {
                return reasoning.asText();
            }
            return null;
        } catch (IOException e) {
            log.warn("SSE payload parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static String extractLastUserContent(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && "user".equalsIgnoreCase(m.role())) {
                return m.content();
            }
        }
        return messages.get(messages.size() - 1).content();
    }

    private void fallbackStream(String userPrompt, Consumer<String> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
        String topic = sanitizeLearnerQuestion(userPrompt);

        String[] chunks = {
                "# 个性化学习讲解\n\n",
                "## 学习目标\n\n",
                topic + "\n\n",
                "### 1. 核心概念\n",
                "先抓住定义、输入输出和常见误区，再进入公式或代码。\n\n",
                "### 2. 图解要点\n",
                "- **Padding**：在输入边缘补值，控制输出特征图尺寸、保留边界信息\n",
                "- **Stride**：卷积核滑动步长，stride 越大输出越小\n",
                "- 尺寸公式：`H_out = floor((H_in + 2P - K) / S) + 1`\n\n",
                "### 3. 练习检查点\n",
                "- 能说清 padding / stride 对特征图尺寸的影响\n",
                "- 能完成一个最小 PyTorch 卷积 shape 实验\n"
        };

        for (String c : chunks) {
            onChunk.accept(c);
        }
        onComplete.run();
    }

    /**
     * Strip RAG injection blocks from the last user turn — never show citation dumps to learners.
     */
    static String sanitizeLearnerQuestion(String raw) {
        if (raw == null || raw.isBlank()) {
            return "当前学习内容";
        }
        String text = raw.trim();
        String[] leakMarkers = {
                "=== Retrieved Knowledge Context",
                "=== Learner Memory Layers",
                "## Application Layer",
                "[检索状态]",
                "[检索模式]",
                "[引用约束]",
                "[输出要求]",
                "[强制输出]",
                "[cite-",
                "mustUseCitationIds"
        };
        for (String marker : leakMarkers) {
            int idx = text.indexOf(marker);
            if (idx > 0) {
                text = text.substring(0, idx).trim();
            }
        }
        int paragraphBreak = text.indexOf("\n\n");
        if (paragraphBreak > 0 && paragraphBreak <= 200) {
            text = text.substring(0, paragraphBreak).trim();
        }
        int lineBreak = text.indexOf('\n');
        if (lineBreak > 0 && lineBreak <= 120) {
            text = text.substring(0, lineBreak).trim();
        }
        if (text.length() > 120) {
            text = text.substring(0, 120) + "…";
        }
        return text.isBlank() ? "当前学习内容" : text;
    }
}
