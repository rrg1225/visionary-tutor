package com.visionary.service;

import com.visionary.dto.LearningSessionSummaryDto;
import com.visionary.dto.SessionChatMessageDto;
import com.visionary.entity.LearningSession;
import com.visionary.entity.SessionChatMessage;
import com.visionary.exception.ResourceNotFoundException;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.SessionChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private static final int PREVIEW_MAX_CHARS = 120;

    private final SessionChatMessageRepository messageRepository;
    private final LearningSessionRepository learningSessionRepository;

    @Transactional(readOnly = true)
    public List<SessionChatMessageDto> listMessages(Long learningSessionId, Long userId) {
        return listMessages(learningSessionId, userId, "GENERAL", "");
    }

    @Transactional(readOnly = true)
    public List<SessionChatMessageDto> listMessages(
            Long learningSessionId,
            Long userId,
            String contextType,
            String contextKey
    ) {
        requireOwnedSession(learningSessionId, userId);
        String normalizedType = normalizeContextType(contextType);
        String normalizedKey = normalizeContextKey(contextKey);
        return messageRepository.findByLearningSessionIdAndContextTypeAndContextKeyOrderBySeqAsc(
                        learningSessionId,
                        normalizedType,
                        normalizedKey
                ).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LearningSessionSummaryDto> listSessionSummaries(Long userId) {
        return learningSessionRepository.findByUserIdOrderByGmtModifiedDesc(userId).stream()
                .map(session -> toSummary(session, userId))
                .toList();
    }

    @Transactional
    public SessionChatMessageDto appendMessage(
            Long learningSessionId,
            Long userId,
            String role,
            String content
    ) {
        return appendMessage(learningSessionId, userId, role, content, "GENERAL", "", null);
    }

    @Transactional
    public SessionChatMessageDto appendMessage(
            Long learningSessionId,
            Long userId,
            String role,
            String content,
            String contextType,
            String contextKey,
            String contextTitle
    ) {
        return appendMessage(
                learningSessionId,
                userId,
                role,
                content,
                contextType,
                contextKey,
                contextTitle,
                null
        );
    }

    @Transactional
    public SessionChatMessageDto appendMessage(
            Long learningSessionId,
            Long userId,
            String role,
            String content,
            String contextType,
            String contextKey,
            String contextTitle,
            String metadataJson
    ) {
        requireOwnedSession(learningSessionId, userId);
        String normalizedRole = normalizeRole(role);
        String normalizedContent = normalizeContent(content);
        String normalizedType = normalizeContextType(contextType);
        String normalizedKey = normalizeContextKey(contextKey);
        if (isDuplicateTail(learningSessionId, normalizedType, normalizedKey, normalizedRole, normalizedContent)) {
            return messageRepository.findTopByLearningSessionIdAndContextTypeAndContextKeyOrderBySeqDesc(
                            learningSessionId,
                            normalizedType,
                            normalizedKey
                    )
                    .map(this::toDto)
                    .orElseThrow(() -> new ResourceNotFoundException("Chat message not found"));
        }
        int nextSeq = (int) messageRepository.countByLearningSessionId(learningSessionId) + 1;
        SessionChatMessage message = new SessionChatMessage();
        message.setLearningSessionId(learningSessionId);
        message.setUserId(userId);
        message.setRole(normalizedRole);
        message.setContextType(normalizedType);
        message.setContextKey(normalizedKey);
        message.setContextTitle(trim(contextTitle, 255));
        message.setContent(normalizedContent);
        message.setMetadataJson(trim(metadataJson, 20_000));
        message.setSeq(nextSeq);
        return toDto(messageRepository.save(message));
    }

    @Transactional
    public void persistTurn(
            Long learningSessionId,
            Long userId,
            String userContent,
            String assistantContent
    ) {
        if (learningSessionId == null || userId == null) {
            return;
        }
        if (!learningSessionRepository.existsById(learningSessionId)) {
            return;
        }
        LearningSession session = learningSessionRepository.findById(learningSessionId).orElse(null);
        if (session == null || !userId.equals(session.getUserId())) {
            return;
        }
        if (userContent != null && !userContent.isBlank()) {
            appendMessage(learningSessionId, userId, "user", userContent);
        }
        if (assistantContent != null && !assistantContent.isBlank()) {
            appendMessage(learningSessionId, userId, "assistant", assistantContent);
        }
    }

    private LearningSessionSummaryDto toSummary(LearningSession session, Long userId) {
        if (!userId.equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该学习会话");
        }
        long count = messageRepository.countByLearningSessionId(session.getId());
        String preview = messageRepository.findTopByLearningSessionIdOrderBySeqDesc(session.getId())
                .map(msg -> preview(msg.getContent()))
                .orElse("");
        return new LearningSessionSummaryDto(
                session.getId(),
                session.getUserId(),
                session.getTopic(),
                session.getStatus(),
                session.getCurrentPhase(),
                preview,
                (int) count,
                session.getGmtCreated(),
                session.getGmtModified()
        );
    }

    private SessionChatMessageDto toDto(SessionChatMessage message) {
        return new SessionChatMessageDto(
                message.getId(),
                message.getLearningSessionId(),
                message.getRole(),
                message.getContextType(),
                message.getContextKey(),
                message.getContextTitle(),
                message.getContent(),
                message.getMetadataJson(),
                message.getSeq(),
                message.getGmtCreated()
        );
    }

    private void requireOwnedSession(Long learningSessionId, Long userId) {
        LearningSession session = learningSessionRepository.findById(learningSessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Learning session not found: " + learningSessionId));
        if (!userId.equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该学习会话");
        }
    }

    private boolean isDuplicateTail(
            Long learningSessionId,
            String contextType,
            String contextKey,
            String role,
            String content
    ) {
        return messageRepository.findTopByLearningSessionIdAndContextTypeAndContextKeyOrderBySeqDesc(
                        learningSessionId,
                        contextType,
                        contextKey
                )
                .map(last -> role.equals(last.getRole()) && content.equals(last.getContent()))
                .orElse(false);
    }

    private static String normalizeContextType(String value) {
        String normalized = trim(value, 32).toUpperCase();
        return normalized.isBlank() ? "GENERAL" : normalized.replaceAll("[^A-Z0-9_]", "_");
    }

    private static String normalizeContextKey(String value) {
        return trim(value, 160);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role is required");
        }
        String normalized = role.trim().toLowerCase();
        if (!"user".equals(normalized) && !"assistant".equals(normalized)) {
            throw new IllegalArgumentException("role must be user or assistant");
        }
        return normalized;
    }

    private static String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        return content.trim();
    }

    private static String trim(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= PREVIEW_MAX_CHARS
                ? compact
                : compact.substring(0, PREVIEW_MAX_CHARS) + "…";
    }
}
