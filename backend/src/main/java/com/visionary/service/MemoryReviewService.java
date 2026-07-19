package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryReviewService {

    private static final int MAX_REVIEW_ROUNDS = 3;
    private static final double PASS_THRESHOLD = 0.60D;
    private static final Pattern SCORE_PATTERN = Pattern.compile("\"score\"\\s*:\\s*([0-9.]+)");

    private final DeepSeekApiClient deepSeekApiClient;
    private final ObjectMapper objectMapper;

    public record ReviewResult(
            boolean approved,
            double score,
            String comment,
            int roundsUsed,
            boolean llmUsed
    ) {
    }

    public ReviewResult reviewMemoryUpdate(
            String previousProfileSummary,
            String sourceContext,
            String proposedUpdate,
            String memoryKey
    ) {
        String candidate = proposedUpdate == null ? "" : proposedUpdate.trim();
        if (candidate.isBlank()) {
            return new ReviewResult(false, 0.0D, "empty update rejected", 0, false);
        }

        if (!deepSeekApiClient.isConfigured()) {
            double score = ruleBasedScore(previousProfileSummary, candidate, memoryKey);
            return new ReviewResult(score >= PASS_THRESHOLD, score, "rule-based review", 1, false);
        }

        String current = candidate;
        for (int round = 1; round <= MAX_REVIEW_ROUNDS; round++) {
            try {
                String raw = deepSeekApiClient.chat(
                        reviewSystemPrompt(),
                        reviewUserPrompt(previousProfileSummary, sourceContext, current, memoryKey, round),
                        false
                );
                double score = parseScore(raw);
                String comment = extractComment(raw);
                if (score >= PASS_THRESHOLD) {
                    return new ReviewResult(true, score, comment, round, true);
                }
                if (round == MAX_REVIEW_ROUNDS) {
                    return new ReviewResult(false, score, comment, round, true);
                }
                current = refineUpdate(current, comment);
            } catch (Exception e) {
                log.warn("Memory review round {} failed: {}", round, e.getMessage());
                double score = ruleBasedScore(previousProfileSummary, current, memoryKey);
                return new ReviewResult(score >= PASS_THRESHOLD, score, "fallback: " + e.getMessage(), round, false);
            }
        }
        return new ReviewResult(false, 0.0D, "review exhausted", MAX_REVIEW_ROUNDS, true);
    }

    private double ruleBasedScore(String previousProfileSummary, String proposedUpdate, String memoryKey) {
        double score = 0.55D;
        if (proposedUpdate.length() >= 4 && proposedUpdate.length() <= 2000) {
            score += 0.15D;
        }
        if (memoryKey != null && proposedUpdate.toLowerCase().contains(memoryKey.toLowerCase().replace('_', ' '))) {
            score += 0.05D;
        }
        if (previousProfileSummary != null && !previousProfileSummary.isBlank()
                && !proposedUpdate.equalsIgnoreCase("待观察")) {
            score += 0.10D;
        }
        if (proposedUpdate.matches(".*[\\u4e00-\\u9fffA-Za-z0-9].*")) {
            score += 0.05D;
        }
        return Math.min(1.0D, score);
    }

    private String reviewSystemPrompt() {
        return """
                你是 MemoryReviewAgent，负责审查学习记忆更新是否可信、与上下文一致。
                只输出 JSON：{"score":0.0-1.0,"comment":"简短中文理由","approved":true/false}
                评分标准：语义一致、信息具体、不与已有画像明显冲突、非空泛套话。
                score >= 0.6 视为 approved=true。
                """;
    }

    private String reviewUserPrompt(
            String previousProfileSummary,
            String sourceContext,
            String proposedUpdate,
            String memoryKey,
            int round
    ) {
        return """
                审查轮次：%d
                记忆键：%s
                已有画像摘要：
                %s

                来源上下文：
                %s

                待审查记忆更新：
                %s
                """.formatted(
                round,
                memoryKey == null ? "" : memoryKey,
                blankToDash(previousProfileSummary),
                blankToDash(sourceContext),
                proposedUpdate
        );
    }

    private double parseScore(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0.0D;
        }
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            if (node.has("score")) {
                return Math.max(0.0D, Math.min(1.0D, node.path("score").asDouble(0.0D)));
            }
        } catch (Exception ignored) {
            // fall through to regex
        }
        Matcher matcher = SCORE_PATTERN.matcher(raw);
        if (matcher.find()) {
            return Math.max(0.0D, Math.min(1.0D, Double.parseDouble(matcher.group(1))));
        }
        return 0.5D;
    }

    private String extractComment(String raw) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(raw));
            return node.path("comment").asText("no comment");
        } catch (Exception e) {
            return "parse failed";
        }
    }

    private String refineUpdate(String current, String comment) {
        if (comment == null || comment.isBlank()) {
            return current;
        }
        return current + "（审查意见：" + comment + "）";
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
