package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Read-through cache for complete, reviewed tutoring answers.
 *
 * <p>Only standalone concept questions are cacheable. Context-dependent follow-ups keep using the
 * full model pipeline so caching never changes conversational semantics. Seeded entries are
 * reviewed teaching answers used by the competition demonstration; live model answers replace or
 * extend the same cache after a successful stream.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnswerCacheService {

    private static final int MAX_CACHEABLE_QUERY_LENGTH = 80;
    private static final String[] CONTEXT_DEPENDENT_MARKERS = {
            "上面", "刚才", "前面", "这个", "那个", "它", "继续", "再说", "换一种", "为什么呢"
    };

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Value("${visionary.chat.answer-cache.enabled:true}")
    private boolean enabled;

    @Value("${visionary.chat.answer-cache.seed-resource:classpath:demo-data/reviewed-chat-answers.json}")
    private String seedResource;

    @Value("${visionary.chat.answer-cache.max-entries:256}")
    private int maxEntries;

    @Value("${visionary.chat.answer-cache.ttl-minutes:1440}")
    private long ttlMinutes;

    private final Map<String, CacheEntry> answers = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75F, true)
    );

    @PostConstruct
    void loadReviewedAnswers() {
        if (!enabled) {
            return;
        }
        Resource resource = resourceLoader.getResource(seedResource);
        try (InputStream input = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            for (JsonNode item : root.path("answers")) {
                String content = item.path("contentMarkdown").asText("").trim();
                if (content.isBlank()) {
                    continue;
                }
                for (JsonNode query : item.path("queries")) {
                    String key = keyFor(query.asText(""), "AUTO");
                    if (!key.isBlank()) {
                        putEntry(key, content, true);
                    }
                }
            }
            log.info("[ChatAnswerCache] loaded {} reviewed entries", answers.size());
        } catch (Exception error) {
            log.warn("[ChatAnswerCache] reviewed seed unavailable: {}", error.getMessage());
        }
    }

    public Optional<String> find(String query, String tutoringMode) {
        if (!enabled || !isCacheable(query)) {
            return Optional.empty();
        }
        String key = keyFor(query, tutoringMode);
        CacheEntry entry = answers.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.reviewed() && entry.createdAtMillis() + ttl().toMillis() < System.currentTimeMillis()) {
            answers.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.content());
    }

    public void remember(String query, String tutoringMode, String content) {
        if (!enabled || !isCacheable(query) || content == null || content.isBlank()) {
            return;
        }
        putEntry(keyFor(query, tutoringMode), content.trim(), false);
    }

    boolean isCacheable(String query) {
        String normalized = normalizeQuery(query);
        if (normalized.isBlank() || normalized.length() > MAX_CACHEABLE_QUERY_LENGTH) {
            return false;
        }
        for (String marker : CONTEXT_DEPENDENT_MARKERS) {
            if (normalized.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private void putEntry(String key, String content, boolean reviewed) {
        synchronized (answers) {
            int capacity = Math.max(16, maxEntries);
            while (answers.size() >= capacity && !answers.containsKey(key)) {
                String eldest = answers.keySet().iterator().next();
                answers.remove(eldest);
            }
            answers.put(key, new CacheEntry(content, System.currentTimeMillis(), reviewed));
        }
    }

    private Duration ttl() {
        return Duration.ofMinutes(Math.max(1, ttlMinutes));
    }

    private static String keyFor(String query, String tutoringMode) {
        String normalized = normalizeQuery(query);
        if (normalized.isBlank()) {
            return "";
        }
        String mode = tutoringMode == null || tutoringMode.isBlank()
                ? "AUTO"
                : tutoringMode.trim().toUpperCase(Locale.ROOT);
        return mode + ":" + normalized;
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、,.!?：:；;‘’'\"“”()（）\\-]+", "")
                .trim();
    }

    private record CacheEntry(String content, long createdAtMillis, boolean reviewed) {
    }
}
