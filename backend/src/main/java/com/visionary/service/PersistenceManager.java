package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.AgentRunStep;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningSession;
import com.visionary.exception.ResourceNotFoundException;
import com.visionary.repository.AgentRunStepRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.resourcegeneration.application.ResourceContentContractValidator;
import com.visionary.util.OssUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersistenceManager {

    private static final Pattern CITATION_HANDLE_PATTERN = Pattern.compile("\\[(cite-[^\\]\\s]+)]");

    private final GeneratedArtifactRepository artifactRepository;
    private final AgentRunStepRepository stepRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final OssUtil ossUtil;
    private final ResourceVectorIndexService resourceVectorIndexService;
    private final LearningPathGraphService learningPathGraphService;
    private final ObjectMapper objectMapper;
    private final ResourceContentContractValidator contentContractValidator;

    public LearningSession requireSession(Long learningSessionId) {
        return learningSessionRepository.findById(learningSessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Learning session not found: " + learningSessionId));
    }

    public List<GeneratedArtifact> listVisibleArtifacts(Long learningSessionId) {
        return findSessionArtifacts(learningSessionId).stream()
                .filter(this::isVisibleToLearner)
                .peek(this::attachSignedMediaUrls)
                .toList();
    }

    public List<GeneratedArtifact> findSessionArtifacts(Long learningSessionId) {
        return artifactRepository.findByLearningSessionIdOrderByGmtCreatedDesc(learningSessionId);
    }

    public List<GeneratedArtifact> findRunArtifacts(String runId) {
        return artifactRepository.findByRunIdOrderByIdAsc(runId);
    }

    public List<AgentRunStep> findRunSteps(String runId) {
        return stepRepository.findByRunIdOrderByStepOrderAsc(runId);
    }

    public void markResourceGenerationPhase(LearningSession session) {
        session.setCurrentPhase(LearningSession.LearningPhase.RESOURCE_GENERATION);
        learningSessionRepository.save(session);
    }

    public GeneratedArtifact saveAndIndexArtifact(GeneratedArtifact artifact) {
        artifact.setContentJson(contentContractValidator.normalize(artifact));
        GeneratedArtifact saved = artifactRepository.save(artifact);
        GeneratedArtifact effective = saved != null ? saved : artifact;
        if (learningPathGraphService != null
                && effective.getArtifactType() == GeneratedArtifact.ArtifactType.LEARNING_PATH
                && effective.getId() != null) {
            String graphJson = learningPathGraphService.graphJsonFromMarkdown(
                    effective.getLearningSessionId(),
                    effective.getTitle(),
                    effective.getContentMarkdown()
            );
            effective.setContentJson(learningPathGraphService.mergeGraphIntoContentJson(effective.getContentJson(), graphJson));
            effective.setContentJson(contentContractValidator.normalize(effective));
            effective = artifactRepository.save(effective);
            learningPathGraphService.persistGraph(effective, graphJson);
        }
        if (resourceVectorIndexService != null) {
            try {
                resourceVectorIndexService.indexArtifact(effective);
            } catch (Exception ex) {
                log.warn("Generated artifact vector indexing skipped: {}", ex.getMessage());
            }
        }
        return effective;
    }

    public AgentRunStep saveStep(
            String runId,
            Long sessionId,
            String agentName,
            int order,
            String input,
            String output,
            String critique
    ) {
        AgentRunStep step = new AgentRunStep();
        step.setRunId(runId);
        step.setLearningSessionId(sessionId);
        step.setAgentName(agentName);
        step.setStepOrder(order);
        step.setInputSummary(truncate(input, 1000));
        step.setOutputSummary(truncate(output, 1000));
        step.setCritique(truncate(critique, 1000));
        step.setAuditTraceJson(buildDefaultAuditTrace(agentName, input, output, critique));
        return stepRepository.save(step);
    }

    private String buildDefaultAuditTrace(String agentName, String input, String output, String critique) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("schemaVersion", "agent-audit-v1");
        audit.put("agent", agentName);
        audit.put("inputSchema", Map.of(
                "type", "object",
                "required", List.of("learningSessionId", "topic", "learnerProfileSnapshot")
        ));
        audit.put("outputSchema", Map.of(
                "type", "object",
                "required", List.of("status", "summary", "evidence", "criticComment")
        ));
        audit.put("toolCalls", List.of());
        audit.put("ragEvidence", extractCitationHandles(input, output, critique));
        audit.put("criticComment", truncate(critique, 600));
        audit.put("revisionDiff", inferRevisionDiff(agentName, input, output, critique));
        audit.put("qualitySignals", Map.of(
                "hasInput", input != null && !input.isBlank(),
                "hasOutput", output != null && !output.isBlank(),
                "hasCriticComment", critique != null && !critique.isBlank(),
                "fallback", containsIgnoreCase(input, "fallback")
                        || containsIgnoreCase(output, "fallback")
                        || containsIgnoreCase(critique, "fallback")
                        || containsIgnoreCase(input, "degraded")
                        || containsIgnoreCase(output, "degraded")
                        || containsIgnoreCase(critique, "degraded")
        ));
        try {
            return objectMapper.writeValueAsString(audit);
        } catch (Exception ex) {
            log.warn("Agent step audit trace serialization skipped: {}", ex.getMessage());
            return "{}";
        }
    }

    private List<String> extractCitationHandles(String... texts) {
        List<String> handles = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher matcher = CITATION_HANDLE_PATTERN.matcher(text);
            while (matcher.find()) {
                String handle = matcher.group(1);
                if (!handles.contains(handle)) {
                    handles.add(handle);
                }
            }
        }
        return handles;
    }

    private String inferRevisionDiff(String agentName, String input, String output, String critique) {
        if (!containsIgnoreCase(agentName, "revision") && !containsIgnoreCase(agentName, "replan")) {
            return "";
        }
        return "before=" + truncate(input, 180)
                + "\nafter=" + truncate(output, 180)
                + "\ncritic=" + truncate(critique, 180);
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && needle != null && text.toLowerCase().contains(needle.toLowerCase());
    }

    public void attachSignedMediaUrls(GeneratedArtifact artifact) {
        if (artifact == null || !ossUtil.isAvailable()) {
            return;
        }
        artifact.setMediaUrl(signIfOwnOssObject(artifact.getMediaUrl()));
        artifact.setCoverImageUrl(signIfOwnOssObject(artifact.getCoverImageUrl()));
    }

    private boolean isVisibleToLearner(GeneratedArtifact artifact) {
        String publishStatus = artifact.getPublishStatus();
        return publishStatus == null
                || publishStatus.isBlank()
                || !"BLOCKED".equalsIgnoreCase(publishStatus);
    }

    private String signIfOwnOssObject(String url) {
        if (url == null || url.isBlank() || url.contains("?")) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || !host.startsWith("rag-a3.oss-cn-hangzhou.aliyuncs.com")) {
                return url;
            }
            String key = uri.getPath();
            if (key == null || key.isBlank() || "/".equals(key)) {
                return url;
            }
            if (key.startsWith("/")) {
                key = key.substring(1);
            }
            return ossUtil.generatePresignedUrl(key, 24 * 60 * 60).toString();
        } catch (Exception ex) {
            log.warn("Failed to sign OSS media url for artifact response: {}", ex.getMessage());
            return url;
        }
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
