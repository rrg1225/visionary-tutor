package com.visionary.service;

import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.ChatMessageDto;
import com.visionary.entity.DiagnosticReport;
import com.visionary.entity.DiagnosticWeakNode;
import com.visionary.entity.LearningSession;
import com.visionary.entity.User;
import com.visionary.repository.DiagnosticReportRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningMemoryService {

    private static final int MAX_SUMMARY_CHARS = 1800;
    private static final int MAX_DROPPED_MESSAGES = 8;

    private final UserRepository userRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final DiagnosticReportRepository diagnosticReportRepository;
    private final DeepSeekApiClient deepSeekApiClient;

    @Transactional(readOnly = true)
    public String buildMemoryPrompt(
            Long userId,
            Long learningSessionId,
            String studentProfileSnapshot,
            String emotionProfileSnapshot,
            String clientContext
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== Learner Memory Layers ===\n");
        prompt.append("Layer 1 - Session Metadata:\n");
        appendLine(prompt, "clientContext", clientContext);
        appendLine(prompt, "learningSessionId", learningSessionId == null ? null : learningSessionId.toString());

        prompt.append("\nLayer 2 - User Memory:\n");
        if (userId != null) {
            userRepository.findById(userId).ifPresent(user -> appendUserMemory(prompt, user));
            appendRecentWeakNodes(prompt, userId);
        }
        appendLine(prompt, "studentProfileSnapshot", studentProfileSnapshot);
        appendLine(prompt, "emotionProfileSnapshot", emotionProfileSnapshot);
        appendEmotionInterventionPolicy(prompt, emotionProfileSnapshot);

        prompt.append("\nLayer 3 - Conversation Summary:\n");
        if (learningSessionId != null) {
            learningSessionRepository.findById(learningSessionId)
                    .map(LearningSession::getConversationSummary)
                    .filter(summary -> !summary.isBlank())
                    .ifPresentOrElse(
                            summary -> prompt.append(truncate(summary, MAX_SUMMARY_CHARS)).append('\n'),
                            () -> prompt.append("(none yet)\n")
                    );
        } else {
            prompt.append("(no persisted session)\n");
        }

        prompt.append("\nLayer 4 - Current Session Window:\n");
        prompt.append("The latest messages are supplied after this system prompt. Use them as working memory.\n");
        prompt.append("Adapt pacing, examples, and remediation to the learner memory above. Do not invent facts not present in memory or retrieved knowledge.\n");
        return prompt.toString();
    }

    private void appendEmotionInterventionPolicy(StringBuilder prompt, String emotionProfileSnapshot) {
        if (emotionProfileSnapshot == null || emotionProfileSnapshot.isBlank()) {
            return;
        }
        String signal = emotionProfileSnapshot.toLowerCase(java.util.Locale.ROOT);
        boolean confused = signal.contains("困惑")
                || signal.contains("confusion")
                || signal.contains("confused")
                || signal.contains("认知负荷")
                || signal.contains("cognitive");
        boolean fatigued = signal.contains("疲劳")
                || signal.contains("fatigue")
                || signal.contains("tired")
                || signal.contains("专注")
                || signal.contains("attention");
        if (!confused && !fatigued) {
            return;
        }
        prompt.append("\nLayer 2.5 - Emotion Intervention Policy:\n");
        if (confused) {
            prompt.append("- The learner is likely confused or overloaded. Stop increasing abstraction; restate the previous idea with one simple analogy, one minimal worked example, then one self-check question.\n");
        }
        if (fatigued) {
            prompt.append("- The learner may have reduced attention. Keep the next answer shorter, use visible steps, and avoid long lists unless the learner asks for depth.\n");
        }
        prompt.append("- Do not mention camera inference unless the learner explicitly asks; treat the signal as a private adaptation cue.\n");
    }

    @Transactional
    public void summarizeDroppedMessages(Long learningSessionId, List<ChatMessageDto> fullHistory, int droppedCount) {
        if (learningSessionId == null || droppedCount <= 0 || fullHistory == null || fullHistory.isEmpty()) {
            return;
        }
        Optional<LearningSession> sessionOpt = learningSessionRepository.findById(learningSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        LearningSession session = sessionOpt.get();
        List<ChatMessageDto> dropped = fullHistory.stream()
                .filter(msg -> msg != null && !"system".equalsIgnoreCase(msg.role()))
                .limit(Math.min(droppedCount, MAX_DROPPED_MESSAGES))
                .toList();
        if (dropped.isEmpty()) {
            return;
        }

        String oldSummary = session.getConversationSummary();
        String newSummary = summarize(oldSummary, dropped);
        session.setConversationSummary(truncate(newSummary, MAX_SUMMARY_CHARS));
        learningSessionRepository.save(session);
    }

    @Transactional
    public void updateSessionMemory(Long learningSessionId, String handout, String emotionSnapshot) {
        if (learningSessionId == null) {
            return;
        }
        learningSessionRepository.findById(learningSessionId).ifPresent(session -> {
            if (handout != null && !handout.isBlank()) {
                session.setStreamingHandout(handout);
            }
            if (emotionSnapshot != null && !emotionSnapshot.isBlank()) {
                session.setLastEmotionSnapshot(emotionSnapshot);
            }
            learningSessionRepository.save(session);
        });
    }

    private String summarize(String oldSummary, List<ChatMessageDto> dropped) {
        String transcript = dropped.stream()
                .map(msg -> msg.role() + ": " + truncate(msg.content(), 500))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        if (!deepSeekApiClient.isConfigured()) {
            return heuristicSummary(oldSummary, transcript);
        }

        String system = "You summarize tutoring memory for an adaptive learning agent. Keep factual, concise, and useful.";
        String user = """
                Existing summary:
                %s

                Newly truncated dialogue:
                %s

                Update the session memory in Chinese within 180 words. Include: knowledge points, weak points, error patterns, learning pace, and emotion/attention signals if present.
                """.formatted(blankToNone(oldSummary), transcript);
        try {
            return deepSeekApiClient.chat(system, user, false);
        } catch (IOException e) {
            log.warn("LLM memory summary failed, using heuristic fallback: {}", e.getMessage());
            return heuristicSummary(oldSummary, transcript);
        }
    }

    private String heuristicSummary(String oldSummary, String transcript) {
        String compact = transcript.replaceAll("\\s+", " ").trim();
        String addition = compact.length() > 360 ? compact.substring(0, 360) + "..." : compact;
        if (oldSummary == null || oldSummary.isBlank()) {
            return "对话摘要：" + addition;
        }
        return oldSummary + "\n补充：" + addition;
    }

    private void appendUserMemory(StringBuilder prompt, User user) {
        appendLine(prompt, "displayName", user.getDisplayName());
        appendLine(prompt, "gradeLevel", user.getGradeLevel());
        appendLine(prompt, "learningGoal", user.getLearningGoal());
        appendLine(prompt, "storedEmotionProfile", user.getEmotionProfileSnapshot());
    }

    private void appendRecentWeakNodes(StringBuilder prompt, Long userId) {
        List<DiagnosticReport> reports = diagnosticReportRepository.findRecentByUserId(userId);
        int appended = 0;
        for (DiagnosticReport report : reports) {
            for (DiagnosticWeakNode node : report.getWeakNodes()) {
                prompt.append("- weakNode: ")
                        .append(node.getNodeName())
                        .append(" | layer=")
                        .append(node.getKnowledgeLayer())
                        .append(" | mastery=")
                        .append(node.getMasteryScore())
                        .append("%\n");
                appended++;
                if (appended >= 6) {
                    return;
                }
            }
        }
        if (appended == 0) {
            prompt.append("- weakNode: none persisted yet\n");
        }
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append("- ").append(label).append(": ").append(truncate(value, 600)).append('\n');
        }
    }

    private static String blankToNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
