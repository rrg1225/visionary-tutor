package com.visionary.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.visionary.agent.AgentJsonParser;
import com.visionary.agent.AgentResponse;
import com.visionary.agent.AgentService;
import com.visionary.agent.AgentTaskType;
import com.visionary.agent.AgentType;
import com.visionary.client.XunfeiSparkApiClient;
import com.visionary.dto.AgentInvokeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 情绪感知档案 Agent Handler。
 * 处理 EMOTION_PROFILE 任务，基于讯飞星火 API 分析学生情绪状态。
 */
@Slf4j
@AgentType(AgentTaskType.EMOTION_PROFILE)
@RequiredArgsConstructor
public class EmotionProfileAgentHandler implements AgentService {

    private final XunfeiSparkApiClient xunfeiSparkApiClient;
    private final AgentJsonParser jsonParser;

    @Override
    public AgentResponse<StudentStateProfile> process(AgentInvokeRequest request) {
        long start = System.currentTimeMillis();

        if (xunfeiSparkApiClient.isConfigured()) {
            try {
                String raw = xunfeiSparkApiClient.analyzeEmotionProfile(
                        request.voiceToken(),
                        request.facialToken(),
                        request.payloadText()
                );
                StudentStateProfile profile = parseProfile(raw);
                return AgentResponse.success(
                        AgentTaskType.EMOTION_PROFILE,
                        AgentTaskType.EMOTION_PROFILE,
                        xunfeiSparkApiClient.providerName(),
                        System.currentTimeMillis() - start,
                        profile
                );
            } catch (Exception ex) {
                log.warn("Xunfei emotion analysis failed, using fallback: {}", ex.getMessage());
                StudentStateProfile fallbackProfile = buildFallbackProfile(
                        request.voiceToken(),
                        request.facialToken(),
                        request.payloadText()
                );
                return AgentResponse.fallback(
                        AgentTaskType.EMOTION_PROFILE,
                        AgentTaskType.EMOTION_PROFILE,
                        xunfeiSparkApiClient.providerName(),
                        System.currentTimeMillis() - start,
                        "Xunfei API unavailable: " + ex.getMessage(),
                        fallbackProfile
                );
            }
        }

        StudentStateProfile fallbackProfile = buildFallbackProfile(
                request.voiceToken(),
                request.facialToken(),
                request.payloadText()
        );
        return AgentResponse.fallback(
                AgentTaskType.EMOTION_PROFILE,
                AgentTaskType.EMOTION_PROFILE,
                "local-heuristic",
                System.currentTimeMillis() - start,
                "Xunfei API key not configured",
                fallbackProfile
        );
    }

    private StudentStateProfile parseProfile(String raw) {
        JsonNode node = jsonParser.parseLenient(raw);
        return new StudentStateProfile(
                jsonParser.text(node, "affectiveState", "neutral"),
                jsonParser.text(node, "attentionLevel", "medium"),
                jsonParser.text(node, "cognitiveLoad", "moderate"),
                jsonParser.text(node, "sensorySummary", raw)
        );
    }

    private StudentStateProfile buildFallbackProfile(String voiceToken, String facialToken, String payloadText) {
        String merged = ((voiceToken == null ? "" : voiceToken) + " "
                + (facialToken == null ? "" : facialToken) + " "
                + (payloadText == null ? "" : payloadText)).toLowerCase();

        String affectiveState = "neutral";
        if (merged.contains("anxious") || merged.contains("stress") || merged.contains("nervous")) {
            affectiveState = "anxious";
        } else if (merged.contains("excited") || merged.contains("happy") || merged.contains("confident")) {
            affectiveState = "engaged";
        } else if (merged.contains("tired") || merged.contains("fatigue")) {
            affectiveState = "fatigued";
        }

        String attentionLevel = merged.contains("distract") || merged.contains("low") ? "low" : "medium";
        String cognitiveLoad = merged.contains("overload") || merged.contains("hard") ? "high" : "moderate";

        return new StudentStateProfile(
                affectiveState,
                attentionLevel,
                cognitiveLoad,
                "Heuristic profile synthesized from sensory tags and payload."
        );
    }

    public record StudentStateProfile(
            String affectiveState,
            String attentionLevel,
            String cognitiveLoad,
            String sensorySummary
    ) {
    }
}
