package com.visionary.agent;

import com.visionary.dto.ChatMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-aware sliding window for multi-turn chat.
 * <p>Always retains the system prompt; retains at most the most recent N non-system messages.</p>
 */
@Slf4j
@Service
public class ConversationContextService {

    public static final int DEFAULT_RECENT_MESSAGE_LIMIT = 15;

    private final int recentMessageLimit;
    private final int maxContextTokens;

    public ConversationContextService(
            @Value("${visionary.chat.recent-message-limit:" + DEFAULT_RECENT_MESSAGE_LIMIT + "}") int recentMessageLimit,
            @Value("${visionary.material-max-tokens:4096}") int maxContextTokens
    ) {
        this.recentMessageLimit = Math.max(1, recentMessageLimit);
        this.maxContextTokens = Math.max(512, maxContextTokens);
    }

    /**
     * Builds trimmed message list and memory status for SSE {@code memory_status} events.
     *
     * @param systemPrompt fixed system instruction (never dropped)
     * @param history      full client history (may include duplicate system rows; those are stripped)
     */
    public ConversationContext assemble(String systemPrompt, List<ChatMessageDto> history) {
        String effectiveSystem = (systemPrompt == null || systemPrompt.isBlank())
                ? defaultSystemPrompt()
                : systemPrompt.trim();

        List<ChatMessageDto> nonSystem = new ArrayList<>();
        if (history != null) {
            for (ChatMessageDto msg : history) {
                if (msg == null || msg.content() == null || msg.content().isBlank()) {
                    continue;
                }
                if ("system".equalsIgnoreCase(msg.role())) {
                    continue;
                }
                nonSystem.add(msg);
            }
        }

        int dropped = 0;
        List<ChatMessageDto> windowed = nonSystem;
        if (nonSystem.size() > recentMessageLimit) {
            dropped = nonSystem.size() - recentMessageLimit;
            windowed = new ArrayList<>(nonSystem.subList(dropped, nonSystem.size()));
            log.debug("Sliding window dropped {} older messages, kept {}", dropped, windowed.size());
        }

        int currentTokens = estimateTokens(effectiveSystem) + windowed.stream()
                .mapToInt(m -> estimateTokens(m.content()))
                .sum();

        MemoryStatus status = new MemoryStatus(
                currentTokens,
                maxContextTokens,
                windowed.size(),
                dropped
        );

        return new ConversationContext(effectiveSystem, List.copyOf(windowed), status);
    }

    /**
     * Heuristic token estimate (~4 Latin chars or ~1.5 CJK chars per token).
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk / 1.5) + (int) Math.ceil(other / 4.0) + 4;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    private static String defaultSystemPrompt() {
        return """
                You are VisionaryTutor, an adaptive AI learning companion.
                Answer clearly, use examples when helpful, and ground explanations in provided context.
                """;
    }
}
