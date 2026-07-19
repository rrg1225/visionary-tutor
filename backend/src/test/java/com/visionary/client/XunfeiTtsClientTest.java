package com.visionary.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.TtsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XunfeiTtsClientTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private TtsProperties properties;
    private XunfeiTtsClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new TtsProperties();
        configureXunfei();
        client = new XunfeiTtsClient(properties, objectMapper);
    }

    @Test
    void isConfiguredRequiresAllCredentialFields() {
        assertTrue(client.isConfigured());

        properties.getXunfei().setApiSecret(" ");

        assertFalse(client.isConfigured());
    }

    @Test
    void buildSignedUrlUsesConfiguredEndpointWithoutExposingSecret() throws Exception {
        properties.getXunfei().setUrl("https://example.com/custom/tts");

        String signedUrl = client.buildSignedUrl(FIXED_NOW);
        Map<String, String> query = queryParams(signedUrl);
        String authorization = new String(
                Base64.getDecoder().decode(query.get("authorization")),
                StandardCharsets.UTF_8
        );

        assertTrue(signedUrl.startsWith("wss://example.com/custom/tts?"));
        assertEquals("example.com", query.get("host"));
        assertEquals("Thu, 1 Jan 2026 00:00:00 GMT", query.get("date"));
        assertTrue(authorization.contains("api_key=\"api-key\""));
        assertTrue(authorization.contains("algorithm=\"hmac-sha256\""));
        assertFalse(signedUrl.contains("api-secret"));
        assertFalse(authorization.contains("api-secret"));
    }

    @Test
    void buildSignedUrlRejectsUnsupportedSchemes() {
        properties.getXunfei().setUrl("ftp://example.com/v2/tts");

        assertThrows(IllegalArgumentException.class, () -> client.buildSignedUrl(FIXED_NOW));
    }

    @Test
    void buildSynthesisFrameUsesDefaultVoiceAndBase64Text() throws Exception {
        JsonNode root = objectMapper.readTree(client.buildSynthesisFrame("hello", " "));

        assertEquals("app-id", root.path("common").path("app_id").asText());
        assertEquals("lame", root.path("business").path("aue").asText());
        assertEquals("xiaoyan", root.path("business").path("vcn").asText());
        assertEquals(2, root.path("data").path("status").asInt());
        assertEquals(
                "hello",
                new String(
                        Base64.getDecoder().decode(root.path("data").path("text").asText()),
                        StandardCharsets.UTF_8
                )
        );
    }

    @Test
    void synthesizeRejectsBlankTextBeforeOpeningWebSocket() {
        assertThrows(IllegalArgumentException.class, () -> client.synthesize("  ", "xiaoyan"));
    }

    private void configureXunfei() {
        TtsProperties.Xunfei xunfei = properties.getXunfei();
        xunfei.setAppId("app-id");
        xunfei.setApiKey("api-key");
        xunfei.setApiSecret("api-secret");
        xunfei.setVoice("xiaoyan");
        xunfei.setUrl("wss://tts-api.xfyun.cn/v2/tts");
    }

    private static Map<String, String> queryParams(String url) {
        String rawQuery = URI.create(url).getRawQuery();
        return Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                ));
    }
}
