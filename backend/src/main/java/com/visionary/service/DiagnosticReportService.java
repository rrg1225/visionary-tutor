package com.visionary.service;

import com.visionary.dto.DiagnosticReportRequest;
import com.visionary.dto.DiagnosticWeakNodeRequest;
import com.visionary.dto.ProfileExtractionRequest;
import com.visionary.entity.DiagnosticReport;
import com.visionary.entity.DiagnosticWeakNode;
import com.visionary.exception.ResourceNotFoundException;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.repository.DiagnosticReportRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.UserRepository;
import com.visionary.os.LearningEvent;
import com.visionary.os.LearningEventBus;
import com.visionary.os.LearningEventType;
import com.visionary.os.LearnerStateStore;
import com.visionary.service.LearnerProfileExtractionService;
import com.visionary.service.LearningPathRePlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticReportService {

    private final DiagnosticReportRepository diagnosticReportRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final UserRepository userRepository;
    private final LearnerProfileExtractionService learnerProfileExtractionService;
    private final LearningPathRePlanService learningPathRePlanService;
    private final GeneratedArtifactRepository generatedArtifactRepository;
    private final LearningEventBus learningEventBus;
    private final LearnerStateStore learnerStateStore;
    private final LearningPathGraphService learningPathGraphService;

    @Transactional(readOnly = true)
    public List<DiagnosticReport> listReportsBySession(Long sessionId) {
        ensureSessionExists(sessionId);
        return diagnosticReportRepository.findByLearningSessionIdOrderByCreatedDesc(sessionId);
    }

    @Transactional(readOnly = true)
    public DiagnosticReport getReport(Long id) {
        return diagnosticReportRepository.findByIdWithWeakNodes(id)
                .orElseThrow(() -> new ResourceNotFoundException("Diagnostic report not found: " + id));
    }

    @Transactional
    public DiagnosticReport createReport(DiagnosticReportRequest request) {
        if (request.learningSessionId() == null) {
            throw new IllegalArgumentException("learningSessionId is required");
        }
        ensureSessionExists(request.learningSessionId());

        DiagnosticReport report = new DiagnosticReport();
        applyScalars(report, request);
        attachWeakNodes(report, request.weakNodes());
        DiagnosticReport saved = diagnosticReportRepository.save(report);
        triggerProfileRefreshFromDiagnosis(saved, request);
        return saved;
    }

    @Transactional
    public DiagnosticReport updateReport(Long id, DiagnosticReportRequest request) {
        DiagnosticReport report = diagnosticReportRepository.findByIdWithWeakNodes(id)
                .orElseThrow(() -> new ResourceNotFoundException("Diagnostic report not found: " + id));

        applyScalars(report, request);
        if (request.weakNodes() != null) {
            report.getWeakNodes().clear();
            attachWeakNodes(report, request.weakNodes());
        }
        DiagnosticReport saved = diagnosticReportRepository.save(report);
        triggerProfileRefreshFromDiagnosis(saved, request);
        return saved;
    }

    @Transactional
    public void deleteReport(Long id) {
        if (!diagnosticReportRepository.existsById(id)) {
            throw new ResourceNotFoundException("Diagnostic report not found: " + id);
        }
        diagnosticReportRepository.deleteById(id);
    }

    private void ensureSessionExists(Long sessionId) {
        if (!learningSessionRepository.existsById(sessionId)) {
            throw new ResourceNotFoundException("Learning session not found: " + sessionId);
        }
    }

    private void applyScalars(DiagnosticReport report, DiagnosticReportRequest request) {
        if (request.learningSessionId() != null) {
            ensureSessionExists(request.learningSessionId());
            report.setLearningSessionId(request.learningSessionId());
        }
        if (request.diagnosisId() != null) {
            report.setDiagnosisId(request.diagnosisId());
        }
        if (request.reasoningTrace() != null) {
            report.setReasoningTrace(request.reasoningTrace());
        }
        if (request.ragApplicationContext() != null) {
            report.setRagApplicationContext(request.ragApplicationContext());
        }
        if (request.ragAlgorithmContext() != null) {
            report.setRagAlgorithmContext(request.ragAlgorithmContext());
        }
        if (request.ragMathContext() != null) {
            report.setRagMathContext(request.ragMathContext());
        }
    }

    private void attachWeakNodes(DiagnosticReport report, List<DiagnosticWeakNodeRequest> nodes) {
        if (nodes == null) {
            return;
        }
        for (DiagnosticWeakNodeRequest nodeRequest : nodes) {
            validateWeakNode(nodeRequest);
            DiagnosticWeakNode node = new DiagnosticWeakNode();
            node.setDiagnosticReport(report);
            node.setNodeName(nodeRequest.nodeName());
            node.setKnowledgeLayer(nodeRequest.knowledgeLayer());
            node.setMasteryScore(nodeRequest.masteryScore());
            report.getWeakNodes().add(node);
        }
    }

    private void validateWeakNode(DiagnosticWeakNodeRequest nodeRequest) {
        if (nodeRequest.nodeName() == null || nodeRequest.nodeName().isBlank()) {
            throw new IllegalArgumentException("weak node name is required");
        }
        if (nodeRequest.knowledgeLayer() == null) {
            throw new IllegalArgumentException("knowledgeLayer is required");
        }
        if (nodeRequest.masteryScore() == null || nodeRequest.masteryScore() < 0 || nodeRequest.masteryScore() > 100) {
            throw new IllegalArgumentException("masteryScore must be between 0 and 100");
        }
    }

    private void triggerProfileRefreshFromDiagnosis(DiagnosticReport report, DiagnosticReportRequest request) {
        try {
            var sessionOpt = learningSessionRepository.findById(report.getLearningSessionId());
            if (sessionOpt.isEmpty()) return;
            Long userId = sessionOpt.get().getUserId();
            String assessmentSummary = buildAssessmentSummary(report, request);
            String previous = userRepository.findById(userId)
                    .map(u -> u.getLearnerProfileSnapshot() == null ? "{}" : u.getLearnerProfileSnapshot())
                    .orElse("{}");
            ProfileExtractionRequest extractionRequest = new ProfileExtractionRequest(
                    userId,
                    null,
                    assessmentSummary,
                    previous,
                    null
            );
            learnerProfileExtractionService.extract(extractionRequest);
            log.info("Auto profile refresh triggered for user {} after diagnostic report {}", userId, report.getId());

            // Independent path re-plan after profile evolution
            String latestProfile = userRepository.findById(userId)
                    .map(u -> u.getLearnerProfileSnapshot() == null ? "{}" : u.getLearnerProfileSnapshot())
                    .orElse("{}");
            var plan = learningPathRePlanService.rePlan(report.getLearningSessionId(), latestProfile, null);
            String graphJson = learningPathGraphService.graphJsonFromPlan(plan);
            log.info("Learning path re-planned for session {}: {} steps, next milestone={}", report.getLearningSessionId(), plan.steps().size(), plan.nextMilestone());

            // Persist as LEARNING_PATH artifact so frontend ResourceCards shows the dynamic update
            GeneratedArtifact pathArtifact = new GeneratedArtifact();
            pathArtifact.setLearningSessionId(report.getLearningSessionId());
            pathArtifact.setRunId("REPLAN-" + report.getId());
            pathArtifact.setArtifactType(GeneratedArtifact.ArtifactType.LEARNING_PATH);
            pathArtifact.setTitle("动态学习路径（视觉评估后自动重规划）");
            pathArtifact.setContentMarkdown(formatPathPlan(plan));
            pathArtifact.setContentJson(learningPathGraphService.mergeGraphIntoContentJson(null, graphJson));
            pathArtifact.setValidationStatus("PROFILE_DERIVED");
            pathArtifact.setPublishStatus("DEGRADED");
            pathArtifact.setVerificationAuditJson("{\"grounding\":\"profile_and_visual_assessment_signals\",\"ragGrounded\":false}");
            pathArtifact.setReviewNotes("Profile-derived path update triggered by visual assessment; not marked as RAG-grounded.");
            pathArtifact.setProgress(100);
            GeneratedArtifact savedPathArtifact = generatedArtifactRepository.save(pathArtifact);
            learningPathGraphService.persistGraph(savedPathArtifact, graphJson);
            log.info("Dynamic LEARNING_PATH artifact persisted for session {}", report.getLearningSessionId());

            publishAssessmentEvent(userId, report.getLearningSessionId(), latestProfile, request, assessmentSummary);
        } catch (Exception e) {
            log.warn("Profile auto-refresh or path re-plan after diagnosis failed (non-fatal): {}", e.getMessage());
        }
    }

    private void publishAssessmentEvent(
            Long userId,
            Long sessionId,
            String profileSnapshot,
            DiagnosticReportRequest request,
            String assessmentSummary
    ) {
        var state = learnerStateStore.getState(userId);
        List<String> weakPoints = request.weakNodes() == null ? List.of() : request.weakNodes().stream()
                .filter(n -> n.masteryScore() != null && n.masteryScore() < 70)
                .map(n -> n.nodeName())
                .toList();
        Map<String, Object> payload = new HashMap<>();
        payload.put("profileSnapshot", profileSnapshot);
        payload.put("weakPoints", weakPoints);
        payload.put("assessmentSummary", assessmentSummary);
        payload.put("reportId", request.diagnosisId());

        learningEventBus.publish(LearningEvent.of(
                LearningEventType.ASSESSMENT_COMPLETED,
                userId,
                sessionId,
                state.profileVersion(),
                payload
        ));
    }

    private String buildAssessmentSummary(DiagnosticReport report, DiagnosticReportRequest request) {
        StringBuilder sb = new StringBuilder("诊断报告 #").append(report.getId()).append("：");
        if (request.weakNodes() != null && !request.weakNodes().isEmpty()) {
            String nodes = request.weakNodes().stream()
                    .map(n -> n.nodeName() + "(掌握" + n.masteryScore() + "%)")
                    .collect(Collectors.joining(", "));
            sb.append("薄弱点：").append(nodes).append("。");
        }
        if (request.reasoningTrace() != null) {
            sb.append(" 推理：").append(request.reasoningTrace(), 0, Math.min(200, request.reasoningTrace().length()));
        }
        return sb.toString();
    }

    private String formatPathPlan(LearningPathRePlanService.LearningPathPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 动态学习路径（视觉评估后自动重规划）\n\n");
        sb.append("**触发原因**：视觉作业评估发现薄弱点，已更新画像并重新规划。\n\n");
        sb.append("**整体理由**：").append(plan.overallRationale()).append("\n\n");
        sb.append("**预计总时长**：").append(plan.estimatedHours()).append(" 小时\n\n");
        sb.append("## 调整后的学习步骤\n\n");
        for (var step : plan.steps()) {
            sb.append(step.order()).append(". **").append(step.concept()).append("** (当前掌握 ").append(step.currentMastery()).append("%)\n");
            sb.append("   - 推荐资源：").append(step.recommendedResourceType()).append("\n");
            sb.append("   - 预计时长：").append(step.estimatedMinutes()).append(" 分钟\n");
            sb.append("   - 理由：").append(step.rationale()).append("\n\n");
        }
        sb.append("**下一里程碑**：").append(plan.nextMilestone()).append("\n");
        sb.append("\n> 此路径与评估前版本存在差异，系统已自动调整优先级。");
        return sb.toString();
    }
}
