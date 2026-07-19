package com.visionary.controller;

import com.visionary.dto.AppendChatMessageRequest;
import com.visionary.dto.LearningSessionRequest;
import com.visionary.dto.LearningSessionSummaryDto;
import com.visionary.dto.SessionChatMessageDto;
import com.visionary.entity.LearningSession;
import com.visionary.security.AuthContext;
import com.visionary.service.ChatHistoryService;
import com.visionary.service.LearningSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learning-sessions")
@RequiredArgsConstructor
public class LearningSessionController {

    private final LearningSessionService learningSessionService;
    private final ChatHistoryService chatHistoryService;

    @GetMapping
    public List<LearningSession> listSessions(@RequestParam(required = false) Long userId) {
        Long resolvedUserId = userId != null ? userId : requireUserId();
        requireMatchingUser(resolvedUserId);
        return learningSessionService.listSessionsByUser(resolvedUserId);
    }

    @GetMapping("/summaries")
    public List<LearningSessionSummaryDto> listSessionSummaries(@RequestParam(required = false) Long userId) {
        Long resolvedUserId = userId != null ? userId : requireUserId();
        requireMatchingUser(resolvedUserId);
        return chatHistoryService.listSessionSummaries(resolvedUserId);
    }

    @GetMapping("/{id}")
    public LearningSession getSession(@PathVariable Long id) {
        return requireOwnedSession(id);
    }

    @PostMapping
    public ResponseEntity<LearningSession> createSession(@RequestBody LearningSessionRequest request) {
        requireMatchingUser(request.userId());
        LearningSession created = learningSessionService.createSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/new")
    public ResponseEntity<LearningSession> startNewSession(@RequestBody Map<String, String> body) {
        Long userId = requireUserId();
        String topic = body.getOrDefault("topic", "个性化学习会话");
        LearningSession created = learningSessionService.startNewSession(userId, topic);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/activate")
    public LearningSession activateSession(@PathVariable Long id) {
        Long userId = requireUserId();
        return learningSessionService.activateSession(userId, id);
    }

    @PutMapping("/{id}")
    public LearningSession updateSession(@PathVariable Long id, @RequestBody LearningSessionRequest request) {
        requireOwnedSession(id);
        if (request.userId() != null) {
            requireMatchingUser(request.userId());
        }
        return learningSessionService.updateSession(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id) {
        requireOwnedSession(id);
        learningSessionService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<SessionChatMessageDto> listMessages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "GENERAL") String contextType,
            @RequestParam(defaultValue = "") String contextKey
    ) {
        Long userId = requireUserId();
        return chatHistoryService.listMessages(id, userId, contextType, contextKey);
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<SessionChatMessageDto> appendMessage(
            @PathVariable Long id,
            @Valid @RequestBody AppendChatMessageRequest request
    ) {
        Long userId = requireUserId();
        SessionChatMessageDto saved = chatHistoryService.appendMessage(
                id,
                userId,
                request.role(),
                request.content(),
                request.contextType(),
                request.contextKey(),
                request.contextTitle()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后访问聊天记录"));
    }

    private void requireMatchingUser(Long userId) {
        Long currentUserId = requireUserId();
        if (!currentUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他用户的会话");
        }
    }

    private LearningSession requireOwnedSession(Long sessionId) {
        Long userId = requireUserId();
        LearningSession session = learningSessionService.getSession(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该学习会话");
        }
        return session;
    }
}
