package com.visionary.service;

import com.visionary.config.TtsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AudioCacheService {

    private static final String REDIS_PREFIX = "tts:cache:";

    private final TtsProperties ttsProperties;
    private final StringRedisTemplate redisTemplate;

    public String buildCacheKey(String text, String provider, String voice, String format) {
        String normalized = normalize(text);
        String raw = normalized + "|" + provider + "|" + voice + "|" + format;
        return sha256(raw);
    }

    public Optional<Path> getCachedFile(String cacheKey) {
        if (!ttsProperties.getCache().isEnabled()) {
            return Optional.empty();
        }
        Path path = resolvePath(cacheKey);
        if (Files.exists(path)) {
            return Optional.of(path);
        }
        String redisPath = redisTemplate.opsForValue().get(REDIS_PREFIX + cacheKey);
        if (redisPath != null) {
            Path cached = Path.of(redisPath);
            if (Files.exists(cached)) {
                return Optional.of(cached);
            }
        }
        return Optional.empty();
    }

    public void put(String cacheKey, byte[] audioBytes, String contentType) throws IOException {
        if (!ttsProperties.getCache().isEnabled()) {
            return;
        }
        Path path = resolvePath(cacheKey);
        Files.createDirectories(path.getParent());
        Files.write(path, audioBytes);
        Duration ttl = Duration.ofDays(Math.max(1, ttsProperties.getCache().getRedisTtlDays()));
        redisTemplate.opsForValue().set(REDIS_PREFIX + cacheKey, path.toString(), ttl);
    }

    private Path resolvePath(String cacheKey) {
        String prefix = cacheKey.substring(0, Math.min(2, cacheKey.length()));
        return Path.of(ttsProperties.getCache().getStorageDir(), prefix, cacheKey + ".mp3");
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
