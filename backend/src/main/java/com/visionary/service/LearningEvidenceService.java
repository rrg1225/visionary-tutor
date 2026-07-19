package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.LearningEvidenceLink;
import com.visionary.repository.LearningEvidenceLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningEvidenceService {
    private final LearningEvidenceLinkRepository repository;
    private final ObjectMapper objectMapper;

    public LearningEvidenceLink record(Evidence evidence) {
        LearningEvidenceLink link = new LearningEvidenceLink();
        link.setUserId(evidence.userId());
        link.setLearningSessionId(evidence.learningSessionId());
        link.setEvidenceType(limit(evidence.evidenceType(), 32));
        link.setContentId(limit(evidence.contentId(), 128));
        link.setSectionId(limit(evidence.sectionId(), 128));
        link.setPaperId(limit(evidence.paperId(), 128));
        link.setQuestionId(limit(evidence.questionId(), 128));
        link.setAttemptId(limit(evidence.attemptId(), 128));
        link.setStateReportId(limit(evidence.stateReportId(), 128));
        link.setAiContextKey(limit(evidence.aiContextKey(), 255));
        link.setReportId(limit(evidence.reportId(), 128));
        link.setPracticeId(limit(evidence.practiceId(), 128));
        if (evidence.payload() != null && !evidence.payload().isEmpty()) {
            try {
                link.setPayloadJson(objectMapper.writeValueAsString(evidence.payload()));
            } catch (Exception exception) {
                log.warn("Cannot serialize learning evidence payload: {}", exception.getMessage());
            }
        }
        return repository.save(link);
    }

    private static String limit(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record Evidence(Long userId, Long learningSessionId, String evidenceType,
                           String contentId, String sectionId, String paperId, String questionId,
                           String attemptId, String stateReportId, String aiContextKey,
                           String reportId, String practiceId, Map<String, Object> payload) {
    }
}
