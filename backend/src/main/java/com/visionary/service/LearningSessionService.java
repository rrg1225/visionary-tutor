package com.visionary.service;

import com.visionary.dto.LearningSessionRequest;
import com.visionary.entity.LearningSession;
import com.visionary.exception.ResourceNotFoundException;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LearningSessionService {

    private final LearningSessionRepository learningSessionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<LearningSession> listSessionsByUser(Long userId) {
        ensureUserExists(userId);
        return learningSessionRepository.findByUserIdOrderByGmtCreatedDesc(userId);
    }

    @Transactional(readOnly = true)
    public LearningSession getSession(Long id) {
        return learningSessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Learning session not found: " + id));
    }

    @Transactional
    public LearningSession createSession(LearningSessionRequest request) {
        if (request.userId() == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (request.topic() == null || request.topic().isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        ensureUserExists(request.userId());

        LearningSession session = new LearningSession();
        applyRequest(session, request, true);
        if (session.getStatus() == null) {
            session.setStatus(LearningSession.SessionStatus.ACTIVE);
        }
        if (session.getStatus() == LearningSession.SessionStatus.ACTIVE) {
            pauseActiveSessions(request.userId());
        }
        return learningSessionRepository.save(session);
    }

    @Transactional
    public LearningSession updateSession(Long id, LearningSessionRequest request) {
        LearningSession session = getSession(id);
        applyRequest(session, request, false);
        return learningSessionRepository.save(session);
    }

    @Transactional
    public void deleteSession(Long id) {
        if (!learningSessionRepository.existsById(id)) {
            throw new ResourceNotFoundException("Learning session not found: " + id);
        }
        learningSessionRepository.deleteById(id);
    }

    @Transactional
    public LearningSession startNewSession(Long userId, String topic) {
        ensureUserExists(userId);
        pauseActiveSessions(userId);
        LearningSession session = new LearningSession();
        session.setUserId(userId);
        session.setTopic(topic == null || topic.isBlank() ? "个性化学习会话" : topic.trim());
        session.setStatus(LearningSession.SessionStatus.ACTIVE);
        session.setCurrentPhase(LearningSession.LearningPhase.STUDENT_PROFILE);
        return learningSessionRepository.save(session);
    }

    @Transactional
    public LearningSession activateSession(Long userId, Long sessionId) {
        ensureUserExists(userId);
        LearningSession session = getSession(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new IllegalArgumentException("Session does not belong to user");
        }
        pauseActiveSessions(userId);
        session.setStatus(LearningSession.SessionStatus.ACTIVE);
        return learningSessionRepository.save(session);
    }

    private void pauseActiveSessions(Long userId) {
        List<LearningSession> activeSessions = learningSessionRepository.findByUserIdAndStatus(
                userId,
                LearningSession.SessionStatus.ACTIVE
        );
        for (LearningSession active : activeSessions) {
            active.setStatus(LearningSession.SessionStatus.PAUSED);
        }
        if (!activeSessions.isEmpty()) {
            learningSessionRepository.saveAll(activeSessions);
        }
    }

    private void ensureUserExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
    }

    private void applyRequest(LearningSession session, LearningSessionRequest request, boolean allowOwnerAssignment) {
        if (request.userId() != null) {
            ensureUserExists(request.userId());
            if (allowOwnerAssignment) {
                session.setUserId(request.userId());
            } else if (!request.userId().equals(session.getUserId())) {
                throw new IllegalArgumentException("Learning session owner cannot be changed");
            }
        }
        if (request.topic() != null && !request.topic().isBlank()) {
            session.setTopic(request.topic());
        }
        if (request.status() != null) {
            session.setStatus(request.status());
        }
        if (request.currentPhase() != null) {
            session.setCurrentPhase(request.currentPhase());
        }
        if (request.streamingHandout() != null) {
            session.setStreamingHandout(request.streamingHandout());
        }
        if (request.conversationSummary() != null) {
            session.setConversationSummary(request.conversationSummary());
        }
        if (request.lastEmotionSnapshot() != null) {
            session.setLastEmotionSnapshot(request.lastEmotionSnapshot());
        }
        if (request.assessmentFileName() != null) {
            session.setAssessmentFileName(request.assessmentFileName());
        }
    }
}
