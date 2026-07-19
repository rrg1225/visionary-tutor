package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningSession;
import com.visionary.exception.BizException;
import com.visionary.exception.VisionaryErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalMockService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final PersistenceManager persistenceManager;
    private final GenerationFallbackService generationFallbackService;

    @Value("${visionary.demo-mode.enabled:false}")
    private boolean enabled;

    @Value("${visionary.demo-mode.data-resource:classpath:demo-data/ai-responses.json}")
    private String dataResource;

    public boolean isEnabled() {
        return enabled;
    }

    public ResourceGenerationResponse generateResources(
            String runId,
            LearningSession session,
            String topic,
            ResourceGenerationRequest request
    ) {
        return generateResourcesInternal(runId, session, topic, request);
    }

    public ResourceGenerationResponse generateShowcaseResources(
            String runId,
            LearningSession session,
            String topic,
            ResourceGenerationRequest request
    ) {
        return generateResourcesInternal(runId, session, topic, request);
    }

    private ResourceGenerationResponse generateResourcesInternal(
            String runId,
            LearningSession session,
            String topic,
            ResourceGenerationRequest request
    ) {
        JsonNode root = loadRoot();
        Set<GeneratedArtifact.ArtifactType> requestedTypes = request.resourceTypes() == null || request.resourceTypes().isEmpty()
                ? Set.of(GeneratedArtifact.ArtifactType.values())
                : Set.copyOf(request.resourceTypes());
        List<GeneratedArtifact> artifacts = new ArrayList<>();
        int order = 1;
        for (JsonNode item : root.path("resourceArtifacts")) {
            GeneratedArtifact.ArtifactType type = GeneratedArtifact.ArtifactType.valueOf(item.path("artifactType").asText());
            if (!requestedTypes.contains(type)) {
                continue;
            }
            GeneratedArtifact artifact = new GeneratedArtifact();
            artifact.setRunId(runId);
            artifact.setLearningSessionId(session.getId());
            artifact.setArtifactType(type);
            artifact.setTitle(item.path("title").asText(type.name()));
            artifact.setContentMarkdown(item.path("contentMarkdown").asText(""));
            artifact.setCitationsJson("[]");
            artifact.setValidationStatus("DEMO_VERIFIED");
            artifact.setPublishStatus("PUBLISHED");
            artifact.setReviewNotes("Demo Mode local fixture");
            artifact.setProgress(100);
            generationFallbackService.markDemoGeneration(artifact, "LocalMockService");
            artifacts.add(persistenceManager.saveAndIndexArtifact(artifact));
            persistenceManager.saveStep(
                    runId,
                    session.getId(),
                    "LocalMockService",
                    order++,
                    topic + " / " + type.name(),
                    "Loaded deterministic demo artifact",
                    "DEMO_MODE"
            );
        }
        persistenceManager.markResourceGenerationPhase(session);
        return new ResourceGenerationResponse(
                runId,
                artifacts,
                persistenceManager.findRunSteps(runId),
                "Demo Mode: 已从本地演示数据生成 " + artifacts.size() + " 类个性化资源"
        );
    }

    public String openAiCompatibleResponse(String provider) throws IOException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", streamText());
        ObjectNode choice = objectMapper.createObjectNode();
        choice.set("message", message);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("provider", provider);
        root.putArray("choices").add(choice);
        return objectMapper.writeValueAsString(root);
    }

    public String streamText() {
        return loadRoot().path("chatFallback").asText("Demo Mode local response");
    }

    public String imageUrl() {
        return loadRoot().path("imageUrl").asText("/demo-data/teaching-keyframe.svg");
    }

    public String videoTaskId() {
        return "demo-video-task";
    }

    public String videoUrl() {
        return loadRoot().path("videoUrl").asText("/demo-data/teaching-video.mp4");
    }

    private JsonNode loadRoot() {
        Resource resource = resourceLoader.getResource(dataResource);
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException ex) {
            log.error("Failed to load demo data from {}", dataResource, ex);
            throw new BizException(VisionaryErrorCode.DEMO_DATA_UNAVAILABLE, "无法读取本地演示数据");
        }
    }
}
