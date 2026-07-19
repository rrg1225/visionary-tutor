package com.visionary.service;

import com.visionary.client.DashScopeTtsClient;
import com.visionary.client.XunfeiTtsClient;
import com.visionary.config.TtsProperties;
import com.visionary.dto.TtsSynthesizeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private final TtsProperties ttsProperties;
    private final AudioCacheService audioCacheService;
    private final DashScopeTtsClient dashScopeTtsClient;
    private final XunfeiTtsClient xunfeiTtsClient;
    private volatile Instant providerUnavailableUntil;
    private volatile String lastProviderError = "";

    public boolean isEnabled() {
        return ttsProperties.isEnabled()
                && (dashScopeTtsClient.isConfigured() || xunfeiTtsClient.isConfigured())
                && !isProviderCoolingDown();
    }

    public String healthMessage() {
        return isProviderCoolingDown() ? lastProviderError : isEnabled() ? "READY" : "云端 TTS 未配置";
    }

    public TtsSynthesizeResponse synthesize(String text, String voice, Double speed, String format) {
        if (!isEnabled()) {
            throw new IllegalStateException("云端 TTS 未配置");
        }
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("朗读文本不能为空");
        }
        String resolvedVoice = voice != null && !voice.isBlank() ? voice : ttsProperties.getDefaultVoice();
        String resolvedFormat = format != null && !format.isBlank() ? format : ttsProperties.getDefaultFormat();
        String primary = ttsProperties.getPrimaryProvider();
        String cacheKey = audioCacheService.buildCacheKey(normalized, primary, resolvedVoice, resolvedFormat);

        long started = System.currentTimeMillis();
        Path cached = audioCacheService.getCachedFile(cacheKey).orElse(null);
        if (cached != null) {
            return new TtsSynthesizeResponse(
                    "/api/tts/audio/" + cacheKey,
                    true,
                    primary,
                    System.currentTimeMillis() - started
            );
        }

        byte[] audio;
        String provider;
        try {
            audio = synthesizeWithProvider(primary, normalized, resolvedVoice, resolvedFormat);
            provider = primary;
        } catch (Exception primaryError) {
            if (!ttsProperties.isFallbackEnabled()) {
                markProviderUnavailable(primaryError, primaryError);
                throw new IllegalStateException("TTS 合成失败: " + primaryError.getMessage(), primaryError);
            }
            String fallback = "dashscope".equalsIgnoreCase(primary) ? "xunfei" : "dashscope";
            try {
                audio = synthesizeWithProvider(fallback, normalized, resolvedVoice, resolvedFormat);
                provider = fallback;
                cacheKey = audioCacheService.buildCacheKey(normalized, provider, resolvedVoice, resolvedFormat);
            } catch (Exception fallbackError) {
                markProviderUnavailable(primaryError, fallbackError);
                throw new IllegalStateException(
                        "TTS 合成失败: " + primaryError.getMessage() + "; fallback: " + fallbackError.getMessage(),
                        fallbackError
                );
            }
        }

        providerUnavailableUntil = null;
        lastProviderError = "";

        try {
            audioCacheService.put(cacheKey, audio, "audio/" + resolvedFormat);
        } catch (Exception e) {
            log.warn("Audio cache write failed: {}", e.getMessage());
        }

        return new TtsSynthesizeResponse(
                "/api/tts/audio/" + cacheKey,
                false,
                provider,
                System.currentTimeMillis() - started
        );
    }

    public Path resolveAudioFile(String cacheKey) {
        if (cacheKey == null || !cacheKey.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("无效的音频缓存键");
        }
        return audioCacheService.getCachedFile(cacheKey)
                .filter(Files::exists)
                .orElseThrow(() -> new IllegalArgumentException("音频不存在或已过期"));
    }

    private byte[] synthesizeWithProvider(String provider, String text, String voice, String format) throws Exception {
        if ("xunfei".equalsIgnoreCase(provider)) {
            if (!xunfeiTtsClient.isConfigured()) {
                throw new IllegalStateException("讯飞 TTS 未配置");
            }
            return xunfeiTtsClient.synthesize(text, voice);
        }
        if (!dashScopeTtsClient.isConfigured()) {
            throw new IllegalStateException("DashScope TTS 未配置");
        }
        return dashScopeTtsClient.synthesize(text, voice, format);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`[^`]+`", " ")
                .replaceAll("[#>*_\\-\\[\\]()]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        int max = Math.max(1, ttsProperties.getMaxTextLength());
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    private boolean isProviderCoolingDown() {
        Instant until = providerUnavailableUntil;
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            providerUnavailableUntil = null;
            lastProviderError = "";
            return false;
        }
        return true;
    }

    private void markProviderUnavailable(Exception primary, Exception fallback) {
        providerUnavailableUntil = Instant.now().plus(Duration.ofMinutes(5));
        lastProviderError = "云端语音服务暂不可用，已切换浏览器朗读";
        log.warn("TTS providers entered cooldown: primary={}, fallback={}",
                primary.getMessage(), fallback.getMessage());
    }
}
