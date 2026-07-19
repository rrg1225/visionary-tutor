package com.visionary.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.TtsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class XunfeiTtsClient {

    private final TtsProperties ttsProperties;
    private final ObjectMapper objectMapper;

    private volatile HttpClient httpClient;

    public boolean isConfigured() {
        TtsProperties.Xunfei xunfei = ttsProperties.getXunfei();
        return hasText(xunfei.getAppId())
                && hasText(xunfei.getApiKey())
                && hasText(xunfei.getApiSecret());
    }

    public byte[] synthesize(String text, String voice) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Xunfei TTS is not configured");
        }
        if (!hasText(text)) {
            throw new IllegalArgumentException("Xunfei TTS text must not be blank");
        }

        TtsProperties.Xunfei xunfei = ttsProperties.getXunfei();
        String signedUrl = buildSignedUrl(Instant.now());
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
        StringBuilder textBuffer = new StringBuilder();

        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocketRef.set(webSocket);
                try {
                    webSocket.sendText(buildSynthesisFrame(text, voice), true);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "request-build-error");
                }
                webSocket.request(1);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence message, boolean last) {
                try {
                    textBuffer.append(message);
                    if (!last) {
                        webSocket.request(1);
                        return null;
                    }

                    JsonNode root = objectMapper.readTree(textBuffer.toString());
                    textBuffer.setLength(0);
                    int code = root.path("code").asInt(-1);
                    if (code != 0) {
                        result.completeExceptionally(buildProviderException(root));
                        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "error");
                        return null;
                    }
                    JsonNode data = root.path("data");
                    if (!data.isMissingNode()) {
                        String audio = data.path("audio").asText("");
                        if (!audio.isBlank()) {
                            byte[] chunk = Base64.getDecoder().decode(audio);
                            int maxAudioBytes = Math.max(1, xunfei.getMaxAudioBytes());
                            if (audioBuffer.size() + chunk.length > maxAudioBytes) {
                                result.completeExceptionally(new IllegalStateException(
                                        "Xunfei TTS audio exceeds configured limit: " + maxAudioBytes + " bytes"
                                ));
                                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "audio-too-large");
                                return null;
                            }
                            audioBuffer.write(chunk);
                        }
                        if (data.path("status").asInt(0) == 2) {
                            byte[] audioBytes = audioBuffer.toByteArray();
                            if (audioBytes.length == 0) {
                                result.completeExceptionally(new IllegalStateException("Xunfei TTS returned empty audio"));
                            } else {
                                result.complete(audioBytes);
                            }
                            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                        }
                    }
                } catch (Exception e) {
                    result.completeExceptionally(e);
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "error");
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                result.completeExceptionally(error);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                if (!result.isDone()) {
                    result.completeExceptionally(new IllegalStateException(
                            "Xunfei TTS connection closed before final audio: status=" + statusCode + ", reason=" + reason
                    ));
                }
                return null;
            }
        };

        WebSocket webSocket = null;
        try {
            webSocket = client()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, xunfei.getConnectTimeoutSeconds())))
                .buildAsync(URI.create(signedUrl), listener)
                    .get(Math.max(1, xunfei.getConnectTimeoutSeconds()) + 5L, TimeUnit.SECONDS);
            webSocketRef.compareAndSet(null, webSocket);

            return result.get(Math.max(1, xunfei.getResponseTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            closeQuietly(webSocket != null ? webSocket : webSocketRef.get(), "timeout");
            throw new IllegalStateException(
                    "Xunfei TTS timed out after " + Math.max(1, xunfei.getResponseTimeoutSeconds()) + " seconds",
                    e
            );
        } catch (ExecutionException e) {
            closeQuietly(webSocket != null ? webSocket : webSocketRef.get(), "exception");
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("Xunfei TTS failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(webSocket != null ? webSocket : webSocketRef.get(), "interrupted");
            throw new IllegalStateException("Xunfei TTS interrupted", e);
        }
    }

    String buildSynthesisFrame(String text, String voice) {
        TtsProperties.Xunfei xunfei = ttsProperties.getXunfei();
        ObjectNode frame = objectMapper.createObjectNode();
        ObjectNode common = frame.putObject("common");
        common.put("app_id", xunfei.getAppId().trim());
        ObjectNode business = frame.putObject("business");
        business.put("aue", hasText(xunfei.getAue()) ? xunfei.getAue().trim() : "lame");
        String defaultVoice = hasText(xunfei.getVoice()) ? xunfei.getVoice().trim() : "xiaoyan";
        business.put("vcn", hasText(voice) ? voice.trim() : defaultVoice);
        business.put("tte", "UTF8");
        ObjectNode data = frame.putObject("data");
        data.put("status", 2);
        data.put("text", Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
        return frame.toString();
    }

    String buildSignedUrl(Instant now) throws Exception {
        URI endpoint = resolveEndpoint();
        String host = endpoint.getHost();
        String hostHeader = endpoint.getPort() > -1 ? host + ":" + endpoint.getPort() : host;
        String path = hasText(endpoint.getRawPath()) ? endpoint.getRawPath() : "/v2/tts";
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(now, ZoneOffset.UTC));
        String signatureOrigin = "host: " + hostHeader + "\n"
                + "date: " + date + "\n"
                + "GET " + path + " HTTP/1.1";
        String signature = hmacSha256(signatureOrigin, ttsProperties.getXunfei().getApiSecret().trim());
        String authorizationOrigin = String.format(
                "api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
                ttsProperties.getXunfei().getApiKey().trim(),
                signature
        );
        String authorization = Base64.getEncoder().encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));
        return endpoint.getScheme() + "://" + hostHeader + path
                + "?authorization=" + java.net.URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                + "&date=" + java.net.URLEncoder.encode(date, StandardCharsets.UTF_8)
                + "&host=" + java.net.URLEncoder.encode(hostHeader, StandardCharsets.UTF_8);
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private URI resolveEndpoint() {
        String configuredUrl = ttsProperties.getXunfei().getUrl();
        URI configured = URI.create(hasText(configuredUrl) ? configuredUrl.trim() : "wss://tts-api.xfyun.cn/v2/tts");
        String scheme = configured.getScheme();
        String webSocketScheme;
        if ("wss".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            webSocketScheme = "wss";
        } else if ("ws".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            webSocketScheme = "ws";
        } else {
            throw new IllegalArgumentException("Unsupported Xunfei TTS URL scheme: " + scheme);
        }
        if (!hasText(configured.getHost())) {
            throw new IllegalArgumentException("Xunfei TTS URL must include a host");
        }
        String path = hasText(configured.getRawPath()) ? configured.getRawPath() : "/v2/tts";
        return URI.create(webSocketScheme + "://" + configured.getHost()
                + (configured.getPort() > -1 ? ":" + configured.getPort() : "")
                + path);
    }

    private IllegalStateException buildProviderException(JsonNode root) {
        int code = root.path("code").asInt(-1);
        String message = root.path("message").asText("unknown provider error");
        String sid = root.path("sid").asText("");
        return new IllegalStateException("Xunfei TTS error: code=" + code
                + ", message=" + message
                + (sid.isBlank() ? "" : ", sid=" + sid));
    }

    private HttpClient client() {
        HttpClient local = httpClient;
        if (local == null) {
            synchronized (this) {
                local = httpClient;
                if (local == null) {
                    local = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(Math.max(1, ttsProperties.getXunfei().getConnectTimeoutSeconds())))
                            .build();
                    httpClient = local;
                }
            }
        }
        return local;
    }

    private void closeQuietly(WebSocket webSocket, String reason) {
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason);
        } catch (Exception e) {
            log.debug("Failed to close Xunfei TTS websocket: {}", e.getMessage());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
