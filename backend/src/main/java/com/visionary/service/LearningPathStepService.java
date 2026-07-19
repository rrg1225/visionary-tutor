package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.dto.LearningPathStepDto;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningPathEdge;
import com.visionary.entity.LearningPathNode;
import com.visionary.entity.LearningPathStep;
import com.visionary.entity.LearningSession;
import com.visionary.repository.LearningPathEdgeRepository;
import com.visionary.repository.LearningPathNodeRepository;
import com.visionary.repository.LearningPathStepRepository;
import com.visionary.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPathStepService {

    private final LearningPathStepRepository stepRepository;
    private final LearningPathNodeRepository nodeRepository;
    private final LearningPathEdgeRepository edgeRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final LearningMasteryPipelineService learningMasteryPipelineService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void syncStepsFromArtifact(GeneratedArtifact artifact) {
        if (artifact == null || artifact.getId() == null || artifact.getLearningSessionId() == null) {
            return;
        }
        LearningSession session = learningSessionRepository.findById(artifact.getLearningSessionId()).orElse(null);
        if (session == null || session.getUserId() == null) {
            return;
        }
        Long userId = session.getUserId();
        List<LearningPathNode> nodes = nodeRepository.findByArtifactIdOrderByOrderIndexAsc(artifact.getId());
        if (nodes.isEmpty()) {
            return;
        }

        // The schema allows one executable step order per user/session. A newly
        // generated path supersedes the previous artifact's executable steps.
        // Use an immediate bulk delete so Hibernate cannot enqueue inserts ahead
        // of deletes and violate uk_path_step_user_session_order on flush.
        stepRepository.deleteByUserIdAndLearningSessionId(userId, artifact.getLearningSessionId());

        List<LearningPathStep> steps = nodes.stream().map(node -> {
            LearningPathStep step = new LearningPathStep();
            step.setUserId(userId);
            step.setLearningSessionId(artifact.getLearningSessionId());
            step.setPathNodeId(node.getId());
            step.setArtifactId(artifact.getId());
            step.setStepOrder(node.getOrderIndex());
            step.setStepTitle(node.getLabel());
            step.setStepGoal(extractRationale(node.getMetadataJson()));
            step.setEstimatedMinutes(node.getEstimatedMinutes());
            step.setStatus("not_started");
            return step;
        }).toList();
        stepRepository.saveAll(steps);
    }

    @Transactional(readOnly = true)
    public List<LearningPathStepDto> listSteps(Long userId, Long learningSessionId) {
        return stepRepository.findByUserIdAndLearningSessionIdOrderByStepOrderAsc(userId, learningSessionId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public LearningPathStepDto updateStepStatus(Long userId, Long learningSessionId, Integer stepOrder, String status) {
        LearningPathStep step = stepRepository
                .findByUserIdAndLearningSessionIdAndStepOrder(userId, learningSessionId, stepOrder)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "学习路径步骤不存在或尚未生成"));

        String normalized = normalizeStatus(status);
        if ("learning".equals(normalized)) {
            assertPrerequisitesMet(userId, learningSessionId, step);
        }

        String previousStatus = step.getStatus();
        step.setStatus(normalized);
        LocalDateTime now = LocalDateTime.now();
        if ("learning".equals(normalized) && step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        if ("finished".equals(normalized) || "skipped".equals(normalized)) {
            step.setCompletedAt(now);
            if (step.getStartedAt() != null) {
                step.setTimeSpentSeconds((int) java.time.Duration.between(step.getStartedAt(), now).getSeconds());
            }
        }

        LearningPathStep saved = stepRepository.save(step);
        if ("finished".equals(normalized) && !"finished".equals(previousStatus)) {
            learningMasteryPipelineService.onPathStepCompleted(
                    userId,
                    learningSessionId,
                    saved.getStepTitle(),
                    saved.getStepOrder()
            );
        }
        return toDto(saved);
    }

    private void assertPrerequisitesMet(Long userId, Long learningSessionId, LearningPathStep step) {
        if (step.getPathNodeId() == null || step.getArtifactId() == null) {
            return;
        }
        LearningPathNode node = nodeRepository.findById(step.getPathNodeId()).orElse(null);
        if (node == null || node.getNodeKey() == null) {
            return;
        }
        List<LearningPathEdge> incoming = edgeRepository.findByLearningSessionIdAndToNodeKey(
                learningSessionId, node.getNodeKey()
        );
        if (incoming.isEmpty()) {
            return;
        }

        Map<String, LearningPathStep> stepsByNodeKey = loadStepsByNodeKey(userId, learningSessionId, step.getArtifactId());
        for (LearningPathEdge edge : incoming) {
            if (!"PREREQUISITE".equalsIgnoreCase(edge.getRelationType())
                    && edge.getRelationType() != null
                    && !edge.getRelationType().isBlank()) {
                continue;
            }
            LearningPathStep prerequisite = stepsByNodeKey.get(edge.getFromNodeKey());
            if (prerequisite == null) {
                continue;
            }
            if (!"finished".equals(prerequisite.getStatus()) && !"skipped".equals(prerequisite.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "请先完成前置步骤「" + prerequisite.getStepTitle() + "」后再开始本步骤"
                );
            }
        }
    }

    private Map<String, LearningPathStep> loadStepsByNodeKey(Long userId, Long learningSessionId, Long artifactId) {
        Map<Long, String> nodeKeys = new HashMap<>();
        for (LearningPathNode node : nodeRepository.findByArtifactIdOrderByOrderIndexAsc(artifactId)) {
            nodeKeys.put(node.getId(), node.getNodeKey());
        }
        Map<String, LearningPathStep> result = new HashMap<>();
        for (LearningPathStep pathStep : stepRepository.findByUserIdAndLearningSessionIdOrderByStepOrderAsc(userId, learningSessionId)) {
            if (pathStep.getPathNodeId() == null) {
                continue;
            }
            String key = nodeKeys.get(pathStep.getPathNodeId());
            if (key != null) {
                result.put(key, pathStep);
            }
        }
        return result;
    }

    private String extractRationale(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            return node.path("rationale").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status required");
        }
        String normalized = status.trim().toLowerCase();
        return switch (normalized) {
            case "not_started", "learning", "finished", "skipped" -> normalized;
            case "in_progress" -> "learning";
            case "completed", "done" -> "finished";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
        };
    }

    private LearningPathStepDto toDto(LearningPathStep step) {
        return new LearningPathStepDto(
                step.getId(),
                step.getStepOrder(),
                step.getStepTitle(),
                step.getStepGoal(),
                step.getEstimatedMinutes(),
                step.getStatus(),
                step.getStartedAt(),
                step.getCompletedAt(),
                step.getTimeSpentSeconds(),
                step.getPathNodeId(),
                step.getArtifactId()
        );
    }
}
